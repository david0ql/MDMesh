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
)

/** [type]: `automatic`, `windowed` ([fromMinutes]..[toMinutes] of the day) or `postpone`. */
@Serializable
data class ConfigSystemUpdate(
    val type: String,
    val fromMinutes: Int? = null,
    val toMinutes: Int? = null,
)

/** A Wi-Fi network the configuration saves on the device. [security]: `WPA`, `WEP` or `NONE`. */
@Serializable
data class ConfigWifi(
    val ssid: String,
    val password: String? = null,
    val security: String = "WPA",
    val hidden: Boolean? = null,
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
data class ConfigLocation(val mode: String = DeviceAction.LOCATION_PASSIVE)

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
}
