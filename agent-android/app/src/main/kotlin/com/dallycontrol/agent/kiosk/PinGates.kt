package com.dallycontrol.agent.kiosk

import android.content.Context
import com.dallycontrol.agent.R
import com.dallycontrol.kiosk.PinAttemptStore
import com.dallycontrol.kiosk.PinGate
import com.dallycontrol.kiosk.PinResult

/** A [PinAttemptStore] in the agent's private preferences: the failure count and the wait survive a restart. */
class PrefsPinAttemptStore(context: Context, private val name: String) : PinAttemptStore {
    private val prefs = context.applicationContext.getSharedPreferences("mdm_pin_gate", Context.MODE_PRIVATE)
    override var failures: Int
        get() = prefs.getInt("$name.failures", 0)
        set(value) { prefs.edit().putInt("$name.failures", value).apply() }
    override var lockedUntil: Long
        get() = prefs.getLong("$name.lockedUntil", 0L)
        set(value) { prefs.edit().putLong("$name.lockedUntil", value).apply() }
}

/** The two secrets typed on a kiosk device, each with its own attempt counter. */
object PinGates {
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60_000L

    /** The kiosk exit password. */
    fun exit(context: Context): PinGate = PinGate(PrefsPinAttemptStore(context, "exit"))

    /** The anti-theft (switch off / restart) PIN. */
    fun power(context: Context): PinGate = PinGate(PrefsPinAttemptStore(context, "power"))

    /** "45 s" / "3 min", for the message shown while waiting. */
    fun waitText(ms: Long): String =
        if (ms < MINUTE_MS) "${(ms + SECOND_MS - 1) / SECOND_MS} s" else "${(ms + MINUTE_MS - 1) / MINUTE_MS} min"

    /** What to tell the person after a refused attempt (null when it passed). */
    fun message(context: Context, r: PinResult): String? = when (r) {
        PinResult.Ok -> null
        PinResult.NoSecret -> context.getString(R.string.kiosk_exit_console_only)
        is PinResult.Locked -> context.getString(R.string.pin_locked, waitText(r.remainingMs))
        is PinResult.Wrong ->
            if (r.lockedMs > 0) context.getString(R.string.pin_wrong_wait, waitText(r.lockedMs))
            else context.getString(R.string.pin_wrong)
    }

    /** The same, for the event sent to the console (never the typed value). */
    fun eventDetail(r: PinResult): String = when (r) {
        PinResult.Ok -> "ok"
        PinResult.NoSecret -> "sin contraseña en la política: salida solo desde la consola"
        is PinResult.Locked -> "en espera, faltan ${waitText(r.remainingMs)}"
        is PinResult.Wrong -> "intento ${r.failures}" + if (r.lockedMs > 0) "; espera ${waitText(r.lockedMs)}" else ""
    }
}
