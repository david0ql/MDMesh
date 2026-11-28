package com.dallycontrol.core.sync

import com.dallycontrol.core.capability.CapabilitySource
import com.dallycontrol.core.command.CommandDispatcher
import com.dallycontrol.core.net.ConnectionDiagnostics
import com.dallycontrol.core.net.MdmApi
import com.dallycontrol.core.net.MemoryKeyValueStore
import com.dallycontrol.core.state.DeviceStateSource
import com.dallycontrol.core.store.DeviceIdentity
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.core.telemetry.TelemetrySource
import com.dallycontrol.proto.AgentCheckInRequest
import com.dallycontrol.proto.EventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One full check-in cycle — the source of truth of the sync loop:
 *
 *  1. ensure the device is enrolled (server-issued id);
 *  2. drain pending command acks and POST them together with the fresh capabilities;
 *  3. dispatch each returned (capability-gated) command;
 *  4. buffer the new results for delivery on the next cycle.
 *
 * [runOnce] is single-flight process-wide (singleton + mutex): the periodic worker, the one-shot
 * worker, the foreground service, the doze heartbeat and WebSocket wakes all race into it, and a
 * fresh device firing several concurrent enrolls burns its single-use token.
 *
 * Network/enroll failures bubble up so [CheckInWorker] applies WorkManager backoff; any
 * drained acks are restored to the buffer so they retry. Per-command failures never abort
 * the batch — they become `failed`/`unsupported`/`expired` results.
 */
@Singleton
class CheckInCoordinator @Inject constructor(
    private val api: MdmApi,
    private val enrollment: EnrollmentManager,
    private val identity: DeviceIdentity,
    private val capabilitySource: CapabilitySource,
    private val dispatcher: CommandDispatcher,
    private val pending: PendingResults,
    private val stateSource: DeviceStateSource,
    private val telemetrySource: TelemetrySource,
    private val eventSink: EventSink,
    private val hardwareIdSource: HardwareIdSource = HardwareIdSource { null },
    private val syncStatus: SyncStatus = SyncStatus(),
) {

    /** Connection diagnostics; injected (a property, not one more constructor parameter). In-memory by default. */
    @set:Inject
    var connection: ConnectionDiagnostics = ConnectionDiagnostics(MemoryKeyValueStore())

    /** Runs around every cycle (lost mode's "silent for N days" wipe). Injected; does nothing by default. */
    @set:Inject
    var hooks: CheckInHooks = CheckInHooks.None

    private val mutex = Mutex()

    // Off the main thread whoever calls: the foreground service launches from lifecycleScope (Main), and
    // a cycle does blocking work (telemetry, a location fix with a latch), which on Main risked ANRs and
    // starved main-looper callbacks the cycle itself was waiting for.
    suspend fun runOnce(): Unit = withContext(workContext) { runLocked(goodbye = false) }

    /**
     * One last report while the device shuts down, carrying the reason, so the console shows it as off rather
     * than as a lost connection. Best effort: the caller bounds it with a timeout.
     */
    suspend fun runGoodbye(): Unit = withContext(workContext) { runLocked(goodbye = true) }

    /** True while a cycle is in flight (a heartbeat then skips instead of queueing behind it). */
    val isBusy: Boolean get() = mutex.isLocked

    /** Where cycles run. Tests swap in EmptyCoroutineContext to stay on the test scheduler. */
    internal var workContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO

    private suspend fun runLocked(goodbye: Boolean): Unit = mutex.withLock {
        runCatching { connection.markAlive() }
        runCatching { hooks.beforeCycle() }
        try {
            // Commands executed in a cycle leave results in the buffer. Report them right away
            // (and pick up anything queued meanwhile) instead of on the next wake or the
            // 10-minute floor, which left the console showing "delivered" for minutes.
            var rounds = 0
            while (cycle(goodbye) && ++rounds < MAX_FOLLOW_UP_ROUNDS) Unit
            syncStatus.clear()
            runCatching { hooks.afterSuccess() }
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                syncStatus.recordFailure(t)
                // Not enrolled yet / no token: nothing was sent, so there is no network failure to explain.
                runCatching {
                    when (t) {
                        is EnrollmentException -> Unit
                        is CheckInException -> connection.recordRejected(t.message)
                        else -> connection.recordFailure(t, ConnectionDiagnostics.CHANNEL_CHECKIN)
                    }
                }
            }
            throw t
        }
    }

    /** One request/response round; true when it executed commands whose results are unreported. */
    private suspend fun cycle(goodbye: Boolean): Boolean {
        val deviceId = enrollment.ensureEnrolled()
        val authorization = "Bearer ${identity.secret().orEmpty()}"
        val matrix = capabilitySource.matrix(deviceId)
        val acks = pending.drain()
        val bufferedEvents = eventSink.drain()
        // A goodbye has a few seconds before the system stops the process: no census (it waits on a location fix).
        val telemetry = if (goodbye) null else runCatching { telemetrySource.snapshot() }.getOrNull()
        val connectionReport = runCatching { connection.pendingReport(goodbye) }.getOrNull()

        val response = try {
            api.checkIn(
                authorization,
                AgentCheckInRequest(
                    deviceId = deviceId,
                    capabilities = matrix.capabilities,
                    results = acks,
                    state = if (goodbye) null else runCatching { stateSource.snapshot() }.getOrNull(),
                    telemetry = telemetry,
                    events = bufferedEvents,
                    hardwareId = runCatching { hardwareIdSource.get() }.getOrNull(),
                    connection = connectionReport,
                ),
            )
        } catch (t: Throwable) {
            pending.restore(acks) // not yet acknowledged by the server; retry next cycle
            eventSink.restore(bufferedEvents)
            throw t
        }

        val data = response.data
        if (!response.isOk || data == null) {
            pending.restore(acks)
            eventSink.restore(bufferedEvents)
            throw CheckInException(response.message ?: "check-in rejected")
        }

        telemetry?.let { runCatching { telemetrySource.delivered(it) } }
        runCatching { connection.delivered(connectionReport, goodbye, data.backupUrls) }
        // A command that must wait (no internet for its download) stays unanswered: the server re-queues it.
        val results = data.commands.mapNotNull {
            try {
                dispatcher.dispatch(it)
            } catch (e: com.dallycontrol.core.command.RetryLaterException) {
                null
            }
        }
        pending.add(results)
        // Record each command outcome as a timeline event (flushed next cycle).
        results.forEach { eventSink.record(EventType.COMMAND_RESULT, "${it.commandId}:${it.status}") }
        return results.isNotEmpty()
    }

    private companion object {
        /** Cap on back-to-back rounds per run, so a server that keeps feeding commands can't pin us. */
        const val MAX_FOLLOW_UP_ROUNDS = 4
    }
}

/** What runs around a check-in cycle, whether or not the server is reachable. */
interface CheckInHooks {
    /** Before the cycle is attempted (also when there is no link). */
    fun beforeCycle() {}

    /** After a cycle that reached the server. */
    fun afterSuccess() {}

    object None : CheckInHooks
}

/** A check-in was rejected by the server. Lets the worker retry with backoff. */
class CheckInException(message: String) : Exception(message)
