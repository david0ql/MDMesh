package com.dallycontrol.agent.policy

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import com.dallycontrol.core.config.ManagedWifi
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.ConfigWifi

/**
 * The configuration's Wi-Fi networks, saved as the Device Owner. Remembers which SSIDs it added, so a network removed
 * from the configuration is removed from the phone — networks the user added are left alone.
 */
class AndroidManagedWifi(private val context: Context, private val wifi: WifiNetworks) : ManagedWifi {
    private val prefs = context.getSharedPreferences("mdm_wifi_policy", Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun apply(networks: List<ConfigWifi>): String {
        val before = prefs.getStringSet(KEY, emptySet()).orEmpty()
        val wanted = networks.map { it.ssid }.toSet()
        val failed = mutableListOf<String>()
        networks.forEachIndexed { i, n ->
            // Join the first network only when the phone has no Wi-Fi connection; the rest are just saved.
            val connect = i == 0 && wifi.connectedSsid() == null
            wifi.add(n.ssid, n.password, n.security, n.hidden == true, connect)?.let { failed += "${n.ssid}: $it" }
        }
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        (before - wanted).forEach { gone ->
            runCatching { wm.configuredNetworks.orEmpty().filter { it.SSID == "\"$gone\"" }.forEach { wm.removeNetwork(it.networkId) } }
        }
        prefs.edit().putStringSet(KEY, wanted).apply()
        return if (failed.isEmpty()) ConfigOutcome.APPLIED else ConfigOutcome.failed(failed.joinToString("; "))
    }

    private companion object { const val KEY = "added" }
}
