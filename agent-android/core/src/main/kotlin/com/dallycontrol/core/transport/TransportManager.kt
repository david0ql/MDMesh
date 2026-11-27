package com.dallycontrol.core.transport

import android.util.Log
import com.dallycontrol.core.net.ConnectionDiagnostics
import com.dallycontrol.core.net.ServerEndpoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Owns the agent wake channel: one WebSocket to {@code wss://<base>/agent/ws/{deviceId}?secret=…},
 * kept alive (OkHttp ping) and auto-reconnected with capped backoff. Each wake invokes [onWake];
 * the host (the foreground service) maps that to a check-in or a fast-sync burst.
 *
 * Wake-to-sync: the socket carries only a tiny signal, so an idle device transfers ~nothing; the
 * actual command pull happens over the authenticated HTTPS /checkin. The WorkManager floor is the
 * backstop when the socket is down.
 *
 * Reconnection: on its own the socket only learns it died from a missed ping (up to ~100 s) and then waits out
 * the backoff. So the host calls [reconnectNow] the moment the network changes or comes back, and [restart]
 * when check-ins stopped working although there is network.
 */
@Singleton
class TransportManager internal constructor(
    okHttpClient: OkHttpClient,
    private val baseUrl: () -> String,
    private val diagnostics: ConnectionDiagnostics? = null,
    private val backoff: (attempt: Int) -> Long = { backoffMs(it) },
) {
    @Inject constructor(okHttpClient: OkHttpClient, endpoints: ServerEndpoints, diagnostics: ConnectionDiagnostics) :
        this(okHttpClient, endpoints::activeUrl, diagnostics)

    // Keepalive ping kept just under typical proxy idle timeouts (~100s) to hold the socket open
    // with the fewest radio wakeups. (When idle on battery in adaptive mode the socket is dropped
    // entirely, so this cost only applies while the socket is intentionally held hot.)
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .pingInterval(50, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false
    @Volatile private var ws: WebSocket? = null
    @Volatile private var attempt = 0
    @Volatile private var openedAt = 0L
    @Volatile private var open = false
    @Volatile private var everOpened = false
    private var reconnectJob: Job? = null

    private var deviceId: String = ""
    private var secret: String = ""
    private var onWake: (suspend (WakeSignal) -> Unit)? = null

    /** True while the socket is open (handshake done, not yet failed or closed). */
    val isOpen: Boolean get() = open

    /** Start (or restart) the wake channel for this device. Idempotent. */
    @Synchronized
    fun start(deviceId: String, secret: String, onWake: suspend (WakeSignal) -> Unit) {
        // Re-enrolled (a code typed on the phone, a forced ADB re-provision): the open socket belongs to the old
        // device id, which the server no longer wakes — reconnect with the new credentials.
        val changed = running && (deviceId != this.deviceId || secret != this.secret)
        this.deviceId = deviceId
        this.secret = secret
        this.onWake = onWake
        if (changed) {
            dropSocket()
            connect()
            return
        }
        if (running) return
        running = true
        connect()
    }

    @Synchronized
    fun stop() {
        running = false
        dropSocket()
    }

    /**
     * Connect again at once, skipping whatever backoff is pending: the network just changed or came back, so the
     * current socket (if any) rides a path that no longer exists and waiting for its ping to notice is the delay
     * operators saw as "disconnected". No-op when the channel is not started.
     */
    @Synchronized
    fun reconnectNow() {
        if (!running) return
        dropSocket()
        attempt = 0
        connect()
    }

    /**
     * [reconnectNow] plus dropping every pooled connection: check-ins kept failing although there is network, so
     * nothing held open is to be trusted (a half-dead connection keeps being reused otherwise).
     */
    fun restart() {
        runCatching { client.connectionPool.evictAll() }
        reconnectNow()
    }

    private fun dropSocket() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.cancel()
        ws = null
        open = false
        openedAt = 0L
    }

    private fun connect() {
        if (!running) return
        val base = baseUrl()
        val url = "$base/agent/ws/$deviceId"
        ws = client.newWebSocket(
            // Secret goes in the handshake header, never the URL (query strings leak into logs).
            Request.Builder().url(url).addHeader("Authorization", "Bearer $secret").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openedAt = System.currentTimeMillis()
                    open = true
                    if (everOpened) diagnostics?.recordReconnect()
                    everOpened = true
                    // Catch up on (re)connect: a wake the server sent while this socket was down (NAT
                    // timeout, network switch, server restart) went nowhere, so without this those
                    // commands waited for the 15-minute floor.
                    val cb = onWake ?: return
                    scope.launch {
                        runCatching { cb(WakeSignal(KIND_CATCH_UP)) }
                            .onFailure { Log.w(TAG, "catch-up sync failed", it) }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val signal = WakeSignal.parse(text) ?: return
                    val cb = onWake ?: return
                    scope.launch {
                        runCatching { cb(signal) }
                            .onFailure { Log.w(TAG, "wake handler failed", it) }
                    }
                }

                // Only the current socket reconnects: a socket replaced by start() (new credentials) reports its
                // cancellation here, and reconnecting for it would drop the new socket and open a second one.
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== ws) return
                    runCatching {
                        diagnostics?.recordFailure(t, ConnectionDiagnostics.CHANNEL_SOCKET, response?.code)
                    }
                    scheduleReconnect(webSocket)
                }

                // A server-initiated clean close is a failure for backoff purposes too — otherwise
                // an accept-then-close server drives a reconnect every second, forever.
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    scheduleReconnect(webSocket)
                }
            },
        )
    }

    @Synchronized
    private fun scheduleReconnect(failed: WebSocket) {
        if (!running || failed !== ws) return
        ws = null
        open = false
        // Reset backoff only after a stable session (open ≥30s); resetting in onOpen let a
        // connection that dies right after the handshake pin the retry delay at 1s.
        if (openedAt != 0L && System.currentTimeMillis() - openedAt >= STABLE_SESSION_MS) attempt = 0
        openedAt = 0L
        val waitMs = backoff(attempt)
        attempt++
        reconnectJob = scope.launch {
            delay(waitMs)
            synchronized(this@TransportManager) { if (running && ws == null) connect() }
        }
    }

    companion object {
        private const val TAG = "TransportManager"
        private const val STABLE_SESSION_MS = 30_000L
        /** Any kind other than "interactive" makes the host run one check-in. */
        private const val KIND_CATCH_UP = "commands"

        /** Longest wait between attempts. Was 60 s: a minute more of "disconnected" after every outage. */
        const val MAX_BACKOFF_MS = 15_000L
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_SHIFT = 4
        private const val JITTER = 0.2

        /**
         * 1, 2, 4, 8, then 15 s, each ±20 % so a fleet that lost the server together does not come back in step.
         */
        fun backoffMs(attempt: Int, random: Random = Random.Default): Long {
            val base = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L shl minOf(attempt, MAX_SHIFT)))
            val factor = 1.0 - JITTER + random.nextDouble() * 2 * JITTER
            return (base * factor).toLong().coerceIn(BASE_BACKOFF_MS / 2, MAX_BACKOFF_MS)
        }
    }
}
