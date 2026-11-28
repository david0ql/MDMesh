package com.dallycontrol.core.support

import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The device's session socket against a real WebSocket server: handshake, both directions, refusal vs network. */
class SupportChannelTest {

    private lateinit var server: MockWebServer
    private val channel = SupportChannel(OkHttpClient())
    private val events = LinkedBlockingQueue<String>()
    private val serverGot = LinkedBlockingQueue<Any>()
    private val serverSockets = LinkedBlockingQueue<WebSocket>()

    private val listener = object : SupportChannel.Listener {
        override fun onOpen() { events += "open" }
        override fun onMessage(type: String, message: JsonObject) { events += "msg:$type:$message" }
        override fun onBinary(kind: Byte, data: ByteArray) { events += "bin:$kind:${data.size}" }
        override fun onClosed(refused: Boolean, reason: String) { events += "closed:$refused" }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun accept() = server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets += webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { serverGot += text }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { serverGot += bytes }
    }))

    private fun next() = events.poll(5, TimeUnit.SECONDS)

    @Test
    fun `dials the session path with the device secret and relays both ways`() {
        accept()
        val conn = channel.connect(server.url("/").toString(), "dev-1", "s3cret", SESSION, listener)
        assertEquals("open", next())
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/agent/ws/dev-1/support/$SESSION", request.path)
        assertEquals("Bearer s3cret", request.getHeader("Authorization"))
        // The secret is in the header, never in the URL (query strings end up in access logs).
        assertFalse(request.path!!.contains("s3cret"))

        val peer = serverSockets.poll(5, TimeUnit.SECONDS)!!
        peer.send("""{"t":"screen.start","maxDim":960}""")
        assertEquals("""msg:screen.start:{"t":"screen.start","maxDim":960}""", next())
        peer.send(byteArrayOf(SupportProtocol.BIN_AUDIO, 1, 2, 3).toByteString())
        assertEquals("bin:2:4", next())
        peer.send("not json") // ignored, the socket stays up
        peer.send("""{"no":"type"}""")

        assertTrue(conn.send(SupportProtocol.message("info")))
        assertEquals("""{"t":"info"}""", serverGot.poll(5, TimeUnit.SECONDS))
        val frame = SupportProtocol.frame(1, 4, 4, byteArrayOf(9, 9))
        assertTrue(conn.send(frame))
        assertArrayEquals(frame, (serverGot.poll(5, TimeUnit.SECONDS) as ByteString).toByteArray())
        conn.close()
    }

    @Test
    fun `a session the server ended is not retried`() {
        accept()
        channel.connect(server.url("/").toString(), "dev-1", "s", SESSION, listener)
        assertEquals("open", next())
        serverSockets.poll(5, TimeUnit.SECONDS)!!.close(1000, "console")
        assertEquals("closed:true", next())
    }

    @Test
    fun `an unknown session is refused, a dead network is not`() {
        server.enqueue(MockResponse().setResponseCode(403))
        channel.connect(server.url("/").toString(), "dev-1", "s", SESSION, listener)
        assertEquals("closed:true", next())

        val url = server.url("/").toString()
        server.shutdown()
        channel.connect(url, "dev-1", "s", SESSION, listener)
        assertEquals("closed:false", next()) // the caller retries this one
    }

    private companion object {
        const val SESSION = "0123456789abcdef01234567"
    }
}
