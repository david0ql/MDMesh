package com.dallycontrol.core.support

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's socket of a remote-support session (ADR 0012): `wss://<server>/agent/ws/{deviceId}/support/{session}`
 * with the device secret as bearer token, over the same HTTPS origin and trust as the API. It dials out, so it
 * works behind carrier NAT with nothing but port 443, and needs no STUN/TURN relay.
 */
@Singleton
class SupportChannel @Inject constructor(client: OkHttpClient) {

    private val client: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS) // keeps NATs open and detects a dead network within seconds
        .build()

    interface Listener {
        fun onOpen() {}
        fun onMessage(type: String, message: JsonObject) {}
        /** [data] includes the leading kind byte. */
        fun onBinary(kind: Byte, data: ByteArray) {}
        /** [refused]: the server said no (unknown or finished session): do not retry. */
        fun onClosed(refused: Boolean, reason: String) {}
    }

    class Connection internal constructor(private val socket: WebSocket) {
        fun send(text: String): Boolean = socket.send(text)
        fun send(bytes: ByteArray): Boolean = socket.send(bytes.toByteString())
        /** Bytes accepted but not yet on the wire: the sender slows down when this grows. */
        fun backlog(): Long = socket.queueSize()
        fun close() { socket.close(NORMAL, "bye") }
        fun cancel() { socket.cancel() }
    }

    fun connect(baseUrl: String, deviceId: String, secret: String, sessionId: String, listener: Listener): Connection {
        val request = Request.Builder().url(url(baseUrl, deviceId, sessionId))
            .header("Authorization", "Bearer $secret")
            .build()
        return Connection(client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val type = with(SupportProtocol) { o.str("t") } ?: return
                listener.onMessage(type, o)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (bytes.size > 0) listener.onBinary(bytes[0], bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(refused = code == POLICY_VIOLATION || code == NORMAL, reason = reason.ifEmpty { "closed" })

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                listener.onClosed(refused = status != null && status in 400..499, reason = t.message ?: "network")
            }
        }))
    }

    companion object {
        const val PING_SECONDS = 15L
        private const val NORMAL = 1000
        private const val POLICY_VIOLATION = 1008
        private val JSON = Json { ignoreUnknownKeys = true }

        fun url(baseUrl: String, deviceId: String, sessionId: String): String =
            "${baseUrl.trimEnd('/')}/agent/ws/$deviceId/support/$sessionId"

        /** Session keys are 24 lowercase hex characters; anything else never reaches a URL. */
        fun isSessionId(s: String?): Boolean = s != null && Regex("[0-9a-f]{24}").matches(s)
    }
}
