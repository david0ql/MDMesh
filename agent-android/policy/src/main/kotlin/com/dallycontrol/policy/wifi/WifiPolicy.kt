package com.dallycontrol.policy.wifi

import com.dallycontrol.policy.PolicyOutcome
import com.dallycontrol.policy.TogglePolicy

/**
 * Capability-abstracted Wi-Fi control as Device Owner. Two different things live here and are kept apart:
 *
 *  - the **radio** ([setRadioEnabled], which is also what the `wifi` toggle means): Wi-Fi switched on or off;
 *  - the **restrictions**: what the user may change ([setUserChangesBlocked], [setManagedNetworksLocked],
 *    [setAddingNetworksBlocked], [setStateChangeBlocked]) and which networks the phone may join at all
 *    ([setSsidRestriction], [setMinimumSecurity]).
 *
 * Two strategies implement it, chosen once by [WifiPolicyFactory]:
 *  - [ModernWifiPolicy] (API 30+): `setConfiguredNetworksLockdownState`, and on API 33+ the SSID policy, the minimum
 *    security level and the `DISALLOW_ADD_WIFI_CONFIG` / `DISALLOW_CHANGE_WIFI_STATE` restrictions;
 *  - [LegacyWifiPolicy] (API 23–29): `DISALLOW_CONFIG_WIFI` and the `wifi_device_owner_configs_lockdown` global
 *    setting.
 *
 * A rule the running Android version lacks answers [PolicyOutcome.Unsupported] — never a silent no-op — so the
 * agent can report it.
 */
interface WifiPolicy : TogglePolicy {

    /** The `wifi` toggle: the radio forced on or off. Restrictions are separate (see the class doc). */
    override fun setEnabled(enabled: Boolean): PolicyOutcome = setRadioEnabled(enabled)

    /** Switch the Wi-Fi radio. */
    fun setRadioEnabled(enabled: Boolean): PolicyOutcome

    /** `DISALLOW_CONFIG_WIFI`: the user cannot open or change any Wi-Fi setting. */
    fun setUserChangesBlocked(blocked: Boolean): PolicyOutcome

    /** The networks the Device Owner saved cannot be edited or forgotten by the user. */
    fun setManagedNetworksLocked(locked: Boolean): PolicyOutcome

    /** `DISALLOW_ADD_WIFI_CONFIG` (API 33+): the user cannot add networks. */
    fun setAddingNetworksBlocked(blocked: Boolean): PolicyOutcome

    /** `DISALLOW_CHANGE_WIFI_STATE` (API 33+): the user cannot switch Wi-Fi on or off. */
    fun setStateChangeBlocked(blocked: Boolean): PolicyOutcome

    /** Only [ssids] may be joined ([SsidMode.ALLOW]), or none of them ([SsidMode.DENY]); null lifts it. API 33+. */
    fun setSsidRestriction(mode: SsidMode?, ssids: Set<String>): PolicyOutcome

    /** Minimum security of any network joined: 0 open, 1 personal, 2 enterprise, 3 enterprise 192-bit. API 33+. */
    fun setMinimumSecurity(level: Int): PolicyOutcome

    enum class SsidMode { ALLOW, DENY }

    companion object {
        const val CAPABILITY_KEY = "wifi"
    }
}

/** Switches the Wi-Fi radio; supplied by the app (WifiManager), so this module stays free of it in tests. */
fun interface WifiRadio {
    /** @return false when the platform refused. */
    fun set(enabled: Boolean): Boolean
}
