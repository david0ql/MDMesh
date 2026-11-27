package com.dallycontrol.agent.data

import android.Manifest
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.Log
import com.dallycontrol.core.data.Crossing
import com.dallycontrol.core.data.DataApp
import com.dallycontrol.core.data.DataPeriod
import com.dallycontrol.core.data.MobileDataPlanner
import com.dallycontrol.core.data.QuotaLedger
import com.dallycontrol.core.data.QuotaText
import com.dallycontrol.core.sync.CheckInWorker
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigMobileData
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.MobileDataAppUsage
import com.dallycontrol.proto.MobileDataBlocked
import com.dallycontrol.proto.MobileDataStatus
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Mobile data per app (Device Owner). Android has no "block mobile data for app X" switch for an MDM, so two
 * mechanisms do it:
 *  - `dpm` — `DevicePolicyManager.setMeteredDataDisabledPackages` (Android 9+): the system refuses metered networks
 *    (mobile data, and Wi-Fi marked as metered) to the listed apps, in the foreground too. No VPN, nothing to see.
 *  - `vpn` — the agent's local VPN ([MobileDataVpnService]) fixed as always-on with `setAlwaysOnVpnPackage` (which
 *    also authorises it, so nobody is asked) and protected with `DISALLOW_CONFIG_VPN`. Android 7–8, or when the
 *    policy forces it because a maker ignores the first one. Lockdown is NOT used: it would cut every app, on Wi-Fi too.
 *
 * Which apps are blocked comes from [MobileDataPlanner] (lists + limits). Consumption of the period is read from the
 * system's own counters (`NetworkStatsManager`, the same ones Settings shows), in one query for the whole period, so
 * nothing is accumulated — and nothing can be counted twice. Re-evaluated every minute while a policy is active, on
 * every check-in and on every network change; each limit crossing is one event per period.
 *
 * The agent itself and [protected] packages are never restricted. Only what this class set is undone.
 */
class MobileDataEnforcer(
    private val context: Context,
    private val handle: DpmHandle,
    private val events: EventSink,
    protected: Collection<String> = emptyList(),
) {
    private val prefs = context.getSharedPreferences("mdm_mobile_data", Context.MODE_PRIVATE)
    private val protectedPackages = protected.toSet() + context.packageName
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var last: MobileDataStatus? = null
    private var timer: ScheduledExecutorService? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appCache: Pair<Set<String>, List<DataApp>>? = null

    /** A problem that makes the whole section fail (reported in the config.apply result and retried by the server). */
    private class Hard(message: String) : Exception(message)

    /** `config.apply` entry point: [cfg] null = the configuration no longer manages mobile data. */
    fun apply(cfg: ConfigMobileData?): String {
        store(cfg)
        return try {
            evaluateOrThrow(System.currentTimeMillis())
            ensureRunning()
            ConfigOutcome.APPLIED
        } catch (e: Hard) {
            // Nothing half-applied stays behind: what was set is lifted and the section is retried from the server.
            runCatching { release() }
            store(null)
            last = null
            ConfigOutcome.failed(e.message ?: "mobile data")
        }
    }

    /** At process start: resume what the last applied configuration asked for. */
    fun start() {
        if (load() == null) return
        ensureRunning()
        timer?.execute { evaluate() }
    }

    /** For telemetry: the state as of the last evaluation (re-evaluated when older than [STALE_MS]); null = not managed. */
    fun status(now: Long = System.currentTimeMillis()): MobileDataStatus? {
        if (load() == null) return null
        val s = last
        return if (s == null || now - s.evaluatedAt > STALE_MS) evaluate(now) else s
    }

    /** Re-read consumption, decide and enforce. Never throws; a problem is reported in the status. */
    fun evaluate(now: Long = System.currentTimeMillis()): MobileDataStatus? = try {
        evaluateOrThrow(now)
    } catch (e: Hard) {
        last?.copy(problem = e.message, evaluatedAt = now).also { last = it }
    } catch (e: Exception) {
        Log.w(TAG, "evaluate", e)
        last
    }

    @Synchronized
    private fun evaluateOrThrow(now: Long): MobileDataStatus? {
        val cfg = load()
        if (cfg == null) {
            release()
            last = null
            return null
        }
        if (!handle.dpm.isDeviceOwnerApp(context.packageName)) throw Hard("el agente no es Device Owner en este equipo")
        val (start, end) = DataPeriod.bounds(now, cfg.period, cfg.cutDay)
        val perUid = mobileBytesByUid(start, now)
        val usage = byPackage(perUid)
        val deviceBytes = deviceMobileBytes(start, now) ?: perUid.values.sum()
        val plan = if (MobileDataPlanner.isIdle(cfg)) null
        else MobileDataPlanner.plan(cfg, installedApps(), usage, deviceBytes, protectedPackages)
        val blocked = plan?.blocked.orEmpty()
        val mechanism = if (plan == null) MECH_NONE else mechanismFor(cfg.mechanism)
        val soft = if (plan == null) { release(); null } else enforce(mechanism, blocked.keys)
        plan?.let { announce(it.crossings, start) }
        val limits = cfg.appLimits.associate { it.pkg to it.bytes }
        val shown = (limits.keys.filter { it in usage || isInstalled(it) } +
            usage.entries.sortedByDescending { it.value }.take(MAX_APPS).filter { it.value >= MIN_SHOWN_BYTES }.map { it.key }).distinct()
        val status = MobileDataStatus(
            mode = cfg.mode,
            mechanism = mechanism,
            vpnActive = if (mechanism == MECH_VPN) MobileDataVpn.blocking else null,
            blocked = blocked.entries.take(MAX_BLOCKED).map { MobileDataBlocked(it.key, it.value) },
            blockedCount = blocked.size,
            period = if (cfg.period == "daily") "daily" else "monthly",
            periodStart = start,
            periodEnd = end,
            deviceBytes = deviceBytes,
            deviceLimitBytes = cfg.deviceLimitBytes,
            warnPercent = cfg.warnPercent.coerceIn(1, 100),
            apps = shown.map { MobileDataAppUsage(it, label(it), usage[it] ?: 0, limits[it]) },
            problem = soft,
            evaluatedAt = now,
        )
        last = status
        return status
    }

    // --- enforcement ------------------------------------------------------------------------------------------

    private fun mechanismFor(asked: String): String = when {
        asked == MECH_VPN -> MECH_VPN
        Build.VERSION.SDK_INT >= 28 -> MECH_DPM
        else -> MECH_VPN // "auto" and "dpm" before Android 9: the system restriction does not exist there
    }

    /** @return a non-fatal problem to show (some package could not be restricted), or null. */
    private fun enforce(mechanism: String, blocked: Set<String>): String? =
        if (mechanism == MECH_DPM) {
            releaseVpn()
            setMeteredDisabled(blocked)
        } else {
            if (Build.VERSION.SDK_INT >= 28 && prefs.getBoolean(KEY_DPM_SET, false)) setMeteredDisabled(emptySet())
            holdVpn(blocked)
            null
        }

    private fun setMeteredDisabled(blocked: Set<String>): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val dpm = handle.dpm
        val current = runCatching { dpm.getMeteredDataDisabledPackages(handle.admin).toSet() }.getOrDefault(emptySet())
        if (current == blocked) return null
        val failed = try {
            dpm.setMeteredDataDisabledPackages(handle.admin, blocked.toList())
        } catch (e: SecurityException) {
            throw Hard("el sistema rechazó la restricción de datos: ${e.message}")
        }
        prefs.edit().putBoolean(KEY_DPM_SET, blocked.isNotEmpty()).apply()
        Log.i(TAG, "metered data disabled for ${blocked.size} app(s), ${failed.size} refused")
        return if (failed.isEmpty()) null else "el sistema no pudo restringir: " + failed.take(5).joinToString(", ")
    }

    private fun holdVpn(blocked: Set<String>) {
        if (Build.VERSION.SDK_INT < 24) throw Hard("Android 6 no permite fijar la VPN local sin intervención del usuario")
        val dpm = handle.dpm
        val own = context.packageName
        val alwaysOn = runCatching { dpm.getAlwaysOnVpnPackage(handle.admin) }.getOrNull()
        if (alwaysOn != null && alwaysOn != own) throw Hard("hay otra VPN siempre activa en el equipo ($alwaysOn)")
        if (blocked.isEmpty()) {
            releaseVpn()
            return
        }
        if (alwaysOn != own) {
            try {
                dpm.setAlwaysOnVpnPackage(handle.admin, own, false)
            } catch (e: Exception) {
                throw Hard("no se pudo fijar la VPN local: ${e.message ?: e.javaClass.simpleName}")
            }
            prefs.edit().putBoolean(KEY_VPN_SET, true).apply()
        }
        if (!prefs.getBoolean(KEY_VPN_RESTRICTION, false)) {
            // Only lifted later when it was not already there (another rule of the configuration may own it).
            val had = runCatching {
                (context.getSystemService(Context.USER_SERVICE) as UserManager).hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)
            }.getOrDefault(false)
            runCatching { dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_VPN) }
            prefs.edit().putBoolean(KEY_VPN_RESTRICTION, true).putBoolean(KEY_VPN_RESTRICTION_OURS, !had).apply()
        }
        MobileDataVpn.set(context, blocked, idlePackage())
    }

    private fun releaseVpn() {
        if (MobileDataVpn.blockedPackages(context).isNotEmpty() || MobileDataVpn.up) MobileDataVpn.set(context, emptySet(), null)
        if (Build.VERSION.SDK_INT < 24) return
        if (prefs.getBoolean(KEY_VPN_SET, false)) {
            runCatching {
                if (handle.dpm.getAlwaysOnVpnPackage(handle.admin) == context.packageName) {
                    handle.dpm.setAlwaysOnVpnPackage(handle.admin, null, false)
                }
            }
            prefs.edit().putBoolean(KEY_VPN_SET, false).apply()
        }
        if (prefs.getBoolean(KEY_VPN_RESTRICTION, false)) {
            if (prefs.getBoolean(KEY_VPN_RESTRICTION_OURS, false)) {
                runCatching { handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_VPN) }
            }
            prefs.edit().putBoolean(KEY_VPN_RESTRICTION, false).apply()
        }
    }

    /** Undo everything this class set (both mechanisms). */
    private fun release() {
        if (prefs.getBoolean(KEY_DPM_SET, false)) runCatching { setMeteredDisabled(emptySet()) }
        runCatching { releaseVpn() }
    }

    /** An installed package that cannot use the network: what the tunnel holds on Wi-Fi so it stays connected. */
    private fun idlePackage(): String? {
        val apps = installedApps().filter { !it.internet && it.pkg !in protectedPackages }
        return (IDLE_CANDIDATES.firstOrNull { c -> apps.any { it.pkg == c } } ?: apps.filter { it.system }.minByOrNull { it.pkg }?.pkg)
    }

    // --- consumption ------------------------------------------------------------------------------------------

    /** Mobile bytes (received + sent) per uid since [start], from the system's counters. */
    private fun mobileBytesByUid(start: Long, end: Long): Map<Int, Long> = runCatching {
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return emptyMap()
        val perUid = HashMap<Int, Long>()
        @Suppress("DEPRECATION")
        nsm.querySummary(ConnectivityManager.TYPE_MOBILE, null, start, end)?.use { stats ->
            val b = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                perUid[b.uid] = (perUid[b.uid] ?: 0) + b.rxBytes + b.txBytes
            }
        }
        perUid
    }.getOrDefault(emptyMap())

    /** The device's whole mobile consumption since [start] (includes tethering and removed apps); null if unreadable. */
    private fun deviceMobileBytes(start: Long, end: Long): Long? = runCatching {
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return null
        @Suppress("DEPRECATION")
        val b = nsm.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, start, end) ?: return null
        b.rxBytes + b.txBytes
    }.getOrNull()

    /** Apps that share a uid share its consumption: each of them is charged the uid's total. */
    private fun byPackage(perUid: Map<Int, Long>): Map<String, Long> {
        val pm = context.packageManager
        val out = HashMap<String, Long>()
        perUid.forEach { (uid, n) ->
            if (n <= 0) return@forEach
            val pkgs = when {
                uid == Process.SYSTEM_UID || uid < Process.FIRST_APPLICATION_UID -> arrayOf("android")
                else -> pm.getPackagesForUid(uid) ?: return@forEach
            }
            pkgs.forEach { out[it] = (out[it] ?: 0) + n }
        }
        return out
    }

    private fun installedApps(): List<DataApp> {
        val pm = context.packageManager
        val infos = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val names = infos.map { it.packageName }.toSet()
        appCache?.let { (key, apps) -> if (key == names) return apps }
        val apps = infos.map { ai ->
            DataApp(
                pkg = ai.packageName,
                internet = pm.checkPermission(Manifest.permission.INTERNET, ai.packageName) == PackageManager.PERMISSION_GRANTED,
                system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                launchable = runCatching { pm.getLaunchIntentForPackage(ai.packageName) != null }.getOrDefault(false),
            )
        }
        appCache = names to apps
        return apps
    }

    private fun isInstalled(pkg: String): Boolean = installedApps().any { it.pkg == pkg }

    private fun label(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    // --- limit events ------------------------------------------------------------------------------------------

    private fun announce(crossings: List<Crossing>, periodStart: Long) {
        val seen = prefs.getStringSet(KEY_LEDGER, emptySet()).orEmpty()
        val (fresh, ledger) = QuotaLedger.fresh(periodStart, crossings, seen)
        if (ledger != seen) prefs.edit().putStringSet(KEY_LEDGER, ledger).apply()
        if (fresh.isEmpty()) return
        // One event per kind and evaluation (the server keeps one alert per device, kind and instant).
        fun text(level: String) = fresh.filter { it.level == level }.joinToString("; ") { c ->
            if (c.key == Crossing.DEVICE) QuotaText.line(null, null, c.used, c.limit) else QuotaText.line(label(c.key), c.key, c.used, c.limit)
        }
        text(Crossing.WARN).takeIf { it.isNotEmpty() }?.let { events.record(EventType.DATA_WARNING, it) }
        text(Crossing.LIMIT).takeIf { it.isNotEmpty() }?.let { events.record(EventType.DATA_LIMIT, it) }
        runCatching { CheckInWorker.scheduleNow(context) } // the console learns about it now, not at the next heartbeat
    }

    // --- lifecycle ---------------------------------------------------------------------------------------------

    @Synchronized
    private fun ensureRunning() {
        if (timer == null) {
            timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mobile-data").apply { isDaemon = true } }.also {
                it.scheduleWithFixedDelay({ if (load() != null) evaluate() }, EVERY_MS, EVERY_MS, TimeUnit.MILLISECONDS)
            }
        }
        if (networkCallback == null && Build.VERSION.SDK_INT >= 24) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = changed()
                override fun onLost(network: Network) = changed()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
            }
            runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
        }
    }

    /** Wi-Fi <-> mobile: the tunnel switches what it captures at once (the system restriction needs nothing). */
    private fun changed() {
        if (MobileDataVpn.blockedPackages(context).isNotEmpty()) MobileDataVpn.sync(context)
    }

    private fun load(): ConfigMobileData? = prefs.getString(KEY_CFG, null)?.let {
        runCatching { json.decodeFromString(ConfigMobileData.serializer(), it) }.getOrNull()
    }

    private fun store(cfg: ConfigMobileData?) {
        prefs.edit().apply {
            if (cfg == null) remove(KEY_CFG) else putString(KEY_CFG, json.encodeToString(ConfigMobileData.serializer(), cfg))
        }.apply()
    }

    companion object {
        const val MECH_DPM = "dpm"
        const val MECH_VPN = "vpn"
        const val MECH_NONE = "none"
        private const val TAG = "MobileData"
        private const val EVERY_MS = 60_000L
        private const val STALE_MS = 30_000L
        private const val MAX_BLOCKED = 150
        private const val MAX_APPS = 25
        private const val MIN_SHOWN_BYTES = 10_000L
        private const val KEY_CFG = "cfg"
        private const val KEY_DPM_SET = "dpm_set"
        private const val KEY_VPN_SET = "vpn_set"
        private const val KEY_VPN_RESTRICTION = "vpn_restriction"
        private const val KEY_VPN_RESTRICTION_OURS = "vpn_restriction_ours"
        private const val KEY_LEDGER = "ledger"

        /** Present on every AOSP build and without the INTERNET permission. */
        private val IDLE_CANDIDATES = listOf("com.android.htmlviewer", "com.android.bookmarkprovider", "com.android.printspooler")
    }
}
