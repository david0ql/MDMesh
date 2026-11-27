package com.dallycontrol.policy.hardware

import android.content.ContentResolver
import android.os.Build
import android.provider.Settings
import com.dallycontrol.policy.wifi.DpmHandle

/** [HardwareBackend] over `DevicePolicyManager`. Every raw call of the hardware rules lives here. */
@Suppress("TooManyFunctions") // one small function per device switch
class DpmHardwareBackend(private val handle: DpmHandle, private val resolver: ContentResolver) : HardwareBackend {

    override val sdk: Int get() = Build.VERSION.SDK_INT

    override fun isDeviceOwner(): Boolean = handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setRestriction(key: String, on: Boolean) {
        if (on) handle.dpm.addUserRestriction(handle.admin, key) else handle.dpm.clearUserRestriction(handle.admin, key)
    }

    override fun hasRestriction(key: String): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            handle.dpm.getUserRestrictions(handle.admin).getBoolean(key, false)
        } else {
            null
        }

    override fun setCameraDisabled(disabled: Boolean) = handle.dpm.setCameraDisabled(handle.admin, disabled)

    override fun isCameraDisabled(): Boolean = handle.dpm.getCameraDisabled(handle.admin)

    override fun canDisableUsbData(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && handle.dpm.canUsbDataSignalingBeDisabled()

    override fun setUsbDataEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) handle.dpm.isUsbDataSignalingEnabled = enabled
    }

    override fun isUsbDataEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || handle.dpm.isUsbDataSignalingEnabled

    @Suppress("DEPRECATION") // the only way to pin the clock before DISALLOW_CONFIG_DATE_TIME (Android 9)
    override fun setAutoTimeRequired(required: Boolean) = handle.dpm.setAutoTimeRequired(handle.admin, required)

    @Suppress("DEPRECATION")
    override fun isAutoTimeRequired(): Boolean = handle.dpm.autoTimeRequired

    override fun isAdbEnabled(): Boolean = Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1

    // A Device Owner may write this global setting (DevicePolicyManager.setGlobalSetting's allowed list).
    override fun enableAdb() = handle.dpm.setGlobalSetting(handle.admin, Settings.Global.ADB_ENABLED, "1")
}
