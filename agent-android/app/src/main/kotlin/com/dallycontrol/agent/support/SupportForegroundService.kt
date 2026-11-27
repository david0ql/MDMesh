package com.dallycontrol.agent.support

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dallycontrol.agent.R

/**
 * Foreground service of a live support session (ADR 0012). It is what lets Android hand the agent the microphone
 * (a voice call) or the screen capture (Android 10) while no activity of the agent is on screen, and it carries
 * the always-visible notification of the session: who is connected, with "Colgar" during a call.
 */
class SupportForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount") // the two control actions return before the foreground start
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HANG_UP -> {
                SupportGraph.manager(this).hangUp(byUser = true)
                return START_NOT_STICKY
            }
        }
        val microphone = intent?.getBooleanExtra(EXTRA_MICROPHONE, false) == true
        val projection = intent?.getBooleanExtra(EXTRA_PROJECTION, false) == true
        val notification = notification(this, microphone)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = 0
                if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                if (projection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                if (type == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, type)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        }.onFailure {
            Log.w(TAG, "could not enter the foreground (microphone=$microphone projection=$projection)", it)
            lastError = it.message
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        foreground = false
        @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        private const val TAG = "SupportService"
        private const val CHANNEL = "dc_support"
        private const val CHANNEL_CHAT = "dc_support_chat"
        const val NOTIFICATION_ID = 7_310
        const val CHAT_NOTIFICATION_ID = 7_311
        private const val ACTION_STOP = "com.dallycontrol.agent.support.STOP"
        const val ACTION_HANG_UP = "com.dallycontrol.agent.support.HANG_UP"
        private const val EXTRA_MICROPHONE = "microphone"
        private const val EXTRA_PROJECTION = "projection"

        @Volatile var foreground = false
            private set
        @Volatile var lastError: String? = null
            private set

        fun start(context: Context, microphone: Boolean = false, projection: Boolean = false) {
            lastError = null
            val intent = Intent(context, SupportForegroundService::class.java)
                .putExtra(EXTRA_MICROPHONE, microphone).putExtra(EXTRA_PROJECTION, projection)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    Log.w(TAG, "could not start the support service", it)
                    lastError = it.message
                }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SupportForegroundService::class.java)) }
            foreground = false
        }

        fun channels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                val name = context.getString(R.string.support_channel)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW))
            }
            if (nm.getNotificationChannel(CHANNEL_CHAT) == null) {
                val name = context.getString(R.string.support_channel_chat)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_CHAT, name, NotificationManager.IMPORTANCE_HIGH))
            }
        }

        private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, SupportActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun notification(context: Context, inCall: Boolean): Notification {
            channels(context)
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
                .setContentTitle(context.getString(R.string.support_notice_title))
                .setContentText(
                    context.getString(if (inCall) R.string.support_notice_call else R.string.support_notice_text),
                )
                .setOngoing(true)
                .setContentIntent(open(context))
            if (inCall) {
                val hangUp = PendingIntent.getService(
                    context, 1, Intent(context, SupportForegroundService::class.java).setAction(ACTION_HANG_UP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(0, context.getString(R.string.support_hang_up), hangUp)
            }
            return b.build()
        }

        /** A chat line from support, as a heads-up notification that opens the conversation. */
        fun chatNotification(context: Context, text: String) {
            channels(context)
            val n = NotificationCompat.Builder(context, CHANNEL_CHAT)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(context.getString(R.string.support_chat_from))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open(context))
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(CHAT_NOTIFICATION_ID, n) }
        }

        fun clearChatNotification(context: Context) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(CHAT_NOTIFICATION_ID) }
        }
    }
}
