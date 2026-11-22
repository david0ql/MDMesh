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
}
