package com.dallycontrol.core.transport

import android.util.Log
import com.dallycontrol.core.config.ServerConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * Owns the agent wake channel: one WebSocket to {@code wss://<base>/agent/ws/{deviceId}?secret=…},
 * kept alive (OkHttp ping) and auto-reconnected with capped backoff. Each wake invokes [onWake];
 * the host (the foreground service) maps that to a check-in or a fast-sync burst.
 *
 * Wake-to-sync: the socket carries only a tiny signal, so an idle device transfers ~nothing; the
 * actual command pull happens over the authenticated HTTPS /checkin. The WorkManager floor is the
 * backstop when the socket is down.
 */
@Singleton
class TransportManager internal constructor(
    okHttpClient: OkHttpClient,
    private val baseUrl: () -> String,
) {
    @Inject constructor(okHttpClient: OkHttpClient, serverConfig: ServerConfigStore) :
        this(okHttpClient, serverConfig::baseUrl)

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

    private var deviceId: String = ""
    private var secret: String = ""
    private var onWake: (suspend (WakeSignal) -> Unit)? = null

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
            ws?.cancel()
            ws = null
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
        ws?.cancel()
        ws = null
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
                    if (webSocket === ws) scheduleReconnect()
                }

                // A server-initiated clean close is a failure for backoff purposes too — otherwise
                // an accept-then-close server drives a reconnect every second, forever.
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (webSocket === ws) scheduleReconnect()
                }
            },
        )
    }

    private fun scheduleReconnect() {
        if (!running) return
        ws = null
        // Reset backoff only after a stable session (open ≥30s); resetting in onOpen let a
        // connection that dies right after the handshake pin the retry delay at 1s.
        if (openedAt != 0L && System.currentTimeMillis() - openedAt >= STABLE_SESSION_MS) attempt = 0
        openedAt = 0L
        val backoffMs = minOf(60_000L, 1_000L * (1L shl minOf(attempt, 5)))
        attempt++
        scope.launch {
            delay(backoffMs)
            synchronized(this@TransportManager) { if (running) connect() }
        }
    }

    private companion object {
        const val TAG = "TransportManager"
        const val STABLE_SESSION_MS = 30_000L
        /** Any kind other than "interactive" makes the host run one check-in. */
        const val KIND_CATCH_UP = "commands"
    }
}
