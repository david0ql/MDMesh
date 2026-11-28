package com.dallycontrol.agent.kiosk

import android.content.Context
import android.os.SystemClock
import com.dallycontrol.agent.support.RemoteSetupActivity
import com.dallycontrol.agent.support.SupportEngine
import com.dallycontrol.kiosk.KioskHardening

/**
 * A kiosk whose guard still lacks its one authorisation (the agent's screen service, one tap on the phone — a phone
 * enrolled by QR) is never left quietly half-closed: the guided step is shown the moment the kiosk finds out, and
 * again every time the kiosk home comes back, at most once every [ASK_EVERY_MS], until it is done. Settings is let
 * through for it and closed again the moment the kiosk home is back in front.
 */
object KioskAuthPrompt {
    /** How often the pending authorisation is asked for again, and how long Settings stays open for it. */
    private const val ASK_EVERY_MS = 10 * 60_000L
    private const val ASK_WINDOW_MS = 5 * 60_000L

    @Volatile private var lastAsked = 0L

    fun pending(context: Context): Boolean =
        AndroidKioskLockdown.gated(context) ||
            AndroidKioskLockdown.guardState(context) == KioskHardening.GUARD_MISSING_ACCESS

    @Volatile private var gateAsked = false

    /**
     * The gate closed the kiosk ([KioskHardening.authGate]): the guided step comes up by itself over the kiosk home,
     * once per closing. After "Ahora no" the person is back on the gate screen, whose button opens it again.
     */
    fun askForGate(context: Context): Boolean {
        val gated = AndroidKioskLockdown.gated(context)
        if (!gated) gateAsked = false
        return gated && !gateAsked && ask(context, force = true)
    }

    /** @return true when the guided step was opened. [force] = the person asked for it (no waiting). */
    fun ask(context: Context, force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        val due = force || lastAsked == 0L || now - lastAsked >= ASK_EVERY_MS
        if (!pending(context) || !due) return false
        lastAsked = now
        SupportEngine.syncComponent(context)
        TimedAllow.allow(context, "com.android.settings", ASK_WINDOW_MS)
        val opened = RemoteSetupActivity.open(context)
        if (opened && AndroidKioskLockdown.gated(context)) gateAsked = true
        return opened
    }
}
