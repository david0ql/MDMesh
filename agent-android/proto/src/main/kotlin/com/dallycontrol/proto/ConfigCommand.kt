package com.dallycontrol.proto

import kotlinx.serialization.Serializable

/**
 * Payload of `config.apply` (proto/payloads/config-apply.schema.json): the server-computed desired state of the
 * device's configuration. Every field defaults so a newer server can add keys freely.
 *
 * @property revision sha256 of the canonical document; reported back as `appliedConfigRevision` once applied.
 * @property policies only the keys the configuration manages (true = allowed/enabled).
 * @property kiosk present = ensure kiosk with this payload; absent = the configuration does not assert kiosk
 *   (exit only if the previously applied configuration did — see ConfigApplier).
 * @property location capture cadence, see [DeviceAction.LOCATION_MODE].
 * @property browser managed-browser site lists (absent = not managed by the configuration).
 * @property apps which apps may be installed and used (absent = not managed).
 * @property tracking periodic location trail (absent = only the check-in's fix).
 */
@Serializable
data class ConfigApplyPayload(
    val revision: String = "",
    val configurationId: Int? = null,
    val policies: Map<String, Boolean> = emptyMap(),
    val kiosk: KioskApplyPayload? = null,
    val location: ConfigLocation? = null,
    val browser: ConfigBrowser? = null,
    val apps: ConfigAppPolicy? = null,
    val tracking: ConfigTracking? = null,
    /** Wi-Fi networks to keep saved (absent = not managed; empty = remove the ones added before). */
    val wifi: List<ConfigWifi>? = null,
    /** Android system-update policy (absent = device default). */
    val systemUpdate: ConfigSystemUpdate? = null,
    /** Device security rules (absent = none): data sharing, Google accounts, factory reset. */
    val device: ConfigDevice? = null,
    /** Mobile-data restriction per app and data limits (absent = not managed; what the agent set before is undone). */
    val mobileData: ConfigMobileData? = null,
    /** Hardware and ports rules (absent = not managed; what an earlier document set is then undone). */
    val hardware: ConfigHardware? = null,
    /**
     * Wi-Fi networks with a security older agents cannot save (WPA3, OWE, Enterprise). Kept apart from [wifi] so an
     * agent that predates them simply ignores the list instead of saving a broken network. Managed with [wifi].
     */
    val wifiAdvanced: List<ConfigWifi>? = null,
    /** Wi-Fi lock-down rules (absent = none): what the user may change and which networks the phone may join. */
    val wifiRules: ConfigWifiRules? = null,
)

/**
 * [tethering]: `allow` | `block`. [googleAccounts]: `block` = no Google account can be added. [accountDomains]: only
 * Google accounts of these domains stay on the phone ([accountDomain]: the one-domain form older servers send). [factoryReset]: `block` = not from Settings. [frpAccounts]: Google
 * account ids allowed to set the phone up again after a reset from recovery (Android 11+).
 */
@Serializable
data class ConfigDevice(
    val tethering: String? = null,
    val googleAccounts: String? = null,
    val accountDomain: String? = null,
    val accountDomains: List<String>? = null,
    val factoryReset: String? = null,
    val frpAccounts: List<String>? = null,
    /** "block" = the user cannot install apps (DISALLOW_INSTALL_APPS); the MDM's silent installs still work. */
    val installApps: String? = null,
)

/** [type]: `automatic`, `windowed` ([fromMinutes]..[toMinutes] of the day) or `postpone`. */
@Serializable
data class ConfigSystemUpdate(
    val type: String,
    val fromMinutes: Int? = null,
    val toMinutes: Int? = null,
)

/**
 * A Wi-Fi network the configuration saves on the device. [security]: `NONE`, `OWE`, `WEP`, `WPA` (WPA/WPA2-PSK),
 * `WPA3` (SAE), `WPA3_TRANSITION` (WPA2/WPA3 mixed), `EAP` (WPA/WPA2-Enterprise) or `EAP_WPA3` (WPA3-Enterprise).
 * Every option is optional, so a policy written for an older agent still applies.
 *
 * @property autoConnect false = saved but never joined on its own. @property priority higher wins when several
 *   managed networks are in range. @property mac `random` or `device`. @property metered `metered` / `unmetered`.
 */
@Serializable
data class ConfigWifi(
    val ssid: String,
    val password: String? = null,
    val security: String = "WPA",
    val hidden: Boolean? = null,
    val autoConnect: Boolean? = null,
    val priority: Int? = null,
    val proxy: ConfigWifiProxy? = null,
    val ip: ConfigWifiIp? = null,
    val mac: String? = null,
    val metered: String? = null,
    val eap: ConfigWifiEap? = null,
)

/** [mode] `manual` ([host]:[port], [exclude] hosts) or `pac` ([pacUrl]). */
@Serializable
data class ConfigWifiProxy(
    val mode: String = "manual",
    val host: String? = null,
    val port: Int? = null,
    val exclude: List<String>? = null,
    val pacUrl: String? = null,
)

/** A static IPv4 configuration (present = static, absent = DHCP). */
@Serializable
data class ConfigWifiIp(
    val address: String,
    val prefix: Int = 24,
    val gateway: String? = null,
    val dns: List<String>? = null,
)

/**
 * Enterprise (802.1X) parameters. [method]: `PEAP`, `TTLS` or `TLS`; [phase2]: `MSCHAPV2`, `PAP`, `GTC` or absent.
 * [caCerts]: the server's CA chain, each one base64 DER. [clientKey]: the client's private key (base64 PKCS#8, of
 * [clientKeyAlgorithm] `RSA` or `EC`) and [clientChain] its certificate chain (base64 DER, leaf first), for `TLS` —
 * the server opens the uploaded PKCS#12, so the phone never needs its password. [domain] is the server certificate's
 * domain suffix; [altSubjectMatch] the alternative-subject match — one of them plus a CA is what makes the connection
 * safe against a rogue access point.
 */
@Serializable
data class ConfigWifiEap(
    val method: String = "PEAP",
    val phase2: String? = null,
    val identity: String? = null,
    val anonymousIdentity: String? = null,
    val password: String? = null,
    val domain: String? = null,
    val altSubjectMatch: String? = null,
    val caCerts: List<String>? = null,
    val clientKey: String? = null,
    val clientKeyAlgorithm: String? = null,
    val clientChain: List<String>? = null,
)

/**
 * Wi-Fi lock-down. [lockManaged]: the user cannot edit or forget the managed networks. [blockAdd]: no new networks
 * (Android 13+). [blockConfig]: no Wi-Fi settings at all. [blockState]: the user cannot switch Wi-Fi on/off (Android
 * 13+). [ssidMode] `allow` (only [ssids] plus the managed networks) or `deny` ([ssids] are refused) — Android 13+.
 * [minSecurity]: `personal`, `enterprise` or `enterprise192` (Android 13+). [safeguardMinutes]: with no usable network
 * for this long after a lock-down, the agent relaxes it and reports it (see WifiSafeguard).
 */
@Serializable
data class ConfigWifiRules(
    val lockManaged: Boolean? = null,
    val blockAdd: Boolean? = null,
    val blockConfig: Boolean? = null,
    val blockState: Boolean? = null,
    val ssidMode: String? = null,
    val ssids: List<String>? = null,
    val minSecurity: String? = null,
    val safeguardMinutes: Int? = null,
)

/**
 * Managed browser (Chrome's managed configuration). [mode]: `open` (no list), `allowlist` (only [allow]),
 * `blocklist` (everything but [block]). Entries use Chrome's URL-filter format (`example.com`, `*.gov.co`, …).
 */
@Serializable
data class ConfigBrowser(
    val mode: String = "open",
    val allow: List<String> = emptyList(),
    val block: List<String> = emptyList(),
    /** Company page: Chrome's home page, and every search typed in the address bar goes there. */
    val homeUrl: String? = null,
)

/**
 * App policy. [mode] `open` (anything) or `allowlist`: user-installed apps outside [allowed], the resolved
 * [roles] and the configuration's own apps are suspended (they cannot be opened) until allowed.
 * [hidePlayStore] hides the Play Store app.
 */
@Serializable
data class ConfigAppPolicy(
    val mode: String = "open",
    val allowed: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val hidePlayStore: Boolean = false,
)

/** Location trail: a fresh fix every [intervalMinutes], buffered and uploaded with the next check-in. */
@Serializable
data class ConfigTracking(val intervalMinutes: Int = 0)

@Serializable
data class ConfigLocation(
    val mode: String = DeviceAction.LOCATION_PASSIVE,
    /** Seconds an active fix may wait for a fresh position (absent = the agent's default, 25 s). */
    val fixTimeoutSec: Int? = null,
)

/** JSON-encoded into `CommandResult.detail` (proto/payloads/config-apply-result.schema.json). */
@Serializable
data class ConfigApplyResult(
    val revision: String,
    /** keys: `policies.<key>`, `kiosk`, `location` → [ConfigOutcome] strings. */
    val outcomes: Map<String, String>,
)

object ConfigOutcome {
    const val APPLIED = "applied"
    const val UNSUPPORTED = "unsupported"
    fun failed(reason: String): String = "failed: $reason"
    fun isFailed(outcome: String): Boolean = outcome.startsWith("failed")
    fun isUnsupported(outcome: String): Boolean = outcome.startsWith(UNSUPPORTED)
}

/**
 * Mobile data per app. [mode]: `open` (no list), `blocklist` ([apps] may not use mobile data: "Wi-Fi only") or
 * `allowlist` (only [apps] may; every other user-facing app is Wi-Fi only). [mechanism]: `auto` (the system's
 * metered-data restriction on Android 9+, the agent's local VPN before), `dpm` or `vpn` to force one.
 *
 * Limits are counted per period ([period] `monthly` starting on [cutDay] 1..28, or `daily`): an app over its
 * [appLimits] entry loses mobile data until the next period; over [deviceLimitBytes] every app but [essential] does.
 * At [warnPercent] of a limit the agent records a warning event. The agent itself is never restricted.
 */
@Serializable
data class ConfigMobileData(
    val mode: String = "open",
    val apps: List<String> = emptyList(),
    val mechanism: String = "auto",
    val period: String = "monthly",
    val cutDay: Int = 1,
    val warnPercent: Int = 80,
    val deviceLimitBytes: Long? = null,
    val appLimits: List<ConfigDataLimit> = emptyList(),
    val essential: List<String> = emptyList(),
)

@Serializable
data class ConfigDataLimit(val pkg: String, val bytes: Long)

/**
 * Hardware and ports. [rules]: rule key (camera, usbDebug, usbData, nfc, …) → `allow` | `block`. [restrictions]: extra
 * `UserManager.DISALLOW_*` values (`no_sms`, …) from the server's validated list. Reported per item in
 * [ConfigApplyResult.outcomes] as `hardware.<rule>` / `restrictions.<key>`: `applied`, `unsupported: <why>` or
 * `failed: <why>`.
 */
@Serializable
data class ConfigHardware(
    val rules: Map<String, String> = emptyMap(),
    val restrictions: List<String> = emptyList(),
)
