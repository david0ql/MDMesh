package com.dallycontrol.proto

import kotlinx.serialization.Serializable

/** A device lifecycle event, buffered offline and flushed to the server on the next check-in. */
@Serializable
data class TelemetryEventDto(
    val type: String,
    val ts: Long,
    val detail: String? = null,
)

/** Open registry of event types. */
object EventType {
    const val BOOT = "boot"
    const val APP_INSTALLED = "appInstalled"
    const val APP_UNINSTALLED = "appUninstalled"
    const val COMMAND_RESULT = "commandResult"
    const val CONNECTIVITY = "connectivityChange"
    const val LOW_BATTERY = "lowBattery"
    const val ENROLLED = "enrolled"
    const val SIM_REMOVED = "simRemoved"
    const val SIM_INSERTED = "simInserted"
    const val SIM_CHANGED = "simChanged"
    const val APP_BLOCKED = "appBlocked"
    const val APP_UPDATED = "appUpdated"

    /**
     * User activity on the device: an app was brought to the foreground (the user opened/switched to it).
     * `detail` is the app label, with the package in parentheses when they differ. Derived from
     * UsageStats foreground transitions (needs usage access), so it reflects what a Device Owner can
     * actually observe: which app is on screen and when — not taps, text, or in-app navigation.
     */
    const val APP_OPENED = "appOpened"

    /**
     * User activity: a camera app was brought to the foreground. Same source/limits as [APP_OPENED]
     * (foreground package matched against the device's camera apps). It does NOT mean a photo was taken,
     * nor does it catch the camera being used in-process by another app without its own camera UI.
     */
    const val CAMERA_OPENED = "cameraOpened"
    const val ACCOUNT_REMOVED = "accountRemoved"
    const val ANNOUNCEMENT_RECEIVED = "announcementReceived"
    const val ANNOUNCEMENT_SEEN = "announcementSeen"
    const val ANNOUNCEMENT_ACK = "announcementAck"
    const val SYSTEM_UPDATED = "systemUpdated"
    const val SYSTEM_UPDATE_PENDING = "systemUpdatePending"
    const val KIOSK_CRASH_LOOP = "kioskCrashLoop"
    /** Something loud is playing (alarm, ringtone…) for a while: what and its likely source. */
    const val SOUND_ALERT = "soundAlert"
    /** That sound stopped: how long it played and what stopped it. */
    const val SOUND_ENDED = "soundEnded"

    /**
     * Policy enforcement drifted from the desired state: the agent applied the app policy but some pieces could not
     * be made to stick. `detail` lists the problems as `key=pkg[,pkg];key=…` (e.g. `failedHide=…;missing=…;noLauncher=…`),
     * so the console can show exactly what/where. Emitted only when the drift signature changes (not every cycle).
     */
    const val POLICY_DRIFT = "policyDrift"

    /**
     * An allowed app is present but has no real version (version code <= 0), so it is effectively a broken/stub
     * install the console should flag and re-push. `detail` is the package name. Emitted once per package until it
     * recovers a real version (respects the install retry cache; no busy loop).
     */
    const val APP_NO_VERSION = "appNoVersion"

    /** Someone left the kiosk on the device with the exit password. */
    const val KIOSK_EXIT = "kioskExit"

    /** A wrong kiosk exit password. `detail`: failures in a row and the wait earned (`intento 3; espera 30 s`). */
    const val KIOSK_EXIT_FAILED = "kioskExitFailed"

    /** An exit attempt refused without comparing anything: no password in the policy, or still waiting. */
    const val KIOSK_EXIT_DENIED = "kioskExitDenied"

    /** A wrong anti-theft (power) PIN. Same `detail` as [KIOSK_EXIT_FAILED]. */
    const val POWER_PIN_FAILED = "powerPinFailed"

    /** Kiosk guard (Android 7–10, 16+): an app kept closed (`detail`: its package) came to the front; sent back. */
    const val KIOSK_ESCAPE_BLOCKED = "kioskEscapeBlocked"

    /** Packages Android refused to suspend for the kiosk (`notSuspended=pkg[,pkg]`) or no guard; sent on change. */
    const val KIOSK_LOCKDOWN_GAP = "kioskLockdownGap"
}
