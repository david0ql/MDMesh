package com.dallycontrol.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** The active network, as far as diagnostics care. */
data class NetworkSnapshot(val type: String, val ssid: String? = null) {
    val connected: Boolean get() = type != NONE

    companion object {
        const val NONE = "none"
    }
}

fun interface NetworkStateSource {
    fun current(): NetworkSnapshot
}

class AndroidNetworkState(private val context: Context) : NetworkStateSource {
    override fun current(): NetworkSnapshot =
        runCatching { read() }.getOrDefault(NetworkSnapshot(NetworkSnapshot.NONE))

    private fun read(): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkSnapshot(NetworkSnapshot.NONE)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkSnapshot("wifi", ssid())
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkSnapshot("cellular")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkSnapshot("ethernet")
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkSnapshot("vpn")
            else -> NetworkSnapshot("other")
        }
    }

    @Suppress("DEPRECATION")
    private fun ssid(): String? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
    }.getOrNull()
}

/** [KeyValueStore] on SharedPreferences. */
class PrefsKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
}
