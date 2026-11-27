package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigWifi
import com.dallycontrol.proto.ConfigWifiRules

/**
 * The Android-free half of the Wi-Fi policy: which security each Android version can save, how a scan result matches a
 * managed network, why a managed network is not connected, and when a lock-down must be relaxed. Kept here (no
 * android.* imports) so every decision is unit-tested; the app module only talks to WifiManager / DevicePolicyManager.
 */
object WifiSecurity {
    const val NONE = "NONE"
    const val OWE = "OWE"
    const val WEP = "WEP"
    const val WPA = "WPA"
    const val WPA3 = "WPA3"
    const val WPA3_TRANSITION = "WPA3_TRANSITION"
    const val EAP = "EAP"
    const val EAP_WPA3 = "EAP_WPA3"

    /** Canonical name; anything unknown is treated as WPA/WPA2-PSK, as every agent before this one did. */
    fun normalize(raw: String?): String = when (raw?.trim()?.uppercase()) {
        "NONE", "OPEN" -> NONE
        "OWE" -> OWE
        "WEP" -> WEP
        "WPA3", "SAE" -> WPA3
        "WPA3_TRANSITION", "WPA2_WPA3" -> WPA3_TRANSITION
        "EAP", "WPA2_EAP", "WPA_EAP" -> EAP
        "EAP_WPA3", "WPA3_EAP" -> EAP_WPA3
        else -> WPA
    }

    fun isEnterprise(security: String) = security == EAP || security == EAP_WPA3

    /** First Android API level whose WifiManager can save a network of this security. */
    fun minSdk(security: String): Int = when (security) {
        WPA3, OWE -> 29
        EAP_WPA3 -> 31
        else -> 23
    }

    /**
     * Android's own ranking (DevicePolicyManager.WIFI_SECURITY_*): 0 open, 1 personal, 2 enterprise EAP,
     * 3 enterprise 192-bit. Used to refuse a "minimum security" none of the managed networks reaches.
     */
    fun level(security: String): Int = when (security) {
        NONE, OWE -> 0
        WEP, WPA, WPA3, WPA3_TRANSITION -> 1
        else -> 2
    }

    fun levelOf(minSecurity: String?): Int = when (minSecurity?.trim()?.lowercase()) {
        "personal" -> 1
        "enterprise" -> 2
        "enterprise192" -> 3
        else -> 0
    }
}

object WifiSupport {
    private val EAP_METHODS = setOf("PEAP", "TTLS", "TLS")

    /**
     * Why this device cannot save the network at all (null = it can). [wpa3]/[owe] are what the Wi-Fi chip reports
     * (WifiManager.isWpa3SaeSupported / isEnhancedOpenSupported); null = unknown, assume yes.
     */
    @Suppress("ReturnCount")
    fun unsupported(net: ConfigWifi, sdk: Int, wpa3: Boolean? = null, owe: Boolean? = null): String? {
        val sec = WifiSecurity.normalize(net.security)
        val min = WifiSecurity.minSdk(sec)
        if (sdk < min) return "$sec needs Android API $min (this is $sdk)"
        if (sec == WifiSecurity.WPA3 && wpa3 == false) return "the Wi-Fi chip does not support WPA3-SAE"
        if (sec == WifiSecurity.OWE && owe == false) return "the Wi-Fi chip does not support Enhanced Open (OWE)"
        if (WifiSecurity.isEnterprise(sec)) {
            val eap = net.eap ?: return "enterprise network without EAP parameters"
            val method = eap.method.trim().uppercase()
            if (method !in EAP_METHODS) return "EAP method ${eap.method} is not supported"
            if (method == "TLS" && (eap.clientKey.isNullOrBlank() || eap.clientChain.isNullOrEmpty())) return "EAP-TLS without a client certificate"
        }
        return null
    }

    /** Options no API of this Android version can honour (the app module adds the ones it fails to set at runtime). */
    fun unsupportedOptions(net: ConfigWifi, sdk: Int): List<String> = buildList {
        if (net.proxy != null && sdk < 26) add("proxy")
        if (net.mac?.lowercase() == "random" && sdk < 29) add("mac")
        if (net.metered != null && sdk < 28) add("metered")
    }
}

object WifiScanMatch {
    /** Does an access point advertising [capabilities] (ScanResult.capabilities) accept a network of [security]? */
    fun matches(security: String, capabilities: String): Boolean {
        val c = capabilities.uppercase()
        val eap = c.contains("EAP")
        val psk = c.contains("PSK")
        val sae = c.contains("SAE")
        val owe = c.contains("OWE")
        val wep = c.contains("WEP")
        return when (WifiSecurity.normalize(security)) {
            WifiSecurity.NONE -> !eap && !psk && !sae && !wep && (!owe || c.contains("OWE_TRANSITION"))
            WifiSecurity.OWE -> owe
            WifiSecurity.WEP -> wep
            WifiSecurity.WPA -> psk
            WifiSecurity.WPA3 -> sae
            WifiSecurity.WPA3_TRANSITION -> psk || sae
            else -> eap
        }
    }
}

/** What the supplicant said the last time it refused a managed network. */
enum class WifiAuthError { WRONG_PASSWORD, EAP_FAILURE, TIMEOUT, OTHER }

object WifiDiagnosis {
    const val PASSWORD = "password"
    const val CERTIFICATE = "certificate"
    const val CREDENTIALS = "credentials"
    const val OUT_OF_RANGE = "out_of_range"
    const val SECURITY_MISMATCH = "security_mismatch"
    const val UNSUPPORTED = "unsupported"
    const val SAVE_FAILED = "save_failed"
    const val DISABLED = "disabled"
    const val RADIO_OFF = "radio_off"

    /** Everything known about one managed network when the report is built. */
    data class Facts(
        val configured: Boolean,
        val connected: Boolean = false,
        val unsupported: Boolean = false,
        val certificateProblem: Boolean = false,
        val radioOn: Boolean = true,
        /** null = unknown (hidden network, no scan yet, no location permission). */
        val inRange: Boolean? = null,
        /** null = not seen in a scan. */
        val securityMatches: Boolean? = null,
        val authError: WifiAuthError? = null,
    )

    /**
     * Why a managed network is not connected, most actionable cause first; null when it is connected or simply idle
     * (saved, in range, no error — e.g. the phone is on another managed network).
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun reason(net: ConfigWifi, f: Facts): String? {
        if (f.unsupported) return UNSUPPORTED
        if (f.certificateProblem) return CERTIFICATE
        if (!f.configured) return SAVE_FAILED
        if (f.connected) return null
        val sec = WifiSecurity.normalize(net.security)
        val enterprise = WifiSecurity.isEnterprise(sec)
        val keyed = !enterprise && sec != WifiSecurity.NONE && sec != WifiSecurity.OWE
        when (f.authError) {
            WifiAuthError.WRONG_PASSWORD -> return if (enterprise) CREDENTIALS else PASSWORD
            WifiAuthError.EAP_FAILURE -> return if (net.eap?.method?.trim()?.uppercase() == "TLS") CERTIFICATE else CREDENTIALS
            // A timeout on a PSK/SAE handshake is how most access points answer a wrong key.
            WifiAuthError.TIMEOUT, WifiAuthError.OTHER -> if (keyed) return PASSWORD
            null -> Unit
        }
        if (!f.radioOn) return RADIO_OFF
        if (f.inRange == false) return OUT_OF_RANGE
        if (f.inRange == true && f.securityMatches == false) return SECURITY_MISMATCH
        if (net.autoConnect == false) return DISABLED
        return null
    }
}

object WifiRulesPlan {
    const val DEFAULT_SAFEGUARD_MINUTES = 10

    /** Rules that can leave a phone without any network if they are wrong — the ones the safeguard watches. */
    fun isRestrictive(rules: ConfigWifiRules?, radio: Boolean?): Boolean {
        if (radio == false) return true
        if (rules == null) return false
        return rules.blockAdd == true || rules.blockConfig == true || rules.blockState == true ||
            !rules.ssidMode.isNullOrBlank() || WifiSecurity.levelOf(rules.minSecurity) > 0
    }

    /** `allow` mode always includes the managed networks: "only corporate networks" can never exclude its own. */
    fun allowedSsids(rules: ConfigWifiRules, managed: List<ConfigWifi>): Set<String> =
        (rules.ssids.orEmpty() + managed.map { it.ssid }).map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

    /** `deny` mode never refuses a managed network. */
    fun deniedSsids(rules: ConfigWifiRules, managed: List<ConfigWifi>): Set<String> {
        val own = managed.map { it.ssid.trim() }.toSet()
        return rules.ssids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() && it !in own }.toCollection(LinkedHashSet())
    }

    /**
     * The minimum security actually enforced: never above the strongest managed network (else the phone could join
     * none of them). Returns the Android level 0..3.
     */
    fun effectiveMinSecurity(rules: ConfigWifiRules, managed: List<ConfigWifi>): Int {
        val wanted = WifiSecurity.levelOf(rules.minSecurity)
        if (managed.isEmpty()) return wanted
        val best = managed.maxOf { WifiSecurity.level(WifiSecurity.normalize(it.security)) }
        return minOf(wanted, best)
    }

    fun safeguardMinutes(rules: ConfigWifiRules?): Int = (rules?.safeguardMinutes ?: DEFAULT_SAFEGUARD_MINUTES).coerceIn(1, 120)
}

/**
 * "Never leave a phone without connectivity because of a wrong policy". While a restrictive Wi-Fi rule is in force the
 * agent calls [tick] every minute or so: with no usable network (Wi-Fi or mobile data) for the grace period it answers
 * [Action.RELAX] — the app lifts the lock-down and reports it — and once the relax period is over it answers
 * [Action.RESTORE] so the lock-down comes back and is watched again. A new policy starts from scratch ([reset]).
 */
class WifiSafeguard(private val store: Store) {
    enum class Action { NONE, RELAX, RESTORE }

    data class State(val offlineSince: Long? = null, val relaxedAt: Long? = null, val relaxedUntil: Long? = null) {
        val relaxed: Boolean get() = relaxedUntil != null
    }

    interface Store {
        fun load(): State
        fun save(state: State)
    }

    fun state(): State = store.load()

    fun reset() = store.save(State())

    @Suppress("ReturnCount")
    fun tick(now: Long, online: Boolean, restrictive: Boolean, graceMs: Long, relaxMs: Long = relaxFor(graceMs)): Action {
        val s = store.load()
        if (!restrictive) {
            if (s != State()) store.save(State())
            return if (s.relaxed) Action.RESTORE else Action.NONE
        }
        val until = s.relaxedUntil
        if (until != null) {
            if (now < until) return Action.NONE
            store.save(State())
            return Action.RESTORE
        }
        if (online) {
            if (s.offlineSince != null) store.save(State())
            return Action.NONE
        }
        val since = s.offlineSince ?: now.also { store.save(State(offlineSince = it)) }
        if (now - since < graceMs) return Action.NONE
        store.save(State(offlineSince = since, relaxedAt = now, relaxedUntil = now + relaxMs))
        return Action.RELAX
    }

    companion object {
        private const val MIN_RELAX_MS = 30 * 60_000L
        private const val RELAX_FACTOR = 6

        /** The lock-down stays lifted long enough for someone to fix the policy: 6x the grace, at least 30 minutes. */
        fun relaxFor(graceMs: Long): Long = maxOf(MIN_RELAX_MS, graceMs * RELAX_FACTOR)
    }
}

/** Which managed network the phone should be on: the highest [ConfigWifi.priority] one in range that may auto-join. */
object WifiPriority {
    /** @return the SSID to move to, or null to stay put. */
    fun preferred(managed: List<ConfigWifi>, inRange: Set<String>, connected: String?): String? {
        val candidates = managed.filter { it.autoConnect != false && it.ssid in inRange }
        if (candidates.isEmpty() || candidates.all { it.priority == null }) return null
        // The first of the highest priority wins (list order breaks ties).
        val best = candidates.maxByOrNull { (it.priority ?: 0) * managed.size - managed.indexOf(it) } ?: return null
        val current = managed.firstOrNull { it.ssid == connected }
        // Move only to a strictly better managed network, or onto one when the phone is on none of them.
        val better = current == null || (best.priority ?: 0) > (current.priority ?: 0)
        return if (better && best.ssid != connected) best.ssid else null
    }
}
