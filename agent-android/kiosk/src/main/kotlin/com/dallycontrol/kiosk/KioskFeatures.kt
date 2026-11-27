package com.dallycontrol.kiosk

import android.app.admin.DevicePolicyManager

/**
 * Kiosk UI toggles as delivered in a `kiosk.apply` command payload. Each is nullable:
 * `null` means "leave at the framework default for that feature".
 */
data class KioskToggles(
    val home: Boolean? = null,
    val recents: Boolean? = null,
    val notifications: Boolean? = null,
    val systemInfo: Boolean? = null,
    val keyguard: Boolean? = null,
    val lockButtons: Boolean? = null,
)

/**
 * Map [KioskToggles] to a `LOCK_TASK_FEATURE_*` bitmask for
 * [DevicePolicyManager.setLockTaskFeatures] (API 28+).
 *
 * Each enabling toggle adds its feature flag. The power menu
 * ([DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS]) is ON by default — that is the
 * framework's own default in lock-task — and is removed only when [KioskToggles.lockButtons]
 * is explicitly `true` ("lock the hardware buttons").
 *
 * On Android 14/15 the mask also carries `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (see
 * [KioskHardening.blockInTaskStarts]); [sdk] is a parameter only so that this is unit-testable.
 */
fun lockTaskFeatures(t: KioskToggles, sdk: Int = android.os.Build.VERSION.SDK_INT): Int {
    var f = 0
    // Android 14/15: an allowed app may not start another app's screen (Settings above all) inside its own task.
    if (KioskHardening.blockInTaskStarts(sdk)) f = f or KioskHardening.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
    if (t.home == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_HOME
    if (t.recents == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
    if (t.notifications == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
    if (t.systemInfo == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
    if (t.keyguard == true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
    if (t.lockButtons != true) f = f or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    return f
}
