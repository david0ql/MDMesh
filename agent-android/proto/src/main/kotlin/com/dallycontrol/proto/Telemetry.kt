package com.dallycontrol.proto

import kotlinx.serialization.Serializable

/** Full device census reported on check-in. All sub-objects except [dynamic] are nullable: a
 *  collector that lacks its permission (or fails) contributes null, so the snapshot is always valid. */
@Serializable
data class TelemetrySnapshot(
    val dynamic: DynamicState,
    val hardware: HardwareInfo? = null,
    val identity: IdentityInfo? = null,
    val security: SecurityPosture? = null,
)

@Serializable
data class HardwareInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val osRelease: String,
    val sdkInt: Int,
    val securityPatch: String?,
    val buildFingerprint: String,
    val abis: List<String>,
    val totalRamBytes: Long,
    val totalStorageBytes: Long,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
)

/** Sensitive: identity ATTRIBUTES only — the device identity remains the server-issued id. */
@Serializable
data class IdentityInfo(
    val serial: String? = null,
    val imei: List<String> = emptyList(),
    val imsi: List<String> = emptyList(),
    val iccid: List<String> = emptyList(),
    val phoneNumber: List<String> = emptyList(),
    /** Hardware Wi-Fi MAC (Device Owner only; the per-network random MAC is not it). */
    val wifiMac: String? = null,
)

@Serializable
data class DynamicState(
    val batteryPct: Int,
    val batteryHealth: String,
    val batteryTempC: Double,
    val batteryVoltageMv: Int,
    val chargingSource: String, // none | ac | usb | wireless
    val freeStorageBytes: Long,
    val freeRamBytes: Long,
    val networkType: String, // wifi | cellular | none
    val wifiSsid: String? = null,
    val wifiBssid: String? = null,
    val ipAddress: String? = null,
    val wifiRssi: Int? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val cellularOperator: String? = null,
    val cellularSignalLevel: Int? = null,
    val roaming: Boolean = false,
    val screenOn: Boolean,
    val locked: Boolean,
    val kioskActive: Boolean,
    val foregroundApp: String? = null,
    val uptimeMs: Long,
    val lastBootAt: Long,
    /** Last-known (or freshly-fixed, in active mode) device location; null without permission/fix. */
    val location: LocationDto? = null,
    /** SIM card(s) as seen now; null when the agent cannot read phone state. */
    val sim: SimStateDto? = null,
    /** Fixes captured between check-ins by the periodic trail (ConfigTracking), oldest first. */
    val trail: List<LocationDto> = emptyList(),
    /** A system (OTA) update is waiting to be installed since this time (Device Owner; null = none known). */
    val systemUpdatePendingSince: Long? = null,
    /** Per-app usage of today and yesterday (sent every few minutes, not on every check-in); null = not this time. */
    val appUsage: AppUsageReport? = null,
    /** Android's "usage access" is on for the agent (needed for per-app screen time and the battery estimate). */
    val usageAccess: Boolean? = null,
    /** Mobile-data restriction as enforced right now (null = the configuration does not manage it). */
    val mobileData: MobileDataStatus? = null,
    /** The policy's Wi-Fi networks and lock-down as they stand on the phone; null = the policy manages no Wi-Fi. */
    val managedWifi: ManagedWifiReport? = null,
)

/**
 * The mobile-data policy as the device enforces it. [mechanism]: `dpm` (system metered-data restriction), `vpn`
 * (the agent's local VPN) or `none`. [vpnActive]: the VPN tunnel is up now (it is only up on a mobile network).
 * [blocked]: packages without mobile data and why (`policy`, `appLimit`, `deviceLimit`). [problem]: why the policy
 * could not be enforced as asked (another always-on VPN, not Device Owner…), null when all is well.
 */
@Serializable
data class MobileDataStatus(
    val mode: String,
    val mechanism: String,
    val vpnActive: Boolean? = null,
    val blocked: List<MobileDataBlocked> = emptyList(),
    val blockedCount: Int = 0,
    val period: String = "monthly",
    val periodStart: Long = 0,
    val periodEnd: Long = 0,
    val deviceBytes: Long = 0,
    val deviceLimitBytes: Long? = null,
    val warnPercent: Int = 80,
    val apps: List<MobileDataAppUsage> = emptyList(),
    val problem: String? = null,
    val evaluatedAt: Long = 0,
)

@Serializable
data class MobileDataBlocked(val pkg: String, val reason: String)

@Serializable
data class MobileDataAppUsage(
    val pkg: String,
    val label: String? = null,
    val bytes: Long = 0,
    val limitBytes: Long? = null,
)

/** State of the policy's Wi-Fi on the device, as the agent sees it at [checkedAt] (epoch ms). */
@Serializable
data class ManagedWifiReport(
    val networks: List<ManagedWifiStatus> = emptyList(),
    val rules: ManagedWifiRulesStatus? = null,
    val checkedAt: Long = 0,
)

/**
 * One managed network. [reason] (absent = fine): `password`, `certificate`, `out_of_range`, `security_mismatch`,
 * `unsupported`, `save_failed`, `disabled`. [unsupportedOptions]: options this Android version cannot honour
 * (`staticIp`, `metered`, `mac`, `autoConnect`, `proxy`); the network is still saved without them.
 */
@Serializable
data class ManagedWifiStatus(
    val ssid: String,
    val security: String,
    val configured: Boolean,
    val connected: Boolean = false,
    val inRange: Boolean? = null,
    val reason: String? = null,
    val detail: String? = null,
    val unsupportedOptions: List<String> = emptyList(),
    /** `configuration` (saved as Device Owner) or `suggestion` (WifiNetworkSuggestion, not Device Owner). */
    val via: String? = null,
)

/** Which lock-down rules are in force; [unsupported] lists the ones this Android version lacks. */
@Serializable
data class ManagedWifiRulesStatus(
    val applied: List<String> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val relaxed: Boolean = false,
    val relaxedAt: Long? = null,
    val relaxedUntil: Long? = null,
    val relaxedReason: String? = null,
)

/**
 * What each app used, per local day: time on screen, data over Wi-Fi and mobile, and an ESTIMATE of the battery it
 * drained (the battery lost while that app was on screen; Android gives no per-app battery figure to a normal app).
 * [usageAccess] false = Android's "usage access" is off for the agent, so screen time and battery are missing.
 */
@Serializable
data class AppUsageReport(
    val usageAccess: Boolean,
    val days: List<AppUsageDay> = emptyList(),
)

@Serializable
data class AppUsageDay(
    /** Local date, `yyyy-MM-dd`. */
    val day: String,
    val apps: List<AppUsageEntry> = emptyList(),
)

@Serializable
data class AppUsageEntry(
    val packageName: String,
    val label: String? = null,
    val foregroundMs: Long = 0,
    val wifiBytes: Long = 0,
    val mobileBytes: Long = 0,
    /** Battery percentage points lost while this app was on screen (estimate). */
    val batteryPct: Double = 0.0,
)

/**
 * SIM state. [state]: `ready`, `absent`, `locked` (PIN/PUK), `unknown`. [slots] lists the active
 * subscriptions; [fingerprint] is a stable hash of them, so the server sees a swapped card as a change.
 */
@Serializable
data class SimStateDto(
    val state: String,
    val slots: List<SimSlotDto> = emptyList(),
    val fingerprint: String? = null,
)

@Serializable
data class SimSlotDto(
    val slot: Int,
    val carrier: String? = null,
    val number: String? = null,
    val countryIso: String? = null,
    val subscriptionId: Int? = null,
)

/** A device location fix. [capturedAt] is the fix's own timestamp (epoch millis), not report time. */
@Serializable
data class LocationDto(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val provider: String? = null,
    val capturedAt: Long,
)

@Serializable
data class SecurityPosture(
    val storageEncrypted: Boolean,
    val deviceSecure: Boolean,
    val adbEnabled: Boolean,
    val devOptionsEnabled: Boolean,
    val unknownSourcesAllowed: Boolean,
    val patchAgeDays: Int?,
    val isDeviceOwner: Boolean,
)
