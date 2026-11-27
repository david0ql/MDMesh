package com.dallycontrol.agent.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.proto.EventType

/**
 * Derives **user activity** events from the apps the user brings to the foreground, and records them on the
 * device event timeline via [EventSink]. This is the only "what is the user doing" signal a Device Owner can
 * read without privileged/root access: Android's UsageStats tells us which app is on screen and when (an
 * `ACTIVITY_RESUMED` transition), nothing finer. So we honestly emit:
 *
 *  - [EventType.APP_OPENED]    — the user opened/switched to an app (detail = its label, package in parens).
 *  - [EventType.CAMERA_OPENED] — the foregrounded app is one of the device's camera apps.
 *
 * What this CANNOT see (and we never pretend to): taps, keystrokes, in-app screens/navigation, content, or the
 * camera being used in-process by an app that has no camera activity of its own. Needs the same "usage access"
 * as [AppUsageCollector] (adb-enroll grants it); without it, [collect] is a no-op.
 *
 * Called once per telemetry cycle. It reads the foreground transitions since the previous call, collapses runs
 * of the same package (a flicker back and forth isn't two openings), drops its own/launcher/system noise, and
 * records the rest. A long offline gap is clamped so one catch-up can't flood the timeline.
 */
class AppActivityCollector(
    private val context: Context,
    private val events: EventSink,
) {
    private val prefs = context.getSharedPreferences("mdm_app_activity", Context.MODE_PRIVATE)

    private fun usageAccess(): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    @Synchronized
    fun collect(now: Long = System.currentTimeMillis()) {
        if (!usageAccess()) return
        runCatching { scan(now) }
    }

    private fun scan(now: Long) {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        // First run (or a huge gap): look back only a little so we don't dump a long backlog at once.
        val last = prefs.getLong(KEY_LAST_TS, 0L)
        val from = if (last <= 0L || now - last > MAX_LOOKBACK_MS) now - INITIAL_WINDOW_MS else last
        val lastPkg = prefs.getString(KEY_LAST_PKG, null)
        val cameras = cameraPackages()
        val self = context.packageName

        val e = UsageEvents.Event()
        val events = usm.queryEvents(from, now) ?: return
        // Collect the opened packages in order, collapsing consecutive repeats.
        val opened = ArrayList<Pair<String, Long>>()
        var prev = lastPkg
        var newestTs = last
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            val pkg = e.packageName ?: continue
            if (e.timeStamp <= last) continue // already processed in a prior cycle
            newestTs = maxOf(newestTs, e.timeStamp)
            if (pkg == prev) continue
            prev = pkg
            if (pkg == self || pkg in SKIP) continue
            opened.add(pkg to e.timeStamp)
        }

        // Keep only the most recent few if we somehow accumulated a lot.
        val toRecord = if (opened.size > MAX_PER_CYCLE) opened.takeLast(MAX_PER_CYCLE) else opened
        for ((pkg, _) in toRecord) {
            val type = if (pkg in cameras) EventType.CAMERA_OPENED else EventType.APP_OPENED
            runCatching { this.events.record(type, describe(pkg)) }
        }

        prefs.edit()
            .putLong(KEY_LAST_TS, maxOf(newestTs, from))
            .putString(KEY_LAST_PKG, prev)
            .apply()
    }

    /** "Label (package)" — or just the label when it equals/contains nothing useful beyond the package. */
    private fun describe(pkg: String): String {
        val pm = context.packageManager
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        return when {
            label == null || label == pkg -> pkg
            else -> "$label ($pkg)"
        }
    }

    /** The device's camera apps: whatever resolves the standard image/video-capture intents, plus obvious names. */
    private fun cameraPackages(): Set<String> = runCatching {
        val pm = context.packageManager
        val out = HashSet<String>()
        for (action in arrayOf(MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_VIDEO_CAPTURE, "android.media.action.STILL_IMAGE_CAMERA")) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(action), 0).forEach { it.activityInfo?.packageName?.let(out::add) }
        }
        out
    }.getOrDefault(emptySet())

    companion object {
        private const val KEY_LAST_TS = "last_ts"
        private const val KEY_LAST_PKG = "last_pkg"
        private const val INITIAL_WINDOW_MS = 15L * 60_000L
        private const val MAX_LOOKBACK_MS = 60L * 60_000L
        private const val MAX_PER_CYCLE = 40
        /** Launcher-shell / system packages whose foregrounding isn't a user "opening an app". */
        private val SKIP = setOf("com.android.systemui", "android", "com.google.android.apps.nexuslauncher", "com.android.launcher3")
    }
}
