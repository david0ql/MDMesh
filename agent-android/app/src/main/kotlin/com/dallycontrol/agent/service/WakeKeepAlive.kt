package com.dallycontrol.agent.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dallycontrol.agent.MainActivity
import com.dallycontrol.agent.wake.WakeChannel
import com.dallycontrol.agent.wake.WakeStates
import com.dallycontrol.core.wake.WakeSchedule

/**
 * Doze-proof backup check-in. WorkManager's periodic floor gets deferred in deep Doze, the socket can be suspended
 * and the push channel does not exist on every phone — so an [AlarmManager] alarm also fires a check-in (see
 * [KeepAliveReceiver]) and reschedules itself.
 *
 * **Pace.** [WakeSchedule]: the policy's base pace (5 min by default) and a faster one for a while after activity
 * on the phone.
 *
 * **Kind of alarm**, best first:
 *  1. `setAlarmClock` when the policy asks for it: the only alarm Android never defers — it even leaves Doze
 *     shortly before — at the price of the alarm icon in the status bar.
 *  2. `setExactAndAllowWhileIdle` when exact alarms are allowed (always before Android 12; from Android 13 through
 *     `USE_EXACT_ALARM`, which this sideloaded management agent declares; or when battery-exempt). Fires on time
 *     outside Doze; inside deep Doze Android still spaces these out (about one per 9 min per app).
 *  3. `setAndAllowWhileIdle` otherwise: same Doze spacing, and outside Doze Android may batch it a little.
 */
object WakeKeepAlive {

    private const val TAG = "WakeKeepAlive"
    private const val REQUEST_CODE = 0x57414B45 // "WAKE"
    private const val SHOW_REQUEST_CODE = 0x57414B46

    /** Schedule the next beat from now. Idempotent (FLAG_UPDATE_CURRENT replaces any pending alarm). */
    fun schedule(context: Context) {
        val state = WakeStates.get(context)
        val plan = runCatching { state.plan() }
            .getOrDefault(WakeSchedule.Plan(WakeSchedule.DEFAULT_POLL_MINUTES * WakeSchedule.MINUTE_MS, fast = false))
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + plan.delayMs
        val pi = pendingIntent(context)
        val exact = WakeChannel.exactAlarmsAllowed(context)
        val alarmClock = exact && runCatching { state.config?.alarmClock == true }.getOrDefault(false)
        runCatching {
            when {
                alarmClock -> am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent(context)), pi)
                exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                else -> inexact(am, triggerAt, pi)
            }
        }.onFailure {
            // The exact-alarm permission was withdrawn between the check and the call: keep the heartbeat anyway.
            Log.w(TAG, "exact alarm refused, falling back", it)
            runCatching { inexact(am, triggerAt, pi) }
        }
    }

    private fun inexact(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java)
        // Alarm broadcast carries no result extras, so IMMUTABLE is correct here.
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags())
    }

    /** What tapping the alarm icon opens (only with `setAlarmClock`): the agent's status screen. */
    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, SHOW_REQUEST_CODE, Intent(context, MainActivity::class.java), flags())

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
