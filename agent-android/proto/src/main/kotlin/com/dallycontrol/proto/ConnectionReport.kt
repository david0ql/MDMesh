package com.dallycontrol.proto

import kotlinx.serialization.Serializable

/**
 * Connection diagnostics piggybacked on a check-in (optional, additive): what went wrong since the last
 * successful report, so the console can explain a silence (the customer's network, the phone being off…).
 * Sent once; the agent clears what the server acknowledged.
 */
@Serializable
data class ConnectionReport(
    /** Wake-socket reconnections since the last delivered report. */
    val reconnects: Int = 0,
    /** Most recent network failure since the last delivered report. */
    val lastFailure: ConnectionFailure? = null,
    /** Why the device went silent before this report (see [StopReason]). */
    val lastStop: ConnectionStop? = null,
    /** Host of the server URL in use. */
    val serverHost: String? = null,
    /** True while the agent talks to a backup URL instead of the primary one. */
    val usingBackup: Boolean? = null,
)

/** One classified network failure. [kind] is one of [FailureKind]. */
@Serializable
data class ConnectionFailure(
    val kind: String,
    val at: Long,
    val detail: String? = null,
    /** HTTP status, for [FailureKind.HTTP] and [FailureKind.WS_UPGRADE]. */
    val httpCode: Int? = null,
    /** "checkin" (HTTPS) or "socket" (WebSocket). */
    val channel: String? = null,
    /** Active network when it failed: "wifi" | "cellular" | "ethernet" | "vpn" | "none". */
    val network: String? = null,
    val ssid: String? = null,
    val host: String? = null,
    /** Issuer of the certificate the network presented (TLS failures): shows TLS inspection. */
    val certIssuer: String? = null,
    /** Failures since the last successful check-in. */
    val count: Int = 1,
)

/** A silence: from [at] until [resumedAt] (absent in a goodbye report sent while shutting down). */
@Serializable
data class ConnectionStop(
    val reason: String,
    val at: Long,
    val resumedAt: Long? = null,
    val battery: Int? = null,
)

object FailureKind {
    const val DNS = "dns"
    const val TLS = "tls"
    const val TIMEOUT = "timeout"
    const val CONNECT = "connect"
    const val HTTP = "http"
    const val WS_UPGRADE = "wsUpgrade"
    const val REJECTED = "rejected"
    const val OTHER = "other"
}

object StopReason {
    /** The device was shut down or restarted in an orderly way. */
    const val SHUTDOWN = "shutdown"
    /** It came back from a boot nobody announced (battery ran out, forced power-off). */
    const val POWER_LOST = "powerLost"
    /** The device had no network. */
    const val NETWORK_LOST = "networkLost"
    /** The system killed the agent's process. */
    const val PROCESS_KILLED = "processKilled"
    /** The system kept the agent asleep (Doze / maker's battery manager). */
    const val SUSPENDED = "suspended"
    /** There was network, but the server could not be reached (see the failure). */
    const val SERVER_UNREACHABLE = "serverUnreachable"
    const val UNKNOWN = "unknown"
}
