package com.dallycontrol.agent.announce

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.dallycontrol.agent.R
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.core.sync.CheckInWorker
import com.dallycontrol.core.telemetry.EventLog
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.ProtocolJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** One announcement as the app keeps it. [localMedia] is the downloaded image/video (null until it is there). */
@Serializable
data class Announcement(
    val id: Int,
    val title: String,
    val body: String? = null,
    val mediaUrl: String? = null,
    val mediaType: String? = null,
    val mandatory: Boolean = false,
    val createdAt: Long = 0,
    val expiresAt: Long? = null,
    val localMedia: String? = null,
    val seen: Boolean = false,
    val acked: Boolean = false,
)

/**
 * The announcements inbox on the phone (newest first) and what the server hears back: received, seen, confirmed
 * (events with the announcement id; the next check-in carries them).
 */
object Announcements {
    private const val PREFS = "mdm_announcements"
    private const val KEY = "items"
    private const val CHANNEL = "mdm_announcements"
    private const val MAX_MEDIA_BYTES = 150L * 1024 * 1024
    private val list = ListSerializer(Announcement.serializer())

    @Synchronized
    fun all(context: Context): List<Announcement> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val now = System.currentTimeMillis()
        return runCatching { ProtocolJson.json.decodeFromString(list, raw) }.getOrDefault(emptyList())
            .filter { it.expiresAt == null || it.expiresAt > now }
            .sortedByDescending { it.createdAt }
    }

    fun get(context: Context, id: Int): Announcement? = all(context).firstOrNull { it.id == id }

    /** Mandatory announcements not confirmed yet, oldest first (shown one after another). */
    fun pendingMandatory(context: Context): List<Announcement> = all(context).filter { it.mandatory && !it.acked }.sortedBy { it.createdAt }

    fun unseen(context: Context): Int = all(context).count { !it.seen }

    @Synchronized
    private fun save(context: Context, items: List<Announcement>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, ProtocolJson.json.encodeToString(list, items.take(100))).apply()
    }

    @Synchronized
    fun put(context: Context, a: Announcement) {
        val old = all(context).firstOrNull { it.id == a.id }
        // Seen / confirmed never go back to false (a re-delivery of the same announcement keeps them).
        val merged = if (old == null) a else a.copy(seen = a.seen || old.seen, acked = a.acked || old.acked, localMedia = a.localMedia ?: old.localMedia)
        save(context, listOf(merged) + all(context).filter { it.id != a.id })
    }

    @Synchronized
    fun remove(context: Context, id: Int) {
        all(context).firstOrNull { it.id == id }?.localMedia?.let { runCatching { File(it).delete() } }
        save(context, all(context).filter { it.id != id })
        notifications(context).cancel(notificationId(id))
    }

    fun markSeen(context: Context, id: Int) {
        val a = get(context, id) ?: return
        if (a.seen) return
        put(context, a.copy(seen = true))
        report(context, EventType.ANNOUNCEMENT_SEEN, id)
    }

    fun markAcked(context: Context, id: Int) {
        val a = get(context, id) ?: return
        if (!a.acked) {
            put(context, a.copy(seen = true, acked = true))
            report(context, EventType.ANNOUNCEMENT_ACK, id)
        }
        notifications(context).cancel(notificationId(id))
    }

    fun report(context: Context, type: String, id: Int) {
        runCatching { EventLog(context).record(type, id.toString()) }
        runCatching { CheckInWorker.scheduleNow(context) }
    }

    /** Show the next mandatory announcement full screen (a Device Owner may start activities from the background). */
    fun showNextMandatory(context: Context) {
        val next = pendingMandatory(context).firstOrNull() ?: return
        runCatching { context.startActivity(AnnouncementActivity.intent(context, next.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** An optional announcement: a notification that opens it (it also stays in the inbox). */
    fun notify(context: Context, a: Announcement) {
        val nm = notifications(context)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ann_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(
            context, a.id,
            AnnouncementActivity.intent(context, a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(a.title)
            .setContentText(a.body?.take(120) ?: context.getString(R.string.ann_new))
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.body ?: context.getString(R.string.ann_new)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(notificationId(a.id), n) }
    }

    /** Download the image/video next to the app (so it shows offline and a video plays smoothly). */
    fun download(context: Context, a: Announcement): String? {
        val url = a.mediaUrl ?: return null
        val dir = File(context.filesDir, "announcements").apply { mkdirs() }
        val ext = url.substringAfterLast('.', "").substringBefore('?').take(5).lowercase().ifEmpty { if (a.mediaType == "video") "mp4" else "jpg" }
        val out = File(dir, "${a.id}.$ext")
        if (out.exists() && out.length() > 0) return out.absolutePath
        val tmp = File(dir, "${a.id}.part")
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode !in 200..299) return null
            if (conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L > MAX_MEDIA_BYTES) return null
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        return if (tmp.renameTo(out)) out.absolutePath else null
    }

    private fun notifications(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun notificationId(id: Int) = 20_000 + id
}

/**
 * `device.announce` — payload `{id, title, body?, mediaUrl?, mediaType?, mandatory, createdAt?, expiresAt?}`, or
 * `{id, withdraw: true}` to take one back. Mandatory ones open full screen; optional ones notify.
 */
class AnnounceHandler(private val context: Context) : CommandHandler {
    override val type: String = DeviceAction.ANNOUNCE

    @Serializable
    private data class Payload(
        val id: Int,
        val title: String = "",
        val body: String? = null,
        val mediaUrl: String? = null,
        val mediaType: String? = null,
        val mandatory: Boolean = false,
        val createdAt: Long? = null,
        val expiresAt: Long? = null,
        val withdraw: Boolean = false,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?: return CommandResults.failed(command, "invalid payload")
        if (p.withdraw) {
            Announcements.remove(context, p.id)
            return CommandResults.done(command, "withdrawn")
        }
        var a = Announcement(
            id = p.id, title = p.title.take(200), body = p.body?.take(5000), mediaUrl = p.mediaUrl,
            mediaType = p.mediaType, mandatory = p.mandatory, createdAt = p.createdAt ?: System.currentTimeMillis(),
            expiresAt = p.expiresAt,
        )
        Announcements.put(context, a)
        Announcements.report(context, EventType.ANNOUNCEMENT_RECEIVED, p.id)
        if (a.mediaUrl != null) {
            val local = withContext(Dispatchers.IO) { runCatching { Announcements.download(context, a) }.getOrNull() }
            if (local != null) {
                a = a.copy(localMedia = local)
                Announcements.put(context, a)
            }
        }
        if (a.mandatory) Announcements.showNextMandatory(context) else Announcements.notify(context, a)
        return CommandResults.done(command, "shown")
    }
}
