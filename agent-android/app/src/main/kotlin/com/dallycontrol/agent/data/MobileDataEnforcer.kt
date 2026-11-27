package com.dallycontrol.agent.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.dallycontrol.core.data.Crossing
import com.dallycontrol.core.data.DataPeriod
import com.dallycontrol.core.data.DataPlan
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
 * Mobile data per app (Device Owner): keeps the apps the policy names off mobile data, counts the period's
 * consumption against the policy's limits and blocks by itself when one is spent.
 *
 * Which apps are blocked comes from [MobileDataPlanner] (lists + limits), how from [MobileDataMechanism] (the system's
 * metered-data restriction, or the agent's local VPN) and the consumption from [MobileDataUsage]. Re-evaluated every
 * minute while a policy is active, on every check-in and on every network change; each limit crossing is one event
 * per period, sent to the console at once. It works without connection to the console.
 *
 * The agent itself and [protected] packages are never restricted.
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
    private val usage = MobileDataUsage(context)
    private val mechanism = MobileDataMechanism(context, handle, prefs)

    @Volatile private var last: MobileDataStatus? = null
    private var timer: ScheduledExecutorService? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** `config.apply` entry point: [cfg] null = the configuration no longer manages mobile data. */
    fun apply(cfg: ConfigMobileData?): String {
        store(cfg)
        return try {
            evaluateLocked(System.currentTimeMillis())
            ensureRunning()
            ConfigOutcome.APPLIED
        } catch (e: MobileDataProblem) {
            // Nothing half-applied stays behind: what was set is lifted and the section is retried from the server.
            Log.w(TAG, "mobile data not applied: ${e.message}")
            mechanism.release()
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

    /** For telemetry: the state as of the last evaluation (redone when older than [STALE_MS]); null = not managed. */
    fun status(now: Long = System.currentTimeMillis()): MobileDataStatus? {
        val s = last
        return when {
            load() == null -> null
            s == null || now - s.evaluatedAt > STALE_MS -> evaluate(now)
            else -> s
        }
    }

    /** Re-read consumption, decide and enforce. Never throws; a problem is reported in the status. */
    fun evaluate(now: Long = System.currentTimeMillis()): MobileDataStatus? =
        runCatching { evaluateLocked(now) }.getOrElse { e ->
            Log.w(TAG, "evaluate: ${e.message}")
            if (e is MobileDataProblem) last = last?.copy(problem = e.message, evaluatedAt = now)
            last
        }

    @Synchronized
    private fun evaluateLocked(now: Long): MobileDataStatus? {
        val cfg = load()
        if (cfg == null) {
            mechanism.release()
            last = null
            return null
        }
        if (!handle.dpm.isDeviceOwnerApp(context.packageName)) {
            throw MobileDataProblem("el agente no es Device Owner en este equipo")
        }
        val (start, end) = DataPeriod.bounds(now, cfg.period, cfg.cutDay)
        val perUid = usage.byUid(start, now)
        val byPackage = usage.byPackage(perUid)
        val deviceBytes = usage.device(start, now) ?: perUid.values.sum()
        val plan = if (MobileDataPlanner.isIdle(cfg)) DataPlan(emptyMap(), emptyList())
        else MobileDataPlanner.plan(cfg, usage.installedApps(), byPackage, deviceBytes, protectedPackages)
        val how = if (MobileDataPlanner.isIdle(cfg)) MobileDataMechanism.NONE else mechanism.resolve(cfg.mechanism)
        val problem = if (how == MobileDataMechanism.NONE) {
            mechanism.release()
            null
        } else {
            mechanism.enforce(how, plan.blocked.keys) { usage.idlePackage(protectedPackages) }
        }
        announce(plan.crossings, start)
        return statusOf(cfg, how, plan, byPackage).copy(
            periodStart = start,
            periodEnd = end,
            deviceBytes = deviceBytes,
            problem = problem,
            evaluatedAt = now,
        ).also { last = it }
    }

    private fun statusOf(
        cfg: ConfigMobileData,
        how: String,
        plan: DataPlan,
        byPackage: Map<String, Long>,
    ): MobileDataStatus {
        val limits = cfg.appLimits.associate { it.pkg to it.bytes }
        val installed = usage.installedApps().mapTo(HashSet()) { it.pkg }
        val top = byPackage.entries.filter { it.value >= MIN_SHOWN_BYTES }.sortedByDescending { it.value }
            .take(MAX_APPS)
        val shown = (limits.keys.filter { it in byPackage || it in installed } + top.map { it.key }).distinct()
        return MobileDataStatus(
            mode = cfg.mode,
            mechanism = how,
            vpnActive = if (how == MobileDataMechanism.VPN) MobileDataVpn.blocking else null,
            blocked = plan.blocked.entries.take(MAX_BLOCKED).map { MobileDataBlocked(it.key, it.value) },
            blockedCount = plan.blocked.size,
            period = if (cfg.period == "daily") "daily" else "monthly",
            deviceLimitBytes = cfg.deviceLimitBytes,
            warnPercent = cfg.warnPercent.coerceIn(1, PERCENT),
            apps = shown.map { MobileDataAppUsage(it, usage.label(it), byPackage[it] ?: 0, limits[it]) },
        )
    }

    /** One event per kind and evaluation (the server keeps one alert per device, kind and instant). */
    private fun announce(crossings: List<Crossing>, periodStart: Long) {
        val seen = prefs.getStringSet(KEY_LEDGER, emptySet()).orEmpty()
        val (fresh, ledger) = QuotaLedger.fresh(periodStart, crossings, seen)
        if (ledger != seen) prefs.edit().putStringSet(KEY_LEDGER, ledger).apply()
        if (fresh.isEmpty()) return
        fun text(level: String) = fresh.filter { it.level == level }.joinToString("; ") { c ->
            val device = c.key == Crossing.DEVICE
            QuotaText.line(if (device) null else usage.label(c.key), if (device) null else c.key, c.used, c.limit)
        }
        text(Crossing.WARN).takeIf { it.isNotEmpty() }?.let { events.record(EventType.DATA_WARNING, it) }
        text(Crossing.LIMIT).takeIf { it.isNotEmpty() }?.let { events.record(EventType.DATA_LIMIT, it) }
        // The console learns about it now, not at the next heartbeat.
        runCatching { CheckInWorker.scheduleNow(context) }
    }

    @Synchronized
    private fun ensureRunning() {
        if (timer == null) {
            val t = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "mobile-data").apply { isDaemon = true }
            }
            t.scheduleWithFixedDelay({ if (load() != null) evaluate() }, EVERY_MS, EVERY_MS, TimeUnit.MILLISECONDS)
            timer = t
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (networkCallback == null && cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Wi-Fi <-> mobile: the tunnel switches what it captures at once (the system restriction needs nothing).
            val cb = object : ConnectivityManager.NetworkCallback() {
                fun changed() {
                    if (MobileDataVpn.blockedPackages(context).isNotEmpty()) MobileDataVpn.sync(context)
                }
                override fun onAvailable(network: Network) = changed()
                override fun onLost(network: Network) = changed()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
            }
            runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { networkCallback = cb }
        }
    }

    private fun load(): ConfigMobileData? = prefs.getString(KEY_CFG, null)?.let {
        runCatching { json.decodeFromString(ConfigMobileData.serializer(), it) }.getOrNull()
    }

    private fun store(cfg: ConfigMobileData?) {
        val text = cfg?.let { json.encodeToString(ConfigMobileData.serializer(), it) }
        prefs.edit().apply { if (text == null) remove(KEY_CFG) else putString(KEY_CFG, text) }.apply()
    }

    private companion object {
        const val TAG = "MobileData"
        const val EVERY_MS = 60_000L
        const val STALE_MS = 30_000L
        const val MAX_BLOCKED = 150
        const val MAX_APPS = 25
        const val MIN_SHOWN_BYTES = 10_000L
        const val PERCENT = 100
        const val KEY_CFG = "cfg"
        const val KEY_LEDGER = "ledger"
    }
}
