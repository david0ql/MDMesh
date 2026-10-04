package com.dallycontrol.proto

/**
 * Open registry of device-action command types. By convention the action's command [type] is
 * identical to its capability token and to the server-side `requiresCapability` string, so there
 * is exactly one string per action and no drift between agent, server, and UI.
 *
 * The agent advertises [ADVERTISED_KEYS] in `capabilities.device`; the server flattens each into a
 * `device.<key>` token (see `AgentCapabilityTokens`).
 */
object DeviceAction {
    const val LOCK = "device.lock"
    const val REBOOT = "device.reboot"
    const val LOCKSCREEN_MESSAGE = "device.lockscreenMessage"
    const val ALERT = "device.alert"
    const val RING = "device.ring"
    const val RING_STOP = "device.ringStop"
    /** A diagnosis snapshot (sounds, notifications, alarms, volumes, kiosk…) as JSON in the result. */
    const val DIAGNOSE = "device.diagnose"
    /** Press a notification's button / open it / dismiss it: `{key, action}`. */
    const val NOTIFICATION_ACTION = "device.notificationAction"
    /** Silence every volume for `{minutes}`. */
    const val SILENCE = "device.silence"
    const val PASSCODE_RESET = "device.passcodeReset"
    const val WIPE = "device.wipe"

    /** Open an installed app in the foreground. Payload: `{ "packageName": "…" }`. */
    const val APP_LAUNCH = "device.appLaunch"

    /** Open an app's Play Store page so the person taps Install. Payload: `{ "packageName": "…" }`. */
    const val OPEN_STORE = "device.openStore"

    /** Android update policy now: `{ "type": "automatic" | "windowed" | "postpone" | "default", fromMinutes?, toMinutes? }`. */
    const val SYSTEM_UPDATE = "device.systemUpdate"

    /** Ask the person to clear every app's cache (Android 11+; opens Android's confirmation on the phone). */
    const val CLEAR_CACHE = "device.clearCache"

    /** Show an announcement in the app (text, image or video; mandatory or optional), or withdraw one. */
    const val ANNOUNCE = "device.announce"

    /** Storage: scan (what takes the space), clean (`{clearData:[pkg], deleteFiles:[id]}`), access (`{kind}`). */
    const val STORAGE_SCAN = "device.storageScan"
    const val STORAGE_CLEAN = "device.storageClean"
    const val STORAGE_ACCESS = "device.storageAccess"

    /** Set the agent's connectivity power mode. Payload: `{ "mode": "adaptive" | "alwaysOn" }`. */
    const val POWER_MODE = "device.powerMode"

    /** Connectivity power-mode values (see [POWER_MODE]). */
    const val POWER_ADAPTIVE = "adaptive"
    const val POWER_ALWAYS_ON = "alwaysOn"

    /** Set how location is captured. Payload: `{ "mode": "passive" | "active" }`. */
    const val LOCATION_MODE = "device.locationMode"

    /** Location-mode values (see [LOCATION_MODE]). Passive = last-known (cheap); active = fresh fix. */
    const val LOCATION_PASSIVE = "passive"
    const val LOCATION_ACTIVE = "active"

    /** Desired-state document push (see ConfigApplyPayload). Advertised as device key [CONFIG_APPLY_KEY]. */
    const val CONFIG_APPLY = "config.apply"
    const val CONFIG_APPLY_KEY = "configApply"

    /** Keys (after the `device.` prefix) advertised in `capabilities.device`. */
    val ADVERTISED_KEYS: List<String> = listOf(
        "lock", "reboot", "lockscreenMessage", "alert", "ring", "ringStop",
        "passcodeReset", "wipe", "powerMode", "locationMode", "configApply", "appLaunch", "openStore",
        "storageScan", "storageClean", "storageAccess", "announce", "systemUpdate", "clearCache",
        "diagnose", "notificationAction", "silence",
    )
}
