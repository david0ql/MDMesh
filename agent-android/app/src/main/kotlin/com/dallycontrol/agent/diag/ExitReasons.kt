package com.dallycontrol.agent.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.dallycontrol.core.telemetry.EventLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * Why the agent stopped last time, as Android recorded it (Android 11+): "no responde" (ANR, with the stuck thread's
 * top frames), a crash, killed by the system for memory or by the maker's battery manager… Reported once per exit as an
 * `agentExit` event, so a freeze seen on a phone can be traced from the console.
 */
object ExitReasons {
    private const val PREFS = "mdm_exit_reasons"
    private const val KEY_LAST = "last_reported"
    const val EVENT = "agentExit"

    fun reportNew(context: Context, events: EventLog) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST, 0L)
        val exits = list(context, 8).filter { it.timestamp > last && worth(it.reason) }
        for (e in exits.sortedBy { it.timestamp }) {
            runCatching { events.record(EVENT, describe(e, withTrace = true).take(3_500)) }
        }
        list(context, 1).firstOrNull()?.let { prefs.edit().putLong(KEY_LAST, maxOf(last, it.timestamp)).apply() }
    }

    /** The last exits, for the diagnosis snapshot. */
    fun recent(context: Context): JSONArray {
        if (Build.VERSION.SDK_INT < 30) return JSONArray()
        return JSONArray(list(context, 6).map { e ->
            JSONObject().put("at", e.timestamp).put("reason", reasonName(e.reason)).put("detail", describe(e, withTrace = e.reason == ApplicationExitInfo.REASON_ANR).take(2_000))
        })
    }

    private fun list(context: Context, max: Int): List<ApplicationExitInfo> = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getHistoricalProcessExitReasons(context.packageName, 0, max)
    }.getOrDefault(emptyList())

    /** Normal ends (the user or an update closing it) are not reported. */
    private fun worth(reason: Int) = reason in setOf(
        ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER,
    )

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "no responde (ANR)"
        ApplicationExitInfo.REASON_CRASH -> "se cerró por un error"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "se cerró por un error nativo"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "cerrado por falta de memoria"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "cerrado por usar demasiados recursos"
        ApplicationExitInfo.REASON_SIGNALED -> "cerrado por el sistema (señal)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "cerrado por el usuario"
        ApplicationExitInfo.REASON_USER_STOPPED -> "detenido por el usuario"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "reiniciado por un cambio de permisos"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "cerrado porque cerró otra app"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "falló al iniciar"
        ApplicationExitInfo.REASON_EXIT_SELF -> "se cerró a sí mismo"
        14 -> "congelado por el sistema"
        15 -> "reiniciado por un cambio de la app"
        16 -> "actualizado"
        else -> "otro motivo ($r)"
    }

    private fun describe(e: ApplicationExitInfo, withTrace: Boolean): String {
        val sb = StringBuilder(reasonName(e.reason))
        e.description?.takeIf { it.isNotBlank() }?.let { sb.append(" · ").append(it.take(200)) }
        sb.append(" · importancia ").append(e.importance)
        if (withTrace && e.reason == ApplicationExitInfo.REASON_ANR) mainThread(e)?.let { sb.append("\n").append(it) }
        return sb.toString()
    }

    /** The "main" thread's top frames from the ANR trace (where it was stuck). */
    private fun mainThread(e: ApplicationExitInfo): String? = runCatching {
        val text = e.traceInputStream?.bufferedReader()?.use { it.readText() } ?: return null
        val start = text.indexOf("\"main\"")
        if (start < 0) return text.take(1_500)
        text.substring(start).lineSequence().take(22).joinToString("\n")
    }.getOrNull()
}
