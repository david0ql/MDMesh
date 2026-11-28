package com.dallycontrol.core.kiosk

import com.dallycontrol.proto.KioskApplyPayload

/**
 * What keeps a kiosk closed besides lock task itself: the kiosk user restrictions (no safe mode, no new users…),
 * the suspension of the system Settings and — where lock task is not used — of every app outside the kiosk, and the
 * status bar. Behind an interface so [KioskApplier]'s orchestration stays unit-testable.
 *
 * Both calls are idempotent; [release] undoes only what [engage] did.
 */
interface KioskLockdown {
    /** @param allowed every package the kiosk lets run (its apps plus the packages its functions resolve to). */
    fun engage(payload: KioskApplyPayload, allowed: List<String>)

    fun release()

    /**
     * Whether the policy's "native lock on Android 16+" may be honoured on this phone right now ([wanted] = what the
     * policy asks). False once the phone was seen restarting in a loop with it on.
     */
    fun nativeLockAllowed(wanted: Boolean): Boolean = wanted
}

/** No lock-down (tests, or a build without Device Owner). */
object NoLockdown : KioskLockdown {
    override fun engage(payload: KioskApplyPayload, allowed: List<String>) = Unit
    override fun release() = Unit
}
