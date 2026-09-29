package com.dallycontrol.agent.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dallycontrol.agent.R

/**
 * A quiet notification that opens the kiosk quick settings — the way in when a single app is pinned (no kiosk home
 * to put a tile on). It shows only when the kiosk allows the notification shade (lock-task notifications feature).
 */
object QuickSettingsNotice {
    private const val CHANNEL = "mdm_kiosk_qs"
    private const val ID = 1002

    fun update(context: Context, enabled: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!enabled) {
            nm.cancel(ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.qs_title), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, QuickSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.qs_title))
            .setContentText(context.getString(R.string.qs_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { nm.notify(ID, n) }
    }
}
