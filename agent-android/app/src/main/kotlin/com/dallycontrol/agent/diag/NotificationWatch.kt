package com.dallycontrol.agent.diag

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Sees the phone's notifications, for diagnosis only: which app put up the alarm, call or alert that is sounding, and
 * a way to press its buttons ("Detener", "Descartar") from the console when the kiosk keeps its screen from showing.
 * Text is kept only for alerting notifications (alarms, calls, system and emergency alerts); for any other app just its
 * package and category — never message contents.
 */
class DcNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationWatch.remember(NotificationWatch.summary(sbn))
    }

    companion object {
        @Volatile internal var instance: DcNotificationListener? = null
    }
}

/** One notification as diagnosis shows it. */
data class NotificationSummary(
    val key: String,
    val pkg: String,
    val postedAt: Long,
    val category: String?,
    val alerting: Boolean,
    val title: String?,
    val text: String?,
    val ongoing: Boolean,
    val insistent: Boolean,
    val fullScreen: Boolean,
    val actions: List<String>,
)

object NotificationWatch {
    private const val TAG = "NotificationWatch"
    private const val MAX_RECENT = 40

    /** Apps whose notifications are alarms or alerts by nature (their text is kept). */
    private val ALERT_APPS = setOf(
        "com.google.android.deskclock", "com.android.deskclock", "com.motorola.timeweatherwidget",
        "com.android.cellbroadcastreceiver", "com.google.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module", "com.google.android.gms", "com.android.systemui",
        "com.android.phone", "com.google.android.dialer", "com.android.dialer", "com.android.server.telecom",
    )
    private val ALERT_CATEGORIES = setOf(
        Notification.CATEGORY_ALARM, Notification.CATEGORY_CALL, Notification.CATEGORY_SYSTEM,
        Notification.CATEGORY_ERROR, Notification.CATEGORY_REMINDER, Notification.CATEGORY_EVENT,
        Notification.CATEGORY_STATUS, Notification.CATEGORY_SERVICE,
    )

    private val recent = ArrayDeque<NotificationSummary>()

    fun component(context: Context) = ComponentName(context, DcNotificationListener::class.java)

    /** Whether the agent sees notifications right now. */
    fun connected(): Boolean = DcNotificationListener.instance != null

    /**
     * Make sure the listener runs when access is granted (cable enrollment grants it with `cmd notification
     * allow_listener`; otherwise the person allows it once, from the console's "Pedir acceso a notificaciones"). Before
     * Android 11 the agent can also switch it on by itself with WRITE_SECURE_SETTINGS; from 11 on Android ignores that.
     */
    fun ensureEnabled(context: Context): Boolean {
        if (connected()) return true
        if (Build.VERSION.SDK_INT >= 27) {
            val granted = runCatching {
                context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
            }.getOrDefault(false)
            if (granted) runCatching { NotificationListenerService.requestRebind(component(context)) }
            if (granted || Build.VERSION.SDK_INT >= 30) return granted
        }
        val me = component(context).flattenToString()
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, "enabled_notification_listeners")?.takeIf { it.isNotBlank() && it != "null" }
        if (current?.split(':')?.contains(me) == true) {
            runCatching { NotificationListenerService.requestRebind(component(context)) }
            return true
        }
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            Settings.Secure.putString(resolver, "enabled_notification_listeners", listOfNotNull(current, me).joinToString(":"))
            NotificationListenerService.requestRebind(component(context))
            true
        }.onFailure { Log.w(TAG, "enable listener", it) }.getOrDefault(false)
    }

    fun summary(sbn: StatusBarNotification): NotificationSummary {
        val n = sbn.notification
        val alerting = sbn.packageName in ALERT_APPS || n.category in ALERT_CATEGORIES ||
            (n.flags and Notification.FLAG_INSISTENT) != 0 || n.fullScreenIntent != null
        val extras = n.extras
        return NotificationSummary(
            key = sbn.key,
            pkg = sbn.packageName,
            postedAt = sbn.postTime,
            category = n.category,
            alerting = alerting,
            title = if (alerting) extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(120) else null,
            text = if (alerting) extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(200) else null,
            ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            insistent = (n.flags and Notification.FLAG_INSISTENT) != 0,
            fullScreen = n.fullScreenIntent != null,
            actions = if (alerting) n.actions?.map { it.title?.toString().orEmpty().take(40) }.orEmpty() else emptyList(),
        )
    }

    @Synchronized
    fun remember(s: NotificationSummary) {
        recent.removeAll { it.key == s.key }
        recent.addFirst(s)
        while (recent.size > MAX_RECENT) recent.removeLast()
    }

    /** Notifications posted since [sinceMs] (newest first). */
    @Synchronized
    fun postedSince(sinceMs: Long): List<NotificationSummary> = recent.filter { it.postedAt >= sinceMs }

    /** What is in the shade now (empty when the listener is off). */
    fun active(): List<NotificationSummary> =
        runCatching { DcNotificationListener.instance?.activeNotifications?.map(::summary) }.getOrNull().orEmpty()
            .sortedByDescending { it.postedAt }

    private fun find(key: String): StatusBarNotification? =
        runCatching { DcNotificationListener.instance?.activeNotifications?.firstOrNull { it.key == key } }.getOrNull()

    /**
     * Act on a notification: `open` (its screen), `fullscreen` (its full-screen screen, e.g. a ringing alarm), `dismiss`,
     * or the index of one of its buttons. The app is allowed in the kiosk for a few minutes first, so its screen shows.
     * Returns null when done, else why not.
     */
    fun act(context: Context, key: String, action: String): String? {
        val sbn = find(key) ?: return "notification not found (already gone?)"
        val n = sbn.notification
        if (action == "dismiss") {
            DcNotificationListener.instance?.cancelNotification(key) ?: return "notification access is off"
            return null
        }
        val intent = when (action) {
            "open" -> n.contentIntent
            "fullscreen" -> n.fullScreenIntent ?: n.contentIntent
            else -> action.toIntOrNull()?.let { n.actions?.getOrNull(it)?.actionIntent }
        } ?: return "that notification has no such action"
        com.dallycontrol.agent.kiosk.TimedAllow.allow(context, sbn.packageName, 5 * 60_000L)
        return runCatching { intent.send(); null }.getOrElse { it.message ?: "could not send" }
    }
}
