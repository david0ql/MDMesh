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
    const val ANNOUNCEMENT_RECEIVED = "announcementReceived"
    const val ANNOUNCEMENT_SEEN = "announcementSeen"
    const val ANNOUNCEMENT_ACK = "announcementAck"
    const val SYSTEM_UPDATED = "systemUpdated"
    const val SYSTEM_UPDATE_PENDING = "systemUpdatePending"
    const val KIOSK_CRASH_LOOP = "kioskCrashLoop"
}
