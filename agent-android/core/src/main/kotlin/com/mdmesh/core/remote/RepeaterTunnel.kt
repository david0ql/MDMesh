package com.mdmesh.core.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted transport for a remote-control session (ADR 0010). droidVNC-NG can only dial the repeater over
 * plain TCP, so the agent gives it a loopback address instead: every connection droidVNC-NG makes to
 * [open]'s port is carried inside a WebSocket to `<server>/remote/device/` (TLS, the same origin and trust
 * as the API) and from there to the repeater's device port. Nothing between the phone and the server sees
 * the VNC stream in clear.
 *
 * The handshake carries the device secret (`Authorization: Bearer`) and the device id ([DEVICE_HEADER]); the
 * server lets the WebSocket through only for a device with a session queued in the last minutes.
 *
 * One tunnel at a time: [open] closes the previous one. Each connection droidVNC-NG makes gets its own
 * WebSocket, opened when it connects: the repeater drops a device connection that does not present its session
 * id within about a second, so the WebSocket must not sit open while droidVNC-NG is still starting. [open]
 * first makes a probe WebSocket (opened and closed at once) so a refused or unreachable tunnel fails the command
 * instead of looking connected.
 */
@Singleton
class RepeaterTunnel @Inject constructor(client: OkHttpClient) {

    private val client: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // a quiet viewer is not a dead tunnel
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS) // keeps carrier NATs and proxies from idling it out
        .build()

    @Volatile private var server: ServerSocket? = null
    private val bridges = mutableListOf<Bridge>()

    /**
     * Check the tunnel is allowed and reachable, then listen on loopback. Returns the port droidVNC-NG must
     * dial, or throws [IOException] with the reason (server refused the tunnel, unreachable, timeout).
     */
    suspend fun open(url: String, deviceId: String, secret: String): Int = withContext(Dispatchers.IO) {
        close()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $secret")
            .header(DEVICE_HEADER, deviceId)
            .build()
        probe(request)
        // IPv4 on purpose: Android's getLoopbackAddress() is ::1, and droidVNC-NG dials LOOPBACK_HOST.
        val listener = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
        synchronized(this@RepeaterTunnel) { server = listener }
        Thread({ acceptLoop(listener, request) }, "repeater-tunnel").apply { isDaemon = true }.start()
        listener.localPort
    }

    /** Close the listener and every bridged connection. Idempotent. */
    fun close() {
        val (listener, open) = synchronized(this) {
            val l = server
            server = null
            val b = bridges.toList()
            bridges.clear()
            l to b
        }
        runCatching { listener?.close() }
        open.forEach { it.close() }
    }

    private suspend fun probe(request: Request) {
        val result = CompletableDeferred<String?>()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                result.complete(null)
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.complete(failureReason(t, response))
            }
        })
        val error = withTimeoutOrNull(OPEN_TIMEOUT_MS) { result.await() }
            ?: if (result.isCompleted) null else "tunnel did not open within ${OPEN_TIMEOUT_MS / 1000} s"
        if (error != null) {
            ws.cancel()
            throw IOException(error)
        }
    }

    private fun acceptLoop(listener: ServerSocket, request: Request) {
        while (!listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (e: IOException) {
                break // closed
            }
            val bridge = Bridge(request, socket)
            synchronized(this) { bridges += bridge }
            bridge.connect()
        }
    }

    /**
     * One droidVNC-NG connection <-> one WebSocket. Reading from droidVNC-NG starts only once the WebSocket is
     * open, so its first bytes (the session id) wait in the socket buffer instead of being lost.
     */
    private inner class Bridge(private val request: Request, private val socket: Socket) {
        @Volatile private var ws: WebSocket? = null
        @Volatile private var closed = false

        fun connect() {
            socket.tcpNoDelay = true
            ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Thread({ pump(webSocket) }, "repeater-tunnel-up").apply { isDaemon = true }.start()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        socket.getOutputStream().write(bytes.toByteArray())
                    } catch (e: IOException) {
                        close()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    close()
                }
            })
        }

        /** droidVNC-NG -> WebSocket, holding back while OkHttp's send queue is full (frames are large). */
        private fun pump(w: WebSocket) {
            val buf = ByteArray(CHUNK)
            try {
                val input = socket.getInputStream()
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    while (!closed && w.queueSize() > MAX_QUEUED_BYTES) Thread.sleep(BACKPRESSURE_SLEEP_MS)
                    if (!w.send(buf.toByteString(0, n))) break
                }
            } catch (e: IOException) {
                // socket closed
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            close()
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { ws?.close(NORMAL_CLOSURE, null) }
            runCatching { socket.close() }
        }
    }

    private fun failureReason(t: Throwable, response: Response?): String = when (response?.code) {
        null -> "tunnel failed: ${t.message ?: t.javaClass.simpleName}"
        401, 403 -> "server refused the tunnel (HTTP ${response.code}): no session queued for this device?"
        else -> "tunnel failed: HTTP ${response.code}"
    }

    companion object {
        /** Header naming the device on the tunnel handshake (the server checks it against the secret). */
        const val DEVICE_HEADER = "X-MDMesh-Device"
        /** Path of the device side of the tunnel on the server's origin (Caddy -> websockify-device). */
        const val PATH = "/remote/device/websockify"
        /** Where droidVNC-NG must dial the port [open] returns. */
        const val LOOPBACK_HOST = "127.0.0.1"
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val PING_SECONDS = 25L
        private const val BACKLOG = 2
        private const val CHUNK = 64 * 1024
        private const val MAX_QUEUED_BYTES = 2L * 1024 * 1024
        private const val BACKPRESSURE_SLEEP_MS = 5L
        private const val NORMAL_CLOSURE = 1000
    }
}
