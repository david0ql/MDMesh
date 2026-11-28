package com.dallycontrol.policy.wifi

import android.os.UserManager
import com.dallycontrol.policy.PolicyOutcome

/**
 * Wi-Fi strategy for API 23–29 (Android 6–10).
 *
 * Predates `DevicePolicyManager.setConfiguredNetworksLockdownState` (API 30): the managed networks are locked with the
 * global setting `wifi_device_owner_configs_lockdown`, which is the same switch the newer API flips. Nothing of the
 * Android 13 lock-down (SSID policy, minimum security, add/state restrictions) exists here.
 */
internal open class LegacyWifiPolicy(
    protected val handle: DpmHandle,
    private val radio: WifiRadio? = null,
) : WifiPolicy {

    override val capabilityKey: String = WifiPolicy.CAPABILITY_KEY

    override fun isSupported(): Boolean =
        handle.dpm.isDeviceOwnerApp(handle.admin.packageName)

    override fun setRadioEnabled(enabled: Boolean): PolicyOutcome {
        val r = radio ?: return PolicyOutcome.Unsupported
        return runCatching {
            if (r.set(enabled)) PolicyOutcome.Applied else PolicyOutcome.Failed("the platform refused to switch Wi-Fi")
        }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi radio") }
    }

    override fun setUserChangesBlocked(blocked: Boolean): PolicyOutcome =
        restriction(UserManager.DISALLOW_CONFIG_WIFI, blocked)

    override fun setManagedNetworksLocked(locked: Boolean): PolicyOutcome = runCatching {
        handle.dpm.setGlobalSetting(handle.admin, LOCKDOWN_SETTING, if (locked) "1" else "0")
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi lockdown") }

    override fun setAddingNetworksBlocked(blocked: Boolean): PolicyOutcome = PolicyOutcome.Unsupported

    override fun setStateChangeBlocked(blocked: Boolean): PolicyOutcome = PolicyOutcome.Unsupported

    override fun setSsidRestriction(mode: WifiPolicy.SsidMode?, ssids: Set<String>): PolicyOutcome =
        PolicyOutcome.Unsupported

    override fun setMinimumSecurity(level: Int): PolicyOutcome = PolicyOutcome.Unsupported

    protected fun restriction(key: String, on: Boolean): PolicyOutcome = runCatching {
        if (on) handle.dpm.addUserRestriction(handle.admin, key) else handle.dpm.clearUserRestriction(handle.admin, key)
        PolicyOutcome.Applied
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "restriction $key") }

    private companion object {
        /** Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN. */
        const val LOCKDOWN_SETTING = "wifi_device_owner_configs_lockdown"
    }
}
