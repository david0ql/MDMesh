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

    /** Android 11: lock task can block an allowed app from starting other apps' screens inside its own task. */
    const val BLOCK_IN_TASK_SDK = 30

    /**
     * Lock task only stops an app from opening a screen in a NEW task; an allowed app can still start another app's
     * screen inside its own task — e.g. Wi-Fi settings — and walk the whole Settings app from there. From Android 11
     * lock task itself can be told to block that for anything not allowed in the kiosk; the kiosk always asks for it.
     * (Suspending Settings instead is not an option on any release: it hosts Android's boot-time home.)
     */
    fun blockInTaskStarts(sdk: Int): Boolean = sdk >= BLOCK_IN_TASK_SDK && !softKiosk(sdk)

    /**
     * Where nothing in the platform keeps Settings out of an allowed app's reach — Android 7–10 (no in-task block)
     * and the soft kiosk (no lock task) — a foreground guard sends the phone back to the kiosk instead.
     */
    fun needsForegroundGuard(sdk: Int): Boolean = !blockInTaskStarts(sdk)

    const val GUARD_LOCK_TASK = "lockTask"
    const val GUARD_WATCHING = "guard"
    const val GUARD_MISSING_ACCESS = "needsUsageAccess"

    /**
     * How the system Settings is kept closed in this kiosk, as reported to the console:
     * [GUARD_LOCK_TASK] (Android 11–15: the platform blocks it, nothing else needed), [GUARD_WATCHING] (the foreground
     * guard is watching) or [GUARD_MISSING_ACCESS] (the guard is needed but the agent has no usage access: an allowed
     * app, or the status bar on Android 16+, can still reach Settings until someone grants it on the phone).
     */
    fun settingsGuard(sdk: Int, guardNeeded: Boolean, canWatch: Boolean): String = when {
        !needsForegroundGuard(sdk) || !guardNeeded -> GUARD_LOCK_TASK
        canWatch -> GUARD_WATCHING
        else -> GUARD_MISSING_ACCESS
    }

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
     *  - Always: the packages that lead into the system Settings ([settingsPackages]: its search app…). The Settings
     *    app itself is passed in [protected] by the caller: it must never be suspended (boot-time home).
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
