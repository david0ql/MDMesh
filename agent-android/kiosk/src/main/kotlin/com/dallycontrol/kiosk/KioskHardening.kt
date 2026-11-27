package com.dallycontrol.kiosk

/**
 * Pure (Android-free) decisions of the kiosk lock-down, so they are unit-testable on the JVM: which kind of kiosk a
 * release runs, which user restrictions a kiosk always sets, and which packages are kept from opening.
 *
 * Restriction keys are literals mirroring `android.os.UserManager.DISALLOW_*` (stable Android API).
 */
object KioskHardening {
    /** First release whose lock task crashes system_server on real phones (see docs/KIOSK-HARDENING.md). */
    const val SOFT_KIOSK_SDK = 36

    const val DISALLOW_SAFE_BOOT = "no_safe_boot"
    const val DISALLOW_ADD_USER = "no_add_user"
    const val DISALLOW_USER_SWITCH = "no_user_switch"
    const val DISALLOW_SYSTEM_ERROR_DIALOGS = "no_system_error_dialogs"

    /**
     * Android 16+ runs the "soft" kiosk: persistent HOME, no lock task (its LockTaskController brings system_server
     * down on some phones, a reboot loop). Everything lock task gave for free is then enforced by other means:
     * the status bar is disabled, every app outside the kiosk is suspended, and the kiosk restrictions apply.
     */
    fun softKiosk(sdk: Int): Boolean = sdk >= SOFT_KIOSK_SDK

    /**
     * Soft kiosk only: whether the status bar (and with it the system quick settings and their gear to Settings) is
     * disabled. Closed unless the policy explicitly asks for it ([softStatusBar], which the server sends only for an
     * explicit "yes" to notifications — the plain `notifications` flag is also true for "auto", so it cannot be used).
     */
    fun statusBarDisabled(softStatusBar: Boolean?): Boolean = softStatusBar != true

    /**
     * User restrictions every kiosk sets, whatever the policy: no safe mode (it boots without the agent), no new
     * users or user switching (a fresh user has no kiosk), and — API 28+ — no system crash/ANR dialogs (their
     * "App info" button opens Settings).
     */
    fun restrictions(sdk: Int): Set<String> = buildSet {
        add(DISALLOW_SAFE_BOOT)
        add(DISALLOW_ADD_USER)
        if (sdk >= 28) {
            add(DISALLOW_USER_SWITCH)
            add(DISALLOW_SYSTEM_ERROR_DIALOGS)
        }
    }

    /**
     * Packages to suspend while in kiosk (a suspended app cannot be opened by anyone: not from Recents, a
     * notification, the quick-settings gear, nor by an allowed app's intent).
     *
     *  - Always: the packages that serve the system Settings screens ([settingsPackages]). Lock task alone lets an
     *    allowed app open them inside its own task on older Android.
     *  - Soft kiosk: also every app with a launcher icon ([launchable]) that the kiosk does not allow, since
     *    nothing else keeps them closed there.
     *
     * Never: what the kiosk allows ([allowed], which includes the functions' packages) or the device needs to keep
     * working ([protected]: the agent, the launchers, keyboards, the dialer, remote support…).
     */
    fun suspendPlan(
        soft: Boolean,
        launchable: Collection<String>,
        settingsPackages: Collection<String>,
        allowed: Collection<String>,
        protected: Collection<String>,
    ): Set<String> {
        val candidates = if (soft) launchable + settingsPackages else settingsPackages
        return candidates.toSet() - allowed.toSet() - protected.toSet()
    }
}
