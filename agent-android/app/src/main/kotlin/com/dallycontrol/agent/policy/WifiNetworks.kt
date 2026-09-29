package com.dallycontrol.agent.policy

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/**
 * Adds and joins Wi-Fi networks as the Device Owner (Android 10+ lets only DO/PO use the legacy WifiManager network
 * APIs, which is what makes this work without user prompts). Used by the kiosk quick settings (a network the user
 * picks) and by the configuration's Wi-Fi policy (networks pushed from the console).
 */
@Suppress("DEPRECATION")
class WifiNetworks(private val context: Context) {
    private val wifi: WifiManager get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** @param security `WPA` (WPA/WPA2-PSK), `WEP` or `NONE`. @return null when saved (and joined when [connect]). */
    @SuppressLint("MissingPermission")
    fun add(ssid: String, password: String?, security: String, hidden: Boolean = false, connect: Boolean = true): String? {
        if (ssid.isBlank()) return "empty SSID"
        if (!wifi.isWifiEnabled) runCatching { wifi.isWifiEnabled = true }
        val quoted = "\"$ssid\""
        val existing = runCatching { wifi.configuredNetworks }.getOrNull().orEmpty().filter { it.SSID == quoted }
        val conf = WifiConfiguration().apply {
            SSID = quoted
            hiddenSSID = hidden
            when (security.uppercase()) {
                "NONE", "OPEN" -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                "WEP" -> {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    wepKeys[0] = "\"${password.orEmpty()}\""; wepTxKeyIndex = 0
                }
                else -> preSharedKey = "\"${password.orEmpty()}\""
            }
        }
        existing.forEach { runCatching { wifi.removeNetwork(it.networkId) } }
        val id = wifi.addNetwork(conf)
        if (id < 0) return "the network could not be saved"
        if (connect) wifi.enableNetwork(id, true)
        runCatching { wifi.saveConfiguration() }
        return null
    }

    /** Networks in range, strongest first (needs location, which the agent holds). */
    @SuppressLint("MissingPermission")
    fun nearby(): List<Pair<String, Boolean>> {
        runCatching { wifi.startScan() }
        return runCatching { wifi.scanResults }.getOrNull().orEmpty()
            .filter { !it.SSID.isNullOrBlank() }
            .sortedByDescending { it.level }
            .distinctBy { it.SSID }
            .map { it.SSID to (it.capabilities.contains("WPA") || it.capabilities.contains("WEP") || it.capabilities.contains("SAE")) }
    }

    fun connectedSsid(): String? = runCatching { wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } }.getOrNull()

    fun isEnabled() = runCatching { wifi.isWifiEnabled }.getOrDefault(false)

    fun setEnabled(on: Boolean) = runCatching { wifi.isWifiEnabled = on }.isSuccess
}
