package com.dallycontrol.agent.diag

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import com.dallycontrol.core.action.RingController
import com.dallycontrol.core.sync.CheckInWorker
import com.dallycontrol.core.telemetry.EventLog
import com.dallycontrol.proto.EventType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches what the phone plays out loud. When an alarm or a ringtone keeps sounding it records an event — what kind of
 * sound, for how long, and its likely source (an incoming call, the Clock app, Google's "find my device", an emergency
 * alert, the console's locate tone…) — and checks in at once, so the console sees it while it happens. In the kiosk it
 * also opens the source's own screen ("Detener", "Descartar"), which the kiosk would otherwise keep hidden.
 */
@Singleton
class SoundWatch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val events: EventLog,
    private val ring: RingController,
) {
    data class SoundRecord(val startedAt: Long, val endedAt: Long?, val kind: String, val source: String)

    private val main = Handler(Looper.getMainLooper())
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var callback: AudioManager.AudioPlaybackCallback? = null

    /** The loud sound playing now (null when none). */
    private var current: Current? = null
    private val history = ArrayDeque<SoundRecord>()

    private class Current(val startedAt: Long, var kind: String, var source: String, var reported: Boolean = false, var relieved: Boolean = false)

    @Synchronized
    fun start() {
        if (callback != null || Build.VERSION.SDK_INT < 26) return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = changed(configs.orEmpty())
        }
        runCatching { audio.registerAudioPlaybackCallback(cb, main) }.onFailure { Log.w(TAG, "register", it) }
        callback = cb
        runCatching { NotificationWatch.ensureEnabled(context) }
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { audio.unregisterAudioPlaybackCallback(it) } }
        callback = null
        main.removeCallbacksAndMessages(null)
    }

    /** Recent loud sounds, newest first (for diagnosis). */
    @Synchronized
    fun recent(): List<SoundRecord> = (listOfNotNull(current?.let { SoundRecord(it.startedAt, null, it.kind, it.source) }) + history)

    /** What is playing now, by usage (e.g. "alarma", "timbre"), for diagnosis. */
    fun playingNow(): List<String> = if (Build.VERSION.SDK_INT < 26) emptyList()
    else runCatching { audio.activePlaybackConfigurations.map { usageName(it.audioAttributes.usage) }.distinct() }.getOrDefault(emptyList())

    private fun changed(configs: List<AudioPlaybackConfiguration>) {
        val loud = configs.map { it.audioAttributes.usage }.filter { it in LOUD_USAGES }
        synchronized(this) {
            val now = System.currentTimeMillis()
            val cur = current
            if (loud.isNotEmpty() && cur == null) {
                val kind = loud.map(::usageName).distinct().joinToString(" + ")
                current = Current(now, kind, source(now))
                main.postDelayed(::stillSounding, REPORT_AFTER_MS)
            } else if (loud.isEmpty() && cur != null) {
                current = null
                main.removeCallbacksAndMessages(null)
                val record = SoundRecord(cur.startedAt, now, cur.kind, cur.source)
                history.addFirst(record)
                while (history.size > 30) history.removeLast()
                val secs = (now - cur.startedAt) / 1000
                if (secs >= MIN_LOG_SECONDS) {
                    runCatching { events.record(EventType.SOUND_ENDED, "${cur.kind} sonó ${duration(secs)} · fuente: ${cur.source}") }
                    if (cur.reported) runCatching { CheckInWorker.scheduleNow(context) }
                }
            }
        }
    }

    /** The sound is still on after [REPORT_AFTER_MS]: tell the console now, and in the kiosk show its stop screen. */
    private fun stillSounding() {
        val cur = synchronized(this) { current } ?: return
        cur.source = source(cur.startedAt) // a notification may have arrived since
        if (!cur.reported) {
            cur.reported = true
            runCatching { events.record(EventType.SOUND_ALERT, "${cur.kind} sonando · fuente: ${cur.source}") }
            runCatching { CheckInWorker.scheduleNow(context) }
        }
        if (!cur.relieved && inKiosk()) {
            cur.relieved = true
            relieve(cur.startedAt)?.let { opened ->
                runCatching { events.record(EventType.SOUND_ALERT, "quiosco: se abrió la pantalla de $opened para poder detenerlo") }
            }
        }
    }

    private fun inKiosk(): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }.getOrDefault(false)

    /**
     * An alarm's or alert's own screen (with its stop button) is blocked by the kiosk: allow that app for a few minutes
     * and open the screen of its newest alerting notification. Returns the app opened, or null.
     */
    private fun relieve(since: Long): String? {
        val n = (NotificationWatch.active() + NotificationWatch.postedSince(since - 60_000))
            .filter { it.alerting && (it.fullScreen || it.insistent || it.category == android.app.Notification.CATEGORY_ALARM) }
            .filter { it.pkg != context.packageName }
            .maxByOrNull { it.postedAt } ?: return null
        val err = NotificationWatch.act(context, n.key, "fullscreen")
        return if (err == null) friendly(n.pkg) else null
    }

    /** The likely source of a sound that started at [startedAt]. */
    private fun source(startedAt: Long): String {
        if (ring.isRinging()) return "la consola (Hacer sonar)"
        val call = runCatching {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)
        if (call == TelephonyManager.CALL_STATE_RINGING) return "llamada entrante"
        val notif = (NotificationWatch.postedSince(startedAt - 30_000) + NotificationWatch.active())
            .filter { it.alerting && it.pkg != context.packageName }
            .maxByOrNull { it.postedAt }
        if (notif != null) return friendly(notif.pkg) + (notif.title?.let { " «$it»" } ?: "")
        val alarm = runCatching { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock }.getOrNull()
        if (alarm != null && kotlin.math.abs(alarm.triggerTime - startedAt) < 3 * 60_000) {
            return "alarma del reloj (${alarm.showIntent?.creatorPackage?.let(::friendly) ?: "Reloj"})"
        }
        return if (NotificationWatch.connected()) "desconocida (sin notificación)" else "desconocida (sin acceso a notificaciones)"
    }

    companion object {
        private const val TAG = "SoundWatch"
        private const val REPORT_AFTER_MS = 12_000L
        private const val MIN_LOG_SECONDS = 5L

        private val LOUD_USAGES = setOf(
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
        )

        fun usageName(usage: Int): String = when (usage) {
            AudioAttributes.USAGE_ALARM -> "alarma"
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "timbre de llamada"
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST -> "solicitud de llamada"
            AudioAttributes.USAGE_NOTIFICATION -> "notificación"
            AudioAttributes.USAGE_MEDIA -> "multimedia"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "llamada en curso"
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "sonido del sistema"
            AudioAttributes.USAGE_ASSISTANT -> "asistente"
            else -> "sonido ($usage)"
        }

        private val NAMES = mapOf(
            "com.google.android.gms" to "Servicios de Google (Encontrar mi dispositivo)",
            "com.google.android.deskclock" to "Reloj",
            "com.android.deskclock" to "Reloj",
            "com.android.cellbroadcastreceiver" to "Alerta de emergencia",
            "com.google.android.cellbroadcastreceiver" to "Alerta de emergencia",
            "com.android.cellbroadcastreceiver.module" to "Alerta de emergencia",
            "com.google.android.dialer" to "Teléfono",
            "com.android.dialer" to "Teléfono",
            "com.whatsapp" to "WhatsApp",
        )

        fun friendly(pkg: String): String = NAMES[pkg]?.let { "$it ($pkg)" } ?: pkg

        private fun duration(secs: Long) = if (secs < 90) "$secs s" else "${secs / 60} min ${secs % 60} s"
    }
}

/**
 * Silence the phone for a while (alarm, ringtone, notifications and media at zero), then put the volumes back. What
 * "Dejar de sonar" does from the console, whatever is sounding.
 */
object Silencer {
    private val STREAMS = intArrayOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC)
    private val main = Handler(Looper.getMainLooper())
    private var saved: Map<Int, Int>? = null
    private val restore = Runnable { restore() }

    @Synchronized
    fun silence(context: Context, minutes: Int): String {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (saved == null) saved = STREAMS.associateWith { runCatching { am.getStreamVolume(it) }.getOrDefault(-1) }
        appCtx = context.applicationContext
        val failed = STREAMS.filter { s -> runCatching { am.setStreamVolume(s, 0, 0) }.isFailure }
        main.removeCallbacks(restore)
        main.postDelayed(restore, minutes.coerceIn(1, 120) * 60_000L)
        return if (failed.isEmpty()) "silenciado ${minutes.coerceIn(1, 120)} min" else "silenciado (salvo ${failed.size} volumen(es) que Android no dejó cambiar)"
    }

    private var appCtx: Context? = null

    @Synchronized
    fun restore() {
        val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        saved?.forEach { (s, v) -> if (v >= 0) runCatching { am.setStreamVolume(s, v, 0) } }
        saved = null
    }
}
