package com.dallycontrol.proto

import kotlinx.serialization.Serializable

/**
 * App permissions without the user (section `permissions` of `config.apply`). [defaultPolicy] is what Android
 * answers when ANY app asks for a runtime permission (`DevicePolicyManager.setPermissionPolicy`):
 * [PermissionRule.GRANT], [PermissionRule.PROMPT] or [PermissionRule.DENY]. [apps] fix individual permissions per
 * app (`setPermissionGrantState`), which also covers apps already installed and permissions already answered. Only
 * apps that carry a rule are listed; the agent reports those plus every app installed on top of the system image.
 */
@Serializable
data class ConfigPermissions(
    val defaultPolicy: String = PermissionRule.GRANT,
    val apps: List<ConfigAppPermissions> = emptyList(),
)

/**
 * [all]: the preset for every runtime permission the app declares. [permissions]: per group
 * ([PermissionCatalog.GROUPS] key to rule), wins over [all]. [special]: special accesses the app should have
 * ([PermissionCatalog.SPECIAL]); what Android lets a Device Owner do is done, the rest is reported as "needs a tap".
 */
@Serializable
data class ConfigAppPermissions(
    val packageName: String,
    val all: String? = null,
    val permissions: Map<String, String> = emptyMap(),
    val special: List<String> = emptyList(),
)

object PermissionRule {
    const val GRANT = "grant"
    const val DENY = "deny"
    const val PROMPT = "prompt"
    val ALL = setOf(GRANT, DENY, PROMPT)
}

/**
 * The vocabulary the console, the server and the agent share for app permissions: a GROUP key per row the console
 * shows, mapped to the Android runtime permissions it covers (the agent only touches the ones the app declares and
 * the Android version knows).
 */
object PermissionCatalog {
    const val LOCATION_FINE = "locationFine"
    const val LOCATION_COARSE = "locationCoarse"
    const val LOCATION_BACKGROUND = "locationBackground"
    const val CAMERA = "camera"
    const val MICROPHONE = "microphone"
    const val STORAGE = "storage"
    const val CONTACTS = "contacts"
    const val PHONE = "phone"
    const val SMS = "sms"
    const val NOTIFICATIONS = "notifications"
    const val NEARBY = "nearbyDevices"
    const val ACTIVITY = "activity"
    const val CALENDAR = "calendar"

    /** Any other runtime permission the app declares (only reachable through the "all" preset). */
    const val OTHER = "other"

    private const val P = "android.permission."
    const val BACKGROUND_LOCATION = P + "ACCESS_BACKGROUND_LOCATION"

    val GROUPS: Map<String, List<String>> = linkedMapOf(
        LOCATION_FINE to listOf(P + "ACCESS_FINE_LOCATION"),
        LOCATION_COARSE to listOf(P + "ACCESS_COARSE_LOCATION"),
        LOCATION_BACKGROUND to listOf(BACKGROUND_LOCATION),
        CAMERA to listOf(P + "CAMERA"),
        MICROPHONE to listOf(P + "RECORD_AUDIO"),
        STORAGE to listOf(
            P + "READ_EXTERNAL_STORAGE", P + "WRITE_EXTERNAL_STORAGE", P + "READ_MEDIA_IMAGES", P + "READ_MEDIA_VIDEO",
            P + "READ_MEDIA_AUDIO", P + "READ_MEDIA_VISUAL_USER_SELECTED", P + "ACCESS_MEDIA_LOCATION",
        ),
        CONTACTS to listOf(P + "READ_CONTACTS", P + "WRITE_CONTACTS", P + "GET_ACCOUNTS"),
        PHONE to listOf(
            P + "READ_PHONE_STATE", P + "READ_PHONE_NUMBERS", P + "CALL_PHONE", P + "ANSWER_PHONE_CALLS",
            P + "READ_CALL_LOG", P + "WRITE_CALL_LOG", P + "PROCESS_OUTGOING_CALLS", P + "USE_SIP",
            "com.android.voicemail.permission.ADD_VOICEMAIL",
        ),
        SMS to listOf(P + "SEND_SMS", P + "RECEIVE_SMS", P + "READ_SMS", P + "RECEIVE_WAP_PUSH", P + "RECEIVE_MMS"),
        NOTIFICATIONS to listOf(P + "POST_NOTIFICATIONS"),
        NEARBY to listOf(
            P + "BLUETOOTH_SCAN", P + "BLUETOOTH_CONNECT", P + "BLUETOOTH_ADVERTISE", P + "NEARBY_WIFI_DEVICES",
            P + "UWB_RANGING",
        ),
        ACTIVITY to listOf(P + "ACTIVITY_RECOGNITION", P + "BODY_SENSORS", P + "BODY_SENSORS_BACKGROUND"),
        CALENDAR to listOf(P + "READ_CALENDAR", P + "WRITE_CALENDAR"),
    )

    private val GROUP_OF: Map<String, String> = GROUPS.flatMap { (g, perms) -> perms.map { it to g } }.toMap()

    /** The group a runtime permission belongs to ([OTHER] when the console has no row for it). */
    fun groupOf(permission: String): String = GROUP_OF[permission] ?: OTHER

    /**
     * Sensor permissions: on Android 12+ a Device Owner may only GRANT them when
     * `DevicePolicyManager.canAdminGrantSensorsPermissions()` is true (it is not when the device was provisioned
     * with `EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT`); denying is always allowed.
     */
    val SENSOR_PERMISSIONS: Set<String> = setOf(
        P + "ACCESS_FINE_LOCATION", P + "ACCESS_COARSE_LOCATION", BACKGROUND_LOCATION, P + "CAMERA", P + "RECORD_AUDIO",
        P + "ACTIVITY_RECOGNITION", P + "BODY_SENSORS", P + "BODY_SENSORS_BACKGROUND",
    )

    // --- Special accesses: not runtime permissions, so setPermissionGrantState does not cover them ---
    const val ALL_FILES = "allFiles"
    const val OVERLAY = "overlay"
    const val WRITE_SETTINGS = "writeSettings"
    const val UNKNOWN_SOURCES = "unknownSources"
    const val BATTERY = "battery"
    const val UNRESTRICTED_DATA = "unrestrictedData"
    const val NOTIFICATION_ACCESS = "notificationAccess"
    const val ACCESSIBILITY = "accessibility"

    val SPECIAL: List<String> = listOf(
        ALL_FILES, OVERLAY, WRITE_SETTINGS, UNKNOWN_SOURCES, BATTERY, UNRESTRICTED_DATA, NOTIFICATION_ACCESS,
        ACCESSIBILITY,
    )
}

/** What the agent reports about permissions (in `dynamic.permissions`, only when it changed or every few hours). */
@Serializable
data class PermissionReport(
    /** Effective `getPermissionPolicy`: a [PermissionRule] value, or `unknown` when the agent is not Device Owner. */
    val policy: String,
    val deviceOwner: Boolean,
    val sdkInt: Int,
    /** `canAdminGrantSensorsPermissions()` (Android 12+); null on older versions, where there is no such limit. */
    val canGrantSensors: Boolean? = null,
    /** Outcome of the last enforcement (a ConfigOutcome string). */
    val lastOutcome: String? = null,
    val apps: List<AppPermissionState> = emptyList(),
    val at: Long = 0,
)

@Serializable
data class AppPermissionState(
    val packageName: String,
    val installed: Boolean,
    val label: String? = null,
    val targetSdk: Int? = null,
    val permissions: List<PermissionState> = emptyList(),
    val special: List<SpecialAccessState> = emptyList(),
)

/**
 * One runtime permission the app declares. [fixed]: `granted` / `denied` when the policy fixed it (the user cannot
 * change it), `none` when it is the user's (or the default policy's) answer. [wanted]: the rule in force, if any.
 */
@Serializable
data class PermissionState(
    val group: String,
    val name: String,
    val granted: Boolean,
    val fixed: String = FIXED_NONE,
    val wanted: String? = null,
    val note: String? = null,
) {
    companion object {
        const val FIXED_NONE = "none"
        const val FIXED_GRANTED = "granted"
        const val FIXED_DENIED = "denied"
    }
}

/**
 * A special access. [state]: [ALLOWED], [NEEDS_USER] (Android only lets the user turn it on: the console can open
 * the exact screen on the phone), [NOT_DECLARED] (the app does not ask for it), [UNKNOWN] (Android does not let the
 * agent read it). [managed] = what the Device Owner did on its side.
 */
@Serializable
data class SpecialAccessState(
    val key: String,
    val state: String,
    val wanted: Boolean = false,
    val managed: String? = null,
) {
    companion object {
        const val ALLOWED = "allowed"
        const val NEEDS_USER = "needsUser"
        const val NOT_DECLARED = "notDeclared"
        const val UNKNOWN = "unknown"
    }
}
