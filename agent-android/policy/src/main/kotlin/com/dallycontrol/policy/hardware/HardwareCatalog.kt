package com.dallycontrol.policy.hardware

/**
 * The "hardware and ports" rules a policy can set (`config.apply` → `hardware.rules`, value `allow` | `block`) and
 * the Android mechanism behind each one. Pure (no Android imports): restriction keys are the literal values of the
 * `UserManager.DISALLOW_*` constants and API levels are plain ints, so the whole decision is unit-testable on the JVM.
 *
 * Every rule states the first Android API level that honours it ([HardwareRule.minSdk]); below it the agent reports
 * `unsupported: sdk-<level>` instead of pretending.
 */
@Suppress("MagicNumber") // the numbers are Android API levels: they are the data of this table
object HardwareCatalog {

    /** Pseudo-tokens for rules that are not a user restriction. */
    const val TOKEN_CAMERA = "@camera"
    const val TOKEN_USB_DATA = "@usbData"
    const val TOKEN_AUTO_TIME = "@autoTime"

    const val ALLOW = "allow"
    const val BLOCK = "block"

    /** A `UserManager.DISALLOW_*` key (or pseudo-token) and the first API level where it exists. */
    data class Restriction(val key: String, val minSdk: Int)

    /**
     * @property tokens what is switched on when the rule blocks. Those newer than the device are skipped; the rule
     *   itself needs [minSdk].
     */
    data class HardwareRule(val key: String, val minSdk: Int, val tokens: List<Restriction>)

    private fun rule(key: String, minSdk: Int, vararg tokens: Restriction) = HardwareRule(key, minSdk, tokens.toList())
    private fun r(key: String, minSdk: Int) = Restriction(key, minSdk)

    val RULES: List<HardwareRule> = listOf(
        // DevicePolicyManager.setCameraDisabled
        rule("camera", 21, r(TOKEN_CAMERA, 21)),
        // DISALLOW_DEBUGGING_FEATURES: ADB (USB and wireless debugging) and the developer options
        rule("usbDebug", 21, r("no_debugging_features", 21)),
        // DevicePolicyManager.setUsbDataSignalingEnabled(false): every USB data function; charging keeps working
        rule("usbData", 31, r(TOKEN_USB_DATA, 31)),
        // DISALLOW_MOUNT_PHYSICAL_MEDIA: SD card / USB-OTG drives
        rule("physicalMedia", 21, r("no_physical_media", 21)),
        rule("bluetoothSharing", 26, r("no_bluetooth_sharing", 26)),
        rule("bluetoothConfig", 21, r("no_config_bluetooth", 21)),
        // DISALLOW_OUTGOING_BEAM (NFC beam; Android removed Beam in 14, the restriction is still accepted)
        rule("nfcBeam", 22, r("no_outgoing_beam", 22)),
        // DISALLOW_NEAR_FIELD_COMMUNICATION_RADIO: the NFC radio itself
        rule("nfc", 35, r("no_near_field_communication_radio", 35)),
        rule("microphone", 21, r("no_unmute_microphone", 21)),
        rule("airplaneMode", 28, r("no_airplane_mode", 28)),
        rule("wifiDirect", 33, r("no_wifi_direct", 33)),
        rule("printing", 28, r("no_printing", 28)),
        rule("networkReset", 23, r("no_network_reset", 23)),
        // DISALLOW_CONFIG_DATE_TIME from Android 9; before that setAutoTimeRequired (network time, not editable)
        rule("dateTime", 21, r("no_config_date_time", 28), r(TOKEN_AUTO_TIME, 21)),
        rule("unknownSources", 21, r("no_install_unknown_sources", 21), r("no_install_unknown_sources_globally", 29)),
        // DISALLOW_CONFIG_LOCATION: the user cannot switch location on/off
        rule("locationConfig", 28, r("no_config_location", 28)),
        // DISALLOW_SHARE_LOCATION: location is off for every app (also for the MDM's own tracking)
        rule("locationSharing", 21, r("no_share_location", 21)),
        rule("contentCapture", 29, r("no_content_capture", 29), r("no_content_suggestions", 29)),
        // DISALLOW_ASSIST_CONTENT: the assistant cannot read the screen
        rule("assistContent", 35, r("no_assist_content", 35)),
    )

    private val BY_KEY: Map<String, HardwareRule> = RULES.associateBy { it.key }

    fun rule(key: String): HardwareRule? = BY_KEY[key]

    /** What [rule] switches on this device: the auto-time fallback only where the restriction does not exist yet. */
    fun tokensFor(rule: HardwareRule, sdk: Int): List<String> {
        val t = rule.tokens.filter { sdk >= it.minSdk }.map { it.key }
        return if (rule.key == "dateTime" && sdk >= 28) t - TOKEN_AUTO_TIME else t
    }

    /**
     * The free "Restricciones de Android" list: `UserManager.DISALLOW_*` values an administrator may add by name, with
     * the first API level of each. Anything else is refused. Restrictions that have their own option in the console
     * (tethering, factory reset, installing apps, Bluetooth, USB storage and every rule above) are deliberately absent.
     */
    val FREE_RESTRICTIONS: Map<String, Int> = mapOf(
        "no_sms" to 21,
        "no_outgoing_calls" to 21,
        "no_config_wifi" to 21,
        "no_config_mobile_networks" to 21,
        "no_config_vpn" to 21,
        "no_config_cell_broadcasts" to 21,
        "no_config_credentials" to 21,
        "no_modify_accounts" to 21,
        "no_add_user" to 21,
        "no_remove_user" to 21,
        "no_uninstall_apps" to 21,
        "no_control_apps" to 21,
        "no_adjust_volume" to 21,
        "no_create_windows" to 21,
        "no_cross_profile_copy_paste" to 21,
        "no_safe_boot" to 23,
        "no_fun" to 23,
        "no_data_roaming" to 24,
        "no_set_wallpaper" to 24,
        "no_set_user_icon" to 24,
        "no_autofill" to 26,
        "no_user_switch" to 28,
        "no_system_error_dialogs" to 28,
        "no_config_locale" to 28,
        "no_config_brightness" to 28,
        "no_config_screen_timeout" to 28,
        "no_ambient_display" to 28,
        "no_config_private_dns" to 29,
        "no_add_wifi_config" to 33,
        "no_change_wifi_state" to 33,
        "no_wifi_tethering" to 33,
        "no_sharing_admin_configured_wifi" to 33,
        "no_cellular_2g" to 34,
        "no_ultra_wideband_radio" to 34,
        "no_config_default_apps" to 34,
    )

    /**
     * Restrictions other sections of the same document keep on, so this section must not clear them: the USB-storage
     * and Bluetooth toggles and the device rules (tethering, factory reset, installing apps).
     */
    fun heldByOtherSections(
        policies: Map<String, Boolean>,
        tethering: String?,
        factoryReset: String?,
        installApps: String?,
    ): Set<String> = buildSet {
        if (policies["usbStorage"] == false) { add("no_usb_file_transfer"); add("no_physical_media") }
        if (policies["bluetooth"] == false) add("no_bluetooth")
        if (tethering == BLOCK) add("no_config_tethering")
        if (factoryReset == BLOCK) add("no_factory_reset")
        if (installApps == BLOCK) { add("no_install_apps"); add("no_install_unknown_sources") }
    }
}
