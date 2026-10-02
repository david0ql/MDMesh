package com.dallycontrol.proto

import kotlinx.serialization.Serializable

/**
 * Payload of the `kiosk.enter` command (and the locally-persisted last-applied kiosk state).
 *
 * Every field defaults so old agents tolerate new keys and a minimal `{ }` payload is valid.
 *
 * @property mode `"single"` (pin one app) or `"launcher"` (show the allowed-apps home grid).
 * @property allowedPackages packages allowlisted for lock-task (the agent's own package is always added).
 * @property pinPackage in `single` mode, the app to launch + pin.
 * @property features lock-task UI feature toggles (see [com.dallycontrol.kiosk.lockTaskFeatures]).
 * @property exitMode `"gesture"` | `"visible"` | `"remote"` — how a technician leaves kiosk on device.
 * @property password admin password required by the on-device exit (gesture/visible).
 * @property theme launcher appearance.
 * @property roles device functions allowed besides [allowedPackages] (see [KioskRoles]): the agent resolves each
 *   to the packages that serve it on THIS device (its dialer + in-call screen, contacts app, …), so one
 *   configuration fits every brand.
 */
@Serializable
data class KioskApplyPayload(
    val mode: String = "launcher",
    val allowedPackages: List<String> = emptyList(),
    val pinPackage: String? = null,
    val features: KioskFeaturesDto = KioskFeaturesDto(),
    val exitMode: String = "gesture",
    val password: String? = null,
    val theme: KioskThemeDto = KioskThemeDto(),
    val roles: List<String> = emptyList(),
    /** Offer the agent's quick settings (brightness, volume, Wi-Fi, Bluetooth) in kiosk. */
    val quickSettings: Boolean = false,
)

/** Device functions a configuration can allow by name instead of by package (resolved on the device). */
object KioskRoles {
    const val PHONE = "phone"
    const val CONTACTS = "contacts"
    const val MESSAGES = "messages"
    const val BROWSER = "browser"
    const val CAMERA = "camera"
    const val MAPS = "maps"
    val ALL: List<String> = listOf(PHONE, CONTACTS, MESSAGES, BROWSER, CAMERA, MAPS)
}

@Serializable
data class KioskFeaturesDto(
    val home: Boolean? = null,
    val recents: Boolean? = null,
    val notifications: Boolean? = null,
    val systemInfo: Boolean? = null,
    val keyguard: Boolean? = null,
    val lockButtons: Boolean? = null,
)

@Serializable
data class KioskThemeDto(
    val backgroundColor: String? = null,
    val textColor: String? = null,
    val iconSize: String? = null,
    /** Branding: a logo above the apps, a logo below them, and the device serial at the bottom. */
    val logoUrl: String? = null,
    val footerLogoUrl: String? = null,
    val showSerial: Boolean? = null,
    /** A wallpaper behind the apps. */
    val backgroundUrl: String? = null,
    /** A support line: the kiosk shows a button that calls it directly ([supportLabel] names the button). */
    val supportPhone: String? = null,
    val supportLabel: String? = null,
)
