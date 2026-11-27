// Android API levels are compared as numbers throughout: they are the vocabulary of this file (see the class doc).
@file:Suppress("MagicNumber")

package com.dallycontrol.agent.policy

import android.annotation.SuppressLint
import android.content.Context
import android.net.IpConfiguration
import android.net.LinkAddress
import android.net.ProxyInfo
import android.net.StaticIpConfiguration
import android.net.Uri
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiEnterpriseConfig
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import androidx.annotation.RequiresApi
import com.dallycontrol.core.config.WifiSecurity
import com.dallycontrol.proto.ConfigWifi
import com.dallycontrol.proto.ConfigWifiEap
import com.dallycontrol.proto.ConfigWifiIp
import com.dallycontrol.proto.ConfigWifiProxy
import java.net.InetAddress

/**
 * Saves and joins Wi-Fi networks. Used by the kiosk quick settings (a network the user picks) and by the policy's
 * Wi-Fi (networks pushed from the console).
 *
 * Which Android API is used, by version (docs/WIFI.md has the full table):
 *  - **Device Owner / Profile Owner, every version** → `WifiConfiguration` + `WifiManager.addNetwork`. Deprecated for
 *    ordinary apps since Android 10, but it is the only API that saves a network silently, lets the owner remove it,
 *    and that the lock-down (`setConfiguredNetworksLockdownState`) protects. API 30+ selects the security with
 *    `setSecurityParams`; API 29 with `KeyMgmt.SAE` / `KeyMgmt.OWE`; older versions only know open, WEP, PSK and EAP.
 *  - **Not an owner, Android 10+** → `WifiNetworkSuggestion` ([suggest]): the user approves once and the system joins
 *    on its own; the network cannot be locked and the user's own networks are untouched.
 *  - Enterprise networks use `WifiEnterpriseConfig` in both paths.
 *
 * Options without a public API on the running version are attempted (some are reachable only through fields the
 * platform hides) and, when Android refuses, reported in [Saved.unsupportedOptions] — the network is saved without
 * them.
 */
@Suppress("DEPRECATION", "TooManyFunctions")
class WifiNetworks(private val context: Context) {
    private val wifi: WifiManager
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** [netId] < 0 = not saved, with [error]; [certificateProblem] = the reason is one of its certificates. */
    data class Saved(
        val netId: Int,
        val error: String? = null,
        val certificateProblem: Boolean = false,
        val unsupportedOptions: List<String> = emptyList(),
    )

    /** @param security `WPA` (WPA/WPA2-PSK), `WEP` or `NONE`. @return null when saved (and joined when [connect]). */
    fun add(
        ssid: String,
        password: String?,
        security: String,
        hidden: Boolean = false,
        connect: Boolean = true,
    ): String? =
        save(ConfigWifi(ssid = ssid, password = password, security = security, hidden = hidden), null, connect).error

    /**
     * Save [net] as the owner, replacing any saved network of the same SSID.
     *
     * @param certs where the enterprise certificates are installed (null = enterprise networks cannot be saved).
     * @param connect join it now. @param mayEnableRadio switch Wi-Fi on first (false when the policy forces it off).
     */
    @SuppressLint("MissingPermission")
    @Suppress("ReturnCount")
    fun save(net: ConfigWifi, certs: WifiCertificates?, connect: Boolean, mayEnableRadio: Boolean = true): Saved {
        if (net.ssid.isBlank()) return Saved(-1, "empty SSID")
        if (mayEnableRadio && !wifi.isWifiEnabled) runCatching { wifi.isWifiEnabled = true }
        val quoted = quote(net.ssid)
        val existing = configured().filter { it.SSID == quoted }
        // Full options first; when Android refuses the network, the options it may be refusing are dropped one step
        // at a time so that a rejected extra never costs the network itself.
        var skipped = emptySet<String>()
        var id = -1
        var unsupported = emptyList<String>()
        for (attempt in FALLBACKS) {
            skipped = attempt
            val dropped = mutableListOf<String>()
            val conf = try {
                build(net, certs, attempt, dropped)
            } catch (e: WifiCertificateException) {
                return Saved(-1, e.message, certificateProblem = true)
            } catch (e: IllegalArgumentException) {
                return Saved(-1, e.message ?: "invalid network")
            }
            if (attempt.isEmpty()) existing.forEach { runCatching { wifi.removeNetwork(it.networkId) } }
            id = runCatching { wifi.addNetwork(conf) }.getOrDefault(-1)
            unsupported = dropped
            if (id >= 0) break
        }
        if (id < 0) return Saved(-1, "Android refused the network")
        unsupported = (unsupported + skipped.filter { wanted(net, it) }).distinct()
        if (net.autoConnect == false) {
            runCatching { wifi.disableNetwork(id) }
        } else {
            runCatching { wifi.enableNetwork(id, connect) }
        }
        runCatching { wifi.saveConfiguration() }
        return Saved(id, unsupportedOptions = unsupported)
    }

    private fun wanted(net: ConfigWifi, option: String) = when (option) {
        "mac" -> net.mac != null
        "metered" -> net.metered != null
        "staticIp" -> net.ip != null
        "proxy" -> net.proxy != null
        else -> false
    }

    /** The owner's WifiConfiguration for [net]. Options in [skip] are left out; unsupported ones land in [dropped]. */
    @Suppress("CyclomaticComplexMethod")
    @SuppressLint("InlinedApi") // SECURITY_TYPE_* are compile-time ints; they only reach setSecurityParams on API 30+
    private fun build(
        net: ConfigWifi,
        certs: WifiCertificates?,
        skip: Set<String>,
        dropped: MutableList<String>,
    ): WifiConfiguration {
        val sec = WifiSecurity.normalize(net.security)
        val conf = WifiConfiguration()
        conf.SSID = quote(net.ssid)
        conf.hiddenSSID = net.hidden == true
        net.priority?.let { conf.priority = it.coerceIn(0, MAX_PRIORITY) } // read by Android 7 and older only
        when (sec) {
            WifiSecurity.NONE -> security(conf, WifiConfiguration.SECURITY_TYPE_OPEN) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            WifiSecurity.OWE -> security(conf, WifiConfiguration.SECURITY_TYPE_OWE) {
                if (Build.VERSION.SDK_INT >= 29) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE)
                requirePmf(this)
            }
            WifiSecurity.WEP -> {
                security(conf, WifiConfiguration.SECURITY_TYPE_WEP) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                    allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                }
                conf.wepKeys[0] = key(net.password, WEP_HEX)
                conf.wepTxKeyIndex = 0
            }
            WifiSecurity.WPA3 -> {
                security(conf, WifiConfiguration.SECURITY_TYPE_SAE) {
                    if (Build.VERSION.SDK_INT >= 29) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE)
                    requirePmf(this)
                }
                conf.preSharedKey = quote(net.password.orEmpty()) // SAE takes the passphrase, never the 64-hex PSK
            }
            WifiSecurity.EAP, WifiSecurity.EAP_WPA3 -> {
                val type = if (sec == WifiSecurity.EAP_WPA3 && Build.VERSION.SDK_INT >= 31) {
                    WifiConfiguration.SECURITY_TYPE_EAP_WPA3_ENTERPRISE
                } else {
                    WifiConfiguration.SECURITY_TYPE_EAP
                }
                security(conf, type) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP)
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
                }
                conf.enterpriseConfig = enterprise(requireNotNull(net.eap) { "no EAP parameters" }, certs)
            }
            // WPA and WPA3_TRANSITION: a PSK network. On Android 12+ the system adds SAE to it on its own and uses
            // WPA3 whenever the access point offers it; before that it joins the WPA2 half of the mixed network.
            else -> {
                security(conf, WifiConfiguration.SECURITY_TYPE_PSK) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                }
                conf.preSharedKey = key(net.password, PSK_HEX)
            }
        }
        net.proxy?.takeIf { "proxy" !in skip }?.let { if (!proxy(conf, it)) dropped += "proxy" }
        net.ip?.takeIf { "staticIp" !in skip }?.let { if (!staticIp(conf, it)) dropped += "staticIp" }
        net.mac?.takeIf { "mac" !in skip }?.let { if (!mac(conf, it.lowercase() == "random")) dropped += "mac" }
        net.metered?.takeIf { "metered" !in skip }?.let {
            if (!metered(conf, it.lowercase() == "metered")) dropped += "metered"
        }
        return conf
    }

    /** API 30+ names the security with one call; older versions get the equivalent bit sets from [legacy]. */
    private inline fun security(conf: WifiConfiguration, type: Int, legacy: WifiConfiguration.() -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) conf.setSecurityParams(type) else conf.legacy()
    }

    /** Protected management frames, mandatory for SAE and OWE: a hidden field on Android 10 (automatic later). */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun requirePmf(conf: WifiConfiguration) {
        for (name in arrayOf("requirePmf", "requirePMF")) {
            if (runCatching { WifiConfiguration::class.java.getField(name).setBoolean(conf, true) }.isSuccess) return
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun enterprise(eap: ConfigWifiEap, certs: WifiCertificates?): WifiEnterpriseConfig {
        val store = certs ?: throw WifiCertificateException("certificates cannot be installed here")
        val method = eap.method.trim().uppercase()
        val e = WifiEnterpriseConfig()
        e.eapMethod = when (method) {
            "TTLS" -> WifiEnterpriseConfig.Eap.TTLS
            "TLS" -> WifiEnterpriseConfig.Eap.TLS
            else -> WifiEnterpriseConfig.Eap.PEAP
        }
        e.phase2Method = when (eap.phase2?.trim()?.uppercase()) {
            "MSCHAPV2" -> WifiEnterpriseConfig.Phase2.MSCHAPV2
            "MSCHAP" -> WifiEnterpriseConfig.Phase2.MSCHAP
            "PAP" -> WifiEnterpriseConfig.Phase2.PAP
            "GTC" -> WifiEnterpriseConfig.Phase2.GTC
            else -> WifiEnterpriseConfig.Phase2.NONE
        }
        eap.identity?.takeIf { it.isNotEmpty() }?.let { e.identity = it }
        eap.anonymousIdentity?.takeIf { it.isNotEmpty() }?.let { e.anonymousIdentity = it }
        if (method != "TLS") eap.password?.let { e.password = it }
        val authorities = store.authorities(eap.caCerts)
        if (authorities.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= 24) {
                e.caCertificates = authorities.toTypedArray()
            } else {
                e.caCertificate = authorities.first()
            }
        }
        eap.domain?.takeIf { it.isNotBlank() }?.let { e.domainSuffixMatch = it.trim() }
        eap.altSubjectMatch?.takeIf { it.isNotBlank() }?.let { e.altSubjectMatch = it.trim() }
        if (method == "TLS") {
            val client = store.clientKey(eap.clientKey, eap.clientKeyAlgorithm, eap.clientChain)
            try {
                when {
                    // Android 12+: the Wi-Fi stack uses the key inside the keystore, by alias.
                    Build.VERSION.SDK_INT >= 31 && client.wifiGranted -> e.setClientKeyPairAlias(client.alias)
                    Build.VERSION.SDK_INT >= 26 ->
                        e.setClientKeyEntryWithCertificateChain(client.key, client.chain.toTypedArray())
                    else -> e.setClientKeyEntry(client.key, client.chain.first())
                }
            } catch (@Suppress("SwallowedException") ex: IllegalArgumentException) {
                throw WifiCertificateException("Android refused the client certificate: ${ex.message}")
            }
        }
        return e
    }

    private fun proxy(conf: WifiConfiguration, p: ConfigWifiProxy): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 26) return false
        conf.httpProxy = if (p.mode.lowercase() == "pac") {
            ProxyInfo.buildPacProxy(Uri.parse(p.pacUrl ?: return false))
        } else {
            ProxyInfo.buildDirectProxy(p.host ?: return false, p.port ?: DEFAULT_PROXY_PORT, p.exclude.orEmpty())
        }
        true
    }.getOrDefault(false)

    /** Public API since Android 13; before that the same setters exist but hidden, so they are tried by reflection. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun staticIp(conf: WifiConfiguration, ip: ConfigWifiIp): Boolean = runCatching {
        val address = literal(ip.address) ?: return false
        val gateway = ip.gateway?.takeIf { it.isNotBlank() }?.let { literal(it) ?: return false }
        val dns = ip.dns.orEmpty().filter { it.isNotBlank() }.map { literal(it) ?: return false }
        val link = LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
            .newInstance(address, ip.prefix.coerceIn(1, MAX_PREFIX))
        if (Build.VERSION.SDK_INT >= 33) {
            modernStaticIp(conf, link, gateway, dns)
        } else {
            val sicClass = Class.forName("android.net.StaticIpConfiguration")
            val sic = sicClass.getConstructor().newInstance()
            sicClass.getField("ipAddress").set(sic, link)
            if (gateway != null) sicClass.getField("gateway").set(sic, gateway)
            @Suppress("UNCHECKED_CAST")
            (sicClass.getField("dnsServers").get(sic) as MutableList<InetAddress>).addAll(dns)
            val assignment = Class.forName("android.net.IpConfiguration\$IpAssignment")
            val static = assignment.enumConstants!!.first { (it as Enum<*>).name == "STATIC" }
            WifiConfiguration::class.java.getMethod("setIpAssignment", assignment).invoke(conf, static)
            WifiConfiguration::class.java.getMethod("setStaticIpConfiguration", sicClass).invoke(conf, sic)
        }
        true
    }.getOrDefault(false)

    @RequiresApi(33)
    private fun modernStaticIp(
        conf: WifiConfiguration,
        link: LinkAddress,
        gateway: InetAddress?,
        dns: List<InetAddress>,
    ) {
        val b = StaticIpConfiguration.Builder().setIpAddress(link).setDnsServers(dns)
        if (gateway != null) b.setGateway(gateway)
        conf.setIpConfiguration(IpConfiguration.Builder().setStaticIpConfiguration(b.build()).build())
    }

    /** An IPv4 literal only — never a name, so nothing is resolved. */
    private fun literal(s: String): InetAddress? =
        s.trim().takeIf { IPV4.matches(it) }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }

    /** Android 13+: public setter. Android 10–12: the same field, hidden. Before Android 10: only the device MAC. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun mac(conf: WifiConfiguration, random: Boolean): Boolean = runCatching {
        when {
            Build.VERSION.SDK_INT >= 33 -> conf.macRandomizationSetting =
                if (random) WifiConfiguration.RANDOMIZATION_PERSISTENT else WifiConfiguration.RANDOMIZATION_NONE
            Build.VERSION.SDK_INT >= 29 ->
                WifiConfiguration::class.java.getField("macRandomizationSetting").setInt(conf, if (random) 1 else 0)
            else -> return !random
        }
        true
    }.getOrDefault(false)

    /** `meteredOverride` (Android 9+) has no public setter for a saved network: 1 metered, 2 unmetered. */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi")
    private fun metered(conf: WifiConfiguration, metered: Boolean): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 28) return false
        WifiConfiguration::class.java.getField("meteredOverride").setInt(conf, if (metered) 1 else 2)
        true
    }.getOrDefault(false)

    /**
     * The non-owner path (Android 10+): hand [networks] to the system as suggestions, replacing the ones this app
     * suggested before. @return per SSID, null when accepted or why not.
     */
    @RequiresApi(29)
    @SuppressLint("MissingPermission")
    fun suggest(networks: List<ConfigWifi>, certs: WifiCertificates?): Map<String, String?> {
        val errors = LinkedHashMap<String, String?>()
        val list = networks.mapNotNull { net ->
            runCatching { suggestion(net, certs) }
                .onFailure { errors[net.ssid] = it.message ?: "not supported as a suggestion" }
                .onSuccess { errors[net.ssid] = null }
                .getOrNull()
        }
        runCatching { wifi.removeNetworkSuggestions(emptyList()) } // an empty list removes every suggestion of this app
        if (list.isNotEmpty()) {
            val status = runCatching { wifi.addNetworkSuggestions(list) }.getOrDefault(-1)
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                errors.keys.filter { errors[it] == null }
                    .forEach { errors[it] = "Android refused the suggestion (status $status)" }
            }
        }
        return errors
    }

    @RequiresApi(29)
    @Suppress("CyclomaticComplexMethod")
    private fun suggestion(net: ConfigWifi, certs: WifiCertificates?): WifiNetworkSuggestion {
        val b = WifiNetworkSuggestion.Builder().setSsid(net.ssid)
        val sec = WifiSecurity.normalize(net.security)
        require(sec != WifiSecurity.WEP) { "WEP cannot be suggested" }
        require(sec != WifiSecurity.OWE || Build.VERSION.SDK_INT >= 30) { "OWE needs Android 11" }
        require(sec != WifiSecurity.EAP_WPA3 || Build.VERSION.SDK_INT >= 31) { "$sec needs Android 12" }
        when (sec) {
            WifiSecurity.NONE -> Unit
            WifiSecurity.OWE -> if (Build.VERSION.SDK_INT >= 30) b.setIsEnhancedOpen(true)
            WifiSecurity.WPA3 -> b.setWpa3Passphrase(net.password.orEmpty())
            WifiSecurity.EAP ->
                b.setWpa2EnterpriseConfig(enterprise(requireNotNull(net.eap) { "no EAP parameters" }, certs))
            WifiSecurity.EAP_WPA3 -> if (Build.VERSION.SDK_INT >= 31) {
                val eap = requireNotNull(net.eap) { "no EAP parameters" }
                b.setWpa3EnterpriseStandardModeConfig(enterprise(eap, certs))
            }
            else -> b.setWpa2Passphrase(net.password.orEmpty())
        }
        if (net.hidden == true) b.setIsHiddenSsid(true)
        net.priority?.let { b.setPriority(it.coerceAtLeast(0)) }
        net.metered?.let { b.setIsMetered(it.lowercase() == "metered") }
        if (Build.VERSION.SDK_INT >= 30 && net.autoConnect == false) b.setIsInitialAutojoinEnabled(false)
        if (Build.VERSION.SDK_INT >= 33 && net.mac != null) {
            val random = net.mac?.lowercase() == "random"
            b.setMacRandomizationSetting(
                if (random) {
                    WifiNetworkSuggestion.RANDOMIZATION_PERSISTENT
                } else {
                    WifiNetworkSuggestion.RANDOMIZATION_NON_PERSISTENT
                },
            )
        }
        return b.build()
    }

    /** Saved networks the owner can see: all, or (Android 12+, if that list is withheld) the ones it created. */
    @SuppressLint("MissingPermission")
    fun configured(): List<WifiConfiguration> {
        val all = runCatching { wifi.configuredNetworks }.getOrNull().orEmpty()
        if (all.isNotEmpty() || Build.VERSION.SDK_INT < 31) return all
        return runCatching { wifi.callerConfiguredNetworks }.getOrNull().orEmpty()
    }

    fun isConfigured(ssid: String, netId: Int? = null): Boolean {
        val quoted = quote(ssid)
        return configured().any { it.SSID == quoted || (netId != null && it.networkId == netId) }
    }

    /** Forget every saved network of [ssid] (and [netId], when the list is withheld). */
    fun forget(ssid: String, netId: Int? = null) {
        val quoted = quote(ssid)
        val ids = configured().filter { it.SSID == quoted }.map { it.networkId }.toMutableSet()
        if (ids.isEmpty() && netId != null && netId >= 0) ids += netId
        ids.forEach { runCatching { wifi.removeNetwork(it) } }
        runCatching { wifi.saveConfiguration() }
    }

    /** Join a saved network now. */
    fun connect(ssid: String): Boolean {
        val id = configured().firstOrNull { it.SSID == quote(ssid) }?.networkId ?: return false
        return runCatching { wifi.enableNetwork(id, true) }.getOrDefault(false)
    }

    /** Networks in range, strongest first (needs location, which the agent holds). */
    @SuppressLint("MissingPermission")
    fun nearby(): List<Pair<String, Boolean>> {
        runCatching { wifi.startScan() }
        return runCatching { wifi.scanResults }.getOrNull().orEmpty()
            .filter { !it.SSID.isNullOrBlank() }
            .sortedByDescending { it.level }
            .distinctBy { it.SSID }
            .map { it.SSID to SECURED.any { mark -> it.capabilities.contains(mark) } }
    }

    /** The last scan: SSID → the capabilities of every access point announcing it. Null = no scan available. */
    @SuppressLint("MissingPermission")
    fun scan(): Map<String, List<String>>? {
        val results = runCatching { wifi.scanResults }.getOrNull()
        if (results.isNullOrEmpty()) return null
        return results.filter { !it.SSID.isNullOrBlank() }.groupBy({ it.SSID }, { it.capabilities.orEmpty() })
    }

    fun startScan() { runCatching { wifi.startScan() } }

    fun connectedSsid(): String? =
        runCatching { wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } }.getOrNull()

    fun isConnected(): Boolean =
        runCatching { wifi.connectionInfo?.supplicantState == SupplicantState.COMPLETED }.getOrDefault(false)

    fun wpa3Supported(): Boolean? =
        if (Build.VERSION.SDK_INT >= 29) runCatching { wifi.isWpa3SaeSupported }.getOrNull() else false

    fun oweSupported(): Boolean? =
        if (Build.VERSION.SDK_INT >= 29) runCatching { wifi.isEnhancedOpenSupported }.getOrNull() else false

    fun isEnabled() = runCatching { wifi.isWifiEnabled }.getOrDefault(false)

    fun setEnabled(on: Boolean) = runCatching { wifi.isWifiEnabled = on }.isSuccess

    private fun quote(s: String) = "\"$s\""

    /** A key the way WifiConfiguration wants it: raw when it is [hex] digits, otherwise a quoted passphrase. */
    private fun key(password: String?, hex: Set<Int>): String {
        val p = password.orEmpty()
        return if (p.length in hex && p.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) p else quote(p)
    }

    private companion object {
        val PSK_HEX = setOf(64)
        val WEP_HEX = setOf(10, 26, 58)
        val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        const val MAX_PRIORITY = 999_999
        const val MAX_PREFIX = 32
        const val DEFAULT_PROXY_PORT = 8080

        /** What is left out on each attempt when Android refuses the network. */
        val FALLBACKS = listOf(
            emptySet(),
            setOf("mac"),
            setOf("mac", "metered"),
            setOf("mac", "metered", "staticIp", "proxy"),
        )
        val SECURED = listOf("WPA", "WEP", "SAE")
    }
}
