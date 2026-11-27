package com.dallycontrol.core.net

import com.dallycontrol.proto.ConnectionFailure
import com.dallycontrol.proto.ConnectionReport
import com.dallycontrol.proto.ConnectionStop
import com.dallycontrol.proto.FailureKind
import com.dallycontrol.proto.ProtocolJson
import com.dallycontrol.proto.StopReason
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * What happened to the connection since the last report the server acknowledged: the last network failure
 * (classified, with the network it happened on), how many times the wake socket reconnected and why the device
 * went silent. It travels in the next successful check-in, so a gap can be explained afterwards — "the phone
 * was off", "the site's Wi-Fi refused our certificate" — instead of guessed.
 *
 * Persistent: the explanation of a silence usually has to survive the process (or the phone) restarting.
 */
class ConnectionDiagnostics(
    private val store: KeyValueStore,
    private val endpoints: ServerEndpoints? = null,
    private val network: NetworkStateSource = NetworkStateSource { NetworkSnapshot("unknown") },
    private val clock: () -> Long = System::currentTimeMillis,
    private val issuerProbe: (host: String, port: Int) -> String? = { _, _ -> null },
) {
    @Serializable
    private data class State(
        val reconnects: Int = 0,
        val failure: ConnectionFailure? = null,
        val stop: ConnectionStop? = null,
        val lastSuccessAt: Long = 0,
        val aliveAt: Long = 0,
        val shutdownAt: Long = 0,
        val bootCount: Int = NO_BOOT_COUNT,
        /** The server already knows about [stop] (goodbye report): its end must be reported however short. */
        val stopAnnounced: Boolean = false,
    )

    private var state: State = runCatching {
        store.getString(KEY)?.let { ProtocolJson.json.decodeFromString(State.serializer(), it) }
    }.getOrNull() ?: State()

    /** When the last check-in succeeded (0: never, as far as this install remembers). */
    val lastSuccessAt: Long @Synchronized get() = state.lastSuccessAt

    /**
     * A check-in ("checkin") or the wake socket ("socket") failed. Failures without any network are not kept as
     * such — that is a silence with its own reason ([StopReason.NETWORK_LOST]), not a fault of the network path.
     */
    fun recordFailure(t: Throwable, channel: String, httpCode: Int? = null) {
        val classified = NetworkFailures.classify(t, httpCode, webSocket = channel == CHANNEL_SOCKET)
        val net = network.current()
        if (!net.connected) {
            recordStop(StopReason.NETWORK_LOST)
            return
        }
        if (NetworkFailures.countsForFailover(classified)) endpoints?.onUnreachable()
        val url = endpoints?.activeUrl()?.toHttpUrlOrNull()
        val previous = synchronized(this) { state.failure }
        val issuer = if (classified.kind != FailureKind.TLS || url == null) {
            null
        } else {
            // One probe per host and failure streak: retries must not open a probe socket each.
            previous?.takeIf { it.host == url.host }?.certIssuer ?: issuerProbe(url.host, url.port)
        }
        update {
            it.copy(
                failure = ConnectionFailure(
                    kind = classified.kind,
                    at = clock(),
                    detail = classified.detail,
                    httpCode = classified.httpCode,
                    channel = channel,
                    network = net.type,
                    ssid = net.ssid,
                    host = url?.host,
                    certIssuer = issuer,
                    count = (it.failure?.count ?: 0) + 1,
                ),
            )
        }
    }

    /** The server answered, and refused the check-in (unknown device, bad secret): not a network problem. */
    fun recordRejected(message: String?) {
        val net = network.current()
        update {
            it.copy(
                failure = ConnectionFailure(
                    kind = FailureKind.REJECTED,
                    at = clock(),
                    detail = message,
                    channel = CHANNEL_CHECKIN,
                    network = net.type,
                    ssid = net.ssid,
                    host = endpoints?.activeUrl()?.toHttpUrlOrNull()?.host,
                    count = (it.failure?.count ?: 0) + 1,
                ),
            )
        }
    }

    fun recordReconnect() = update { it.copy(reconnects = it.reconnects + 1) }

    /**
     * The device is about to go silent (or just noticed it was). The first reason of a silence stands; only an
     * orderly shutdown replaces it, being the one thing known for certain.
     */
    fun recordStop(reason: String, battery: Int? = null) = update {
        val now = clock()
        val shutdown = reason == StopReason.SHUTDOWN
        when {
            shutdown -> it.copy(stop = ConnectionStop(reason, now, battery = battery), shutdownAt = now)
            it.stop == null -> it.copy(stop = ConnectionStop(reason, now, battery = battery))
            else -> it
        }
    }

    /** The network came back within moments: forget a network-lost mark that explained nothing. */
    fun networkBack() = update {
        val stop = it.stop
        if (stop != null && stop.reason == StopReason.NETWORK_LOST && clock() - stop.at < MIN_GAP_MS) {
            it.copy(stop = null)
        } else {
            it
        }
    }

    /**
     * Called regularly while the process runs. A long hole between two calls means the system kept the agent
     * asleep (Doze, a maker's battery manager) although its alarms should have fired.
     */
    fun markAlive() = update {
        val now = clock()
        val slept = it.aliveAt > 0 && now - it.aliveAt >= SUSPENDED_GAP_MS && it.stop == null
        it.copy(aliveAt = now, stop = if (slept) ConnectionStop(StopReason.SUSPENDED, it.aliveAt) else it.stop)
    }

    /**
     * Process start. Same boot as the last sign of life and a long hole: the system killed the process. Another
     * boot with no shutdown notice: the power went away (battery, forced power-off).
     */
    fun onProcessStart(bootCount: Int) = update {
        val now = clock()
        val hole = it.aliveAt > 0 && now - it.aliveAt >= MIN_GAP_MS
        val reason = when {
            !hole || it.stop != null || it.bootCount == NO_BOOT_COUNT || bootCount == NO_BOOT_COUNT -> null
            it.bootCount == bootCount -> StopReason.PROCESS_KILLED
            it.shutdownAt < it.aliveAt -> StopReason.POWER_LOST
            else -> null
        }
        it.copy(
            aliveAt = now,
            bootCount = bootCount,
            stop = if (reason != null) ConnectionStop(reason, it.aliveAt) else it.stop,
        )
    }

    /**
     * The report for the next check-in. [goodbye]: sent while shutting down, so the silence has no end yet.
     * Silences shorter than [MIN_GAP_MS] are not reported.
     */
    @Synchronized
    fun pendingReport(goodbye: Boolean = false): ConnectionReport {
        val now = clock()
        val s = state
        val reachedNetwork = s.failure != null && s.failure.at > s.lastSuccessAt
        val stop = s.stop ?: s.lastSuccessAt.takeIf { it > 0 && now - it >= SUSPENDED_GAP_MS }?.let {
            ConnectionStop(if (reachedNetwork) StopReason.SERVER_UNREACHABLE else StopReason.UNKNOWN, it)
        }
        val reported = when {
            stop == null -> null
            goodbye -> stop.takeIf { it.reason == StopReason.SHUTDOWN }
            now - stop.at >= MIN_GAP_MS || s.stopAnnounced -> stop.copy(resumedAt = now)
            else -> null
        }
        return ConnectionReport(
            reconnects = s.reconnects,
            lastFailure = s.failure,
            lastStop = reported,
            serverHost = endpoints?.activeUrl()?.toHttpUrlOrNull()?.host,
            usingBackup = endpoints?.usingBackup(),
        )
    }

    /**
     * The server acknowledged [report]: drop what it carried and note the success. [backupUrls] is the list the
     * response carried; null (an older server) leaves the stored one alone.
     */
    fun delivered(report: ConnectionReport?, goodbye: Boolean = false, backupUrls: List<String>? = null) {
        endpoints?.onReached()
        if (backupUrls != null) endpoints?.saveBackups(backupUrls)
        update {
            val failure = it.failure?.takeIf { f -> report == null || f.at != report.lastFailure?.at }
            // A goodbye leaves the stop in place: the next report closes it with the time the device came back.
            val stop = it.stop?.takeIf { goodbye && report?.lastStop != null }
            it.copy(
                reconnects = (it.reconnects - (report?.reconnects ?: 0)).coerceAtLeast(0),
                failure = failure,
                stop = if (report == null) it.stop else stop,
                stopAnnounced = stop != null,
                lastSuccessAt = clock(),
                aliveAt = clock(),
            )
        }
    }

    @Synchronized
    private fun update(change: (State) -> State) {
        val next = change(state)
        if (next == state) return
        state = next
        runCatching { store.putString(KEY, ProtocolJson.json.encodeToString(State.serializer(), next)) }
    }

    companion object {
        const val CHANNEL_CHECKIN = "checkin"
        const val CHANNEL_SOCKET = "socket"

        /** Silences shorter than this are not worth explaining. */
        const val MIN_GAP_MS = 5L * 60_000L

        /** The heartbeat alarm fires every few minutes; this long without running means the system held us. */
        const val SUSPENDED_GAP_MS = 15L * 60_000L

        const val NO_BOOT_COUNT = -1
        private const val KEY = "state"
    }
}
