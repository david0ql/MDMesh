package com.dallycontrol.policy.wifi

/**
 * Selects the correct [WifiPolicy] strategy for the current device — the single
 * place the `Build.VERSION.SDK_INT` branch is resolved.
 *
 * Candidates are ordered most-capable-first; the first [WifiPolicy.isSupported]
 * wins. Returns `null` when the device supports no strategy at all (e.g. not a
 * Device Owner), in which case the `wifi` capability is simply never advertised.
 */
object WifiPolicyFactory {

    /** @param radio how to switch the Wi-Fi radio (null = the radio cannot be driven, e.g. in a test). */
    fun create(handle: DpmHandle, radio: WifiRadio? = null): WifiPolicy? {
        val candidates = listOf(
            ModernWifiPolicy(handle, radio),
            LegacyWifiPolicy(handle, radio),
        )
        return candidates.firstOrNull { it.isSupported() }
    }
}
