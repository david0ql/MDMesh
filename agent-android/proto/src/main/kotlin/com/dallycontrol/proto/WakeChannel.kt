package com.dallycontrol.proto

import kotlinx.serialization.Serializable

/**
 * How the agent can be woken when its live channel is closed, piggybacked on a check-in (optional, additive):
 * the push token and the phone's own limits on background work. The console uses it to say by which channel a
 * command will reach the device.
 */
@Serializable
data class WakeReport(
    /** [PushState.PROVIDER_FCM], or null when the agent has no push channel. */
    val provider: String? = null,
    val token: String? = null,
    /** One of [PushState]. */
    val state: String? = null,
    val error: String? = null,
    val batteryExempt: Boolean? = null,
    val exactAlarms: Boolean? = null,
    val bgRestricted: Boolean? = null,
    /** Minutes between backup check-ins, as scheduled now. */
    val pollMinutes: Int? = null,
    /** What triggered this check-in; one of [WakeTrigger]. */
    val wokenBy: String? = null,
)

object PushState {
    const val PROVIDER_FCM = "fcm"
    const val READY = "ready"

    /** The server has no push project. */
    const val UNCONFIGURED = "unconfigured"

    /** The phone has no Google services (or they refuse): the agent stays on its own check-ins. */
    const val UNAVAILABLE = "unavailable"
    const val ERROR = "error"
}

object WakeTrigger {
    const val PUSH = "push"
    const val SOCKET = "socket"
    const val ALARM = "alarm"
    const val SCREEN = "screen"
    const val UNLOCK = "unlock"
    const val POWER = "power"
    const val NETWORK = "network"
    const val WORKER = "worker"
    const val BOOT = "boot"
    const val OTHER = "other"
}

/**
 * Sent by the server with each check-in answer: the backup check-in schedule of the device's policy and, when the
 * server has a push project, the public client values to register with it. Null fields = the agent's defaults.
 */
@Serializable
data class WakeConfig(
    val pollMinutes: Int? = null,
    val fastPollMinutes: Int? = null,
    val fastWindowMinutes: Int? = null,
    val alarmClock: Boolean? = null,
    val push: PushConfig? = null,
)

@Serializable
data class PushConfig(
    val provider: String? = null,
    val projectId: String? = null,
    val apiKey: String? = null,
    val senderId: String? = null,
    /** Firebase app id by Android package name; `*` = any package. */
    val appIds: Map<String, String> = emptyMap(),
) {
    fun appIdFor(packageName: String): String? = appIds[packageName] ?: appIds["*"]
}
