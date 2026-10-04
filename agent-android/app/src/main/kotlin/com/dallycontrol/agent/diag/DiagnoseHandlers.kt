package com.dallycontrol.agent.diag

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import com.dallycontrol.core.action.RingController
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

/**
 * `device.diagnose` — a snapshot of what can make the phone ring or misbehave, as JSON in the result: sounds playing
 * now and recently (with their likely source), notifications in the shade (alerting ones with their buttons), the next
 * alarm and who set it, volumes and silent mode, the call state, the kiosk (lock and allowed apps), the apps that came
 * to the front in the last 30 minutes, battery and network.
 */
class DiagnoseHandler(
    private val context: Context,
    private val sounds: SoundWatch,
    private val handle: DpmHandle,
) : CommandHandler {
    override val type: String = DeviceAction.DIAGNOSE

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        NotificationWatch.ensureEnabled(context)
        CommandResults.done(command, snapshot().toString())
    }.getOrElse { CommandResults.failed(command, it.message ?: "diagnose failed") }

    private fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val o = JSONObject().put("at", now).put("android", Build.VERSION.RELEASE).put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        // Sound
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = JSONObject()
        for ((name, s) in listOf("alarma" to AudioManager.STREAM_ALARM, "timbre" to AudioManager.STREAM_RING,
            "notificaciones" to AudioManager.STREAM_NOTIFICATION, "multimedia" to AudioManager.STREAM_MUSIC, "llamada" to AudioManager.STREAM_VOICE_CALL)) {
            vol.put(name, "${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}")
        }
        o.put("sound", JSONObject()
            .put("playingNow", JSONArray(sounds.playingNow()))
            .put("ringerMode", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silencio"; AudioManager.RINGER_MODE_VIBRATE -> "vibrar"; else -> "normal" })
            .put("doNotDisturb", runCatching { (context.getSystemService(NotificationManager::class.java)).currentInterruptionFilter }.getOrNull())
            .put("volumes", vol)
            .put("recent", JSONArray(sounds.recent().take(15).map {
                JSONObject().put("start", it.startedAt).put("end", it.endedAt ?: JSONObject.NULL).put("kind", it.kind).put("source", it.source)
            })))
        // Calls
        @Suppress("DEPRECATION")
        val call = runCatching { (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState }.getOrDefault(-1)
        o.put("call", when (call) { TelephonyManager.CALL_STATE_RINGING -> "timbrando"; TelephonyManager.CALL_STATE_OFFHOOK -> "en llamada"; TelephonyManager.CALL_STATE_IDLE -> "sin llamada"; else -> "?" })
        // Alarm clock
        val next = runCatching { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock }.getOrNull()
        o.put("nextAlarm", next?.let { JSONObject().put("at", it.triggerTime).put("setBy", it.showIntent?.creatorPackage ?: JSONObject.NULL) } ?: JSONObject.NULL)
        // Notifications
        o.put("notificationAccess", NotificationWatch.connected())
        o.put("notifications", JSONArray(NotificationWatch.active().take(30).map { n ->
            JSONObject().put("key", n.key).put("app", n.pkg).put("at", n.postedAt).put("category", n.category ?: JSONObject.NULL)
                .put("alerting", n.alerting).put("title", n.title ?: JSONObject.NULL).put("text", n.text ?: JSONObject.NULL)
                .put("ongoing", n.ongoing).put("insistent", n.insistent).put("fullScreen", n.fullScreen).put("actions", JSONArray(n.actions))
        }))
        // Kiosk
        val atm = context.getSystemService(ActivityManager::class.java)
        o.put("kiosk", JSONObject()
            .put("locked", when (atm.lockTaskModeState) { ActivityManager.LOCK_TASK_MODE_LOCKED -> "bloqueado"; ActivityManager.LOCK_TASK_MODE_PINNED -> "fijado"; else -> "no" })
            .put("allowedApps", JSONArray(runCatching { handle.dpm.getLockTaskPackages(handle.admin).toList() }.getOrDefault(emptyList()))))
        // Apps that came to the front lately (needs usage access)
        o.put("foreground", JSONArray(foreground(now - 30 * 60_000L, now)))
        // Battery / network
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        o.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        return o
    }

    private fun foreground(from: Long, to: Long): List<JSONObject> = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(from, to)
        val out = ArrayList<JSONObject>()
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName != last) {
                out += JSONObject().put("at", e.timeStamp).put("app", e.packageName)
                last = e.packageName
            }
        }
        out.takeLast(40)
    }.getOrDefault(emptyList())
}

/** `device.notificationAction` — `{key, action}`: `open`, `fullscreen`, `dismiss` or a button index. */
class NotificationActionHandler(private val context: Context) : CommandHandler {
    override val type: String = DeviceAction.NOTIFICATION_ACTION

    @Serializable
    private data class Payload(val key: String, val action: String = "open")

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?: return CommandResults.failed(command, "invalid payload")
        val err = NotificationWatch.act(context, p.key, p.action)
        return if (err == null) CommandResults.done(command, "${p.action} sent") else CommandResults.failed(command, err)
    }
}

/** `device.silence` — `{minutes}` (default 10): every volume to zero, back afterwards. */
class SilenceHandler(private val context: Context, private val ring: RingController) : CommandHandler {
    override val type: String = DeviceAction.SILENCE

    @Serializable
    private data class Payload(val minutes: Int = 10)

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() } ?: Payload()
        ring.stop()
        return CommandResults.done(command, Silencer.silence(context, p.minutes))
    }
}

/**
 * `device.ringStop` — "Dejar de sonar": stops the console's locate tone and, whatever else is sounding (an alarm, a
 * ringtone, Google's find-my-device), silences the phone for 5 minutes.
 */
class RingStopHandler(private val context: Context, private val ring: RingController) : CommandHandler {
    override val type: String = DeviceAction.RING_STOP

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        ring.stop()
        return CommandResults.done(command, Silencer.silence(context, 5))
    }
}
