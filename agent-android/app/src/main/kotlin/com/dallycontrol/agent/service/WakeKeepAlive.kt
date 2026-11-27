package com.dallycontrol.agent.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Doze-proof reconcile heartbeat. WorkManager's periodic floor gets deferred in deep Doze, and a
 * parked device's WebSocket can be suspended — so we also schedule an [AlarmManager] alarm with
 * `setAndAllowWhileIdle`, which fires during Doze maintenance windows (throttled to ~once / 9 min)
 * WITHOUT needing the exact-alarm permission. Each firing triggers a check-in (see
 * [KeepAliveReceiver]) and reschedules the next one.
 */
object WakeKeepAlive {

    private const val REQUEST_CODE = 0x57414B45 // "WAKE"
    // 5 min, not 10: the console counts a device as connected for 20 min after its last report, and with a
    // 10-minute beat a single deferred alarm already crossed that line. Android stretches this on its own in deep
    // Doze (about 9 min before Android 12, a per-hour quota after), so asking for 5 costs little there.
    private val INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

    /** Schedule the next heartbeat. Idempotent (FLAG_UPDATE_CURRENT replaces any pending alarm). */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        val pi = pendingIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java)
        // Alarm broadcast carries no result extras, so IMMUTABLE is correct here.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
