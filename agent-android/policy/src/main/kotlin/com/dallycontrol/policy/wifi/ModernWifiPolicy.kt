package com.dallycontrol.policy.wifi

import android.app.admin.WifiSsidPolicy
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.UserManager
import androidx.annotation.RequiresApi
import com.dallycontrol.policy.PolicyOutcome

/**
 * Wi-Fi strategy for API 30+ (Android 11+).
 *
 *  - API 30+: `setConfiguredNetworksLockdownState` locks the networks the Device Owner saved.
 *  - API 33+: `DISALLOW_ADD_WIFI_CONFIG`, `DISALLOW_CHANGE_WIFI_STATE`, `setWifiSsidPolicy` (allow / deny list) and
 *    `setMinimumRequiredWifiSecurityLevel`. On API 30–32 those four answer [PolicyOutcome.Unsupported].
 *
 * The radio and `DISALLOW_CONFIG_WIFI` are inherited: they are the same on every version.
 */
internal class ModernWifiPolicy(
    handle: DpmHandle,
    radio: WifiRadio? = null,
) : LegacyWifiPolicy(handle, radio) {

    override fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && super.isSupported()

    override fun setManagedNetworksLocked(locked: Boolean): PolicyOutcome = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handle.dpm.setConfiguredNetworksLockdownState(handle.admin, locked)
            PolicyOutcome.Applied
        } else {
            PolicyOutcome.Unsupported
        }
    }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi lockdown") }

    override fun setAddingNetworksBlocked(blocked: Boolean): PolicyOutcome =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            restriction(UserManager.DISALLOW_ADD_WIFI_CONFIG, blocked)
        } else {
            PolicyOutcome.Unsupported
        }

    override fun setStateChangeBlocked(blocked: Boolean): PolicyOutcome =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            restriction(UserManager.DISALLOW_CHANGE_WIFI_STATE, blocked)
        } else {
            PolicyOutcome.Unsupported
        }

    override fun setSsidRestriction(mode: WifiPolicy.SsidMode?, ssids: Set<String>): PolicyOutcome =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { ssidPolicy(mode, ssids) }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi ssid policy") }
        } else {
            PolicyOutcome.Unsupported
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ssidPolicy(mode: WifiPolicy.SsidMode?, ssids: Set<String>): PolicyOutcome {
        // Android refuses an empty set; "deny nothing" is the same as no policy, and "allow nothing" is never sent
        // (the agent always adds the managed networks) — both clear it.
        if (mode == null || ssids.isEmpty()) {
            handle.dpm.wifiSsidPolicy = null
            return PolicyOutcome.Applied
        }
        val type = if (mode == WifiPolicy.SsidMode.ALLOW) {
            WifiSsidPolicy.WIFI_SSID_POLICY_TYPE_ALLOWLIST
        } else {
            WifiSsidPolicy.WIFI_SSID_POLICY_TYPE_DENYLIST
        }
        val set = ssids.map { WifiSsid.fromBytes(it.toByteArray(Charsets.UTF_8)) }.toSet()
        handle.dpm.wifiSsidPolicy = WifiSsidPolicy(type, set)
        return PolicyOutcome.Applied
    }

    override fun setMinimumSecurity(level: Int): PolicyOutcome =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                // DevicePolicyManager.WIFI_SECURITY_OPEN..WIFI_SECURITY_ENTERPRISE_192 are 0..3, the same scale.
                handle.dpm.minimumRequiredWifiSecurityLevel = level.coerceIn(0, MAX_LEVEL)
                PolicyOutcome.Applied
            }.getOrElse { PolicyOutcome.Failed(it.message ?: "wifi minimum security") }
        } else {
            PolicyOutcome.Unsupported
        }

    private companion object { const val MAX_LEVEL = 3 }
}
