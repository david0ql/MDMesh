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

    /** Android 9: DISALLOW_USER_SWITCH and DISALLOW_SYSTEM_ERROR_DIALOGS exist from here on. */
    const val CRASH_DIALOG_RESTRICTION_SDK = 28

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

    /** `DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (API 30). */
    const val LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 64

    /** Android 14: Settings became exempt from suspension (`OP_SYSTEM_EXEMPT_FROM_SUSPENSION`). */
    const val SETTINGS_UNSUSPENDABLE_SDK = 34

    /**
     * Lock task only stops an app from opening a screen in a NEW task; an allowed app can still start another app's
     * screen inside its own task — e.g. Wi-Fi settings. Up to Android 13 the kiosk closes that by suspending
     * Settings.
     * Android 14+ refuses to suspend Settings (and hiding it instead leaves the phone without its boot-time home: it
     * never finishes starting), so on Android 14/15 lock task itself is told to block in-task starts of anything
     * that is not allowed in the kiosk. (Android 16+ has no lock task here: see [softKiosk].)
     */
    fun blockInTaskStarts(sdk: Int): Boolean = sdk >= SETTINGS_UNSUSPENDABLE_SDK && !softKiosk(sdk)

    /**
     * Soft kiosk only: whether the status bar (and with it the system quick settings and their gear to Settings) is
     * disabled. Closed unless the policy explicitly asks for it ([softStatusBar], which the server sends only for an
     * explicit "yes" to notifications — the plain `notifications` flag is also true for "auto", so it cannot be
     * used).
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
        if (sdk >= CRASH_DIALOG_RESTRICTION_SDK) {
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
