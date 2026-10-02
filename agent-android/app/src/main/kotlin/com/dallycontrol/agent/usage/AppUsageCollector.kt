package com.dallycontrol.agent.usage

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Process
import com.dallycontrol.proto.AppUsageDay
import com.dallycontrol.proto.AppUsageEntry
import com.dallycontrol.proto.AppUsageReport
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Per-app usage for the console's reports: time on screen (usage events), data over Wi-Fi and mobile (network
 * stats; a Device Owner may read them) and a battery estimate. Android exposes no per-app battery figure to an
 * ordinary app, so the estimate is: between two samples taken while discharging, the percentage lost is shared
 * among the apps by the time each was on screen; the rest goes to [IDLE] (screen off / system).
 *
 * Screen time and the battery estimate need Android's "usage access" for the agent (adb-enroll grants it; otherwise
 * the person turns it on once). Data usage works without it.
 */
class AppUsageCollector(private val context: Context) {
    private val prefs = context.getSharedPreferences("mdm_app_usage", Context.MODE_PRIVATE)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun usageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Called on every telemetry collection: keeps the battery estimate going and, at most every [REPORT_EVERY_MS],
     * returns the report for today and yesterday (null in between).
     */
    @Synchronized
    fun collect(now: Long = System.currentTimeMillis()): AppUsageReport? {
        val access = usageAccess()
        runCatching { if (access) sampleBattery(now) }
        if (now - prefs.getLong(KEY_REPORTED, 0L) < REPORT_EVERY_MS) return null
        val report = runCatching {
            val today = startOfDay(now)
            AppUsageReport(access, listOf(day(today - DAY_MS, today, access), day(today, now, access)))
        }.getOrNull() ?: return null
        prefs.edit().putLong(KEY_REPORTED, now).apply()
        return report
    }

    private fun day(start: Long, end: Long, access: Boolean): AppUsageDay {
        val key = dayFormat.format(Date(start))
        val fg = if (access) foregroundMs(start, end) else emptyMap()
        val wifi = bytes(ConnectivityManager.TYPE_WIFI, start, end)
        val mobile = bytes(ConnectivityManager.TYPE_MOBILE, start, end)
        val battery = batteryOf(key)
        val pm = context.packageManager
        val apps = (fg.keys + wifi.keys + mobile.keys + battery.keys).map { pkg ->
            AppUsageEntry(
                packageName = pkg,
                label = when (pkg) {
                    IDLE -> null
                    else -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                },
                foregroundMs = fg[pkg] ?: 0,
                wifiBytes = wifi[pkg] ?: 0,
                mobileBytes = mobile[pkg] ?: 0,
                batteryPct = Math.round((battery[pkg] ?: 0.0) * 100) / 100.0,
            )
        }.filter { it.foregroundMs >= 1000 || it.wifiBytes + it.mobileBytes >= 10_000 || it.batteryPct >= 0.05 }
            .sortedByDescending { it.foregroundMs.toDouble() + (it.wifiBytes + it.mobileBytes) / 1000.0 + it.batteryPct * 60_000 }
            .take(MAX_APPS)
        return AppUsageDay(key, apps)
    }

    /** Time on screen per package in [start, end): from resume to pause of its activities. */
    private fun foregroundMs(start: Long, end: Long): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyMap()
        val events = usm.queryEvents(start, end) ?: return emptyMap()
        val out = HashMap<String, Long>()
        val resumedAt = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt.putIfAbsent(pkg, e.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> resumedAt.remove(pkg)?.let { from ->
                    out[pkg] = (out[pkg] ?: 0) + (e.timeStamp - from).coerceAtLeast(0)
                }
                // Screen off / device shutdown closes whatever was on screen.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    resumedAt.forEach { (p, from) -> out[p] = (out[p] ?: 0) + (e.timeStamp - from).coerceAtLeast(0) }
                    resumedAt.clear()
                }
            }
        }
        resumedAt.forEach { (p, from) -> out[p] = (out[p] ?: 0) + (end - from).coerceAtLeast(0) } // still on screen
        return out
    }

    /** Bytes (received + sent) per package over one transport. Shared uids are reported under their first package. */
    private fun bytes(transport: Int, start: Long, end: Long): Map<String, Long> = runCatching {
        val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return emptyMap()
        val perUid = HashMap<Int, Long>()
        @Suppress("DEPRECATION")
        nsm.querySummary(transport, null, start, end)?.use { stats ->
            val b = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                perUid[b.uid] = (perUid[b.uid] ?: 0) + b.rxBytes + b.txBytes
            }
        }
        val pm = context.packageManager
        val out = HashMap<String, Long>()
        perUid.forEach { (uid, n) ->
            val pkg = when {
                uid == Process.SYSTEM_UID || uid < Process.FIRST_APPLICATION_UID -> "android"
                else -> pm.getPackagesForUid(uid)?.firstOrNull() ?: return@forEach // removed app / tethering
            }
            out[pkg] = (out[pkg] ?: 0) + n
        }
        out
    }.getOrDefault(emptyMap())

    // --- battery estimate -------------------------------------------------------------------------------------

    private fun sampleBattery(now: Long) {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = level * 100.0 / scale
        val charging = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val lastAt = prefs.getLong(KEY_SAMPLE_AT, 0L)
        val lastPct = prefs.getFloat(KEY_SAMPLE_PCT, -1f).toDouble()
        val lastCharging = prefs.getBoolean(KEY_SAMPLE_CHARGING, true)
        if (lastAt > 0 && now - lastAt < SAMPLE_EVERY_MS) return
        if (lastAt > 0 && !charging && !lastCharging && lastPct > pct && now - lastAt <= MAX_GAP_MS) {
            val drop = lastPct - pct
            val span = (now - lastAt).coerceAtLeast(1)
            val fg = foregroundMs(lastAt, now)
            val used = fg.values.sum().coerceAtMost(span)
            val key = dayFormat.format(Date(now))
            val day = batteryOf(key).toMutableMap()
            fg.forEach { (pkg, ms) -> day[pkg] = (day[pkg] ?: 0.0) + drop * ms.coerceAtMost(span) / span }
            day[IDLE] = (day[IDLE] ?: 0.0) + drop * (span - used) / span
            saveBattery(key, day)
        }
        prefs.edit().putLong(KEY_SAMPLE_AT, now).putFloat(KEY_SAMPLE_PCT, pct.toFloat()).putBoolean(KEY_SAMPLE_CHARGING, charging).apply()
    }

    private fun batteryOf(day: String): Map<String, Double> = runCatching {
        val all = JSONObject(prefs.getString(KEY_BATTERY, "{}") ?: "{}")
        val d = all.optJSONObject(day) ?: return emptyMap()
        d.keys().asSequence().associateWith { d.optDouble(it, 0.0) }
    }.getOrDefault(emptyMap())

    private fun saveBattery(day: String, values: Map<String, Double>) {
        val all = runCatching { JSONObject(prefs.getString(KEY_BATTERY, "{}") ?: "{}") }.getOrDefault(JSONObject())
        all.put(day, JSONObject(values))
        // Keep the last few days only.
        val keep = all.keys().asSequence().toList().sorted().takeLast(KEEP_DAYS).toSet()
        all.keys().asSequence().toList().filterNot { it in keep }.forEach { all.remove(it) }
        prefs.edit().putString(KEY_BATTERY, all.toString()).apply()
    }

    private fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    companion object {
        /** Pseudo-package for battery lost with no app on screen (screen off, system). */
        const val IDLE = "android.idle"
        private const val DAY_MS = 24L * 3600_000L
        private const val REPORT_EVERY_MS = 10L * 60_000L
        private const val SAMPLE_EVERY_MS = 3L * 60_000L
        private const val MAX_GAP_MS = 2L * 3600_000L
        private const val MAX_APPS = 60
        private const val KEEP_DAYS = 4
        private const val KEY_REPORTED = "reported_at"
        private const val KEY_SAMPLE_AT = "sample_at"
        private const val KEY_SAMPLE_PCT = "sample_pct"
        private const val KEY_SAMPLE_CHARGING = "sample_charging"
        private const val KEY_BATTERY = "battery"
    }
}
