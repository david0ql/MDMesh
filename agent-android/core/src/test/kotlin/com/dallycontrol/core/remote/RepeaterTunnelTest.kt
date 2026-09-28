package com.dallycontrol.core.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The tunnel against a real WebSocket server: bytes both ways, handshake headers, refusal, reconnects. */
class RepeaterTunnelTest {

    private lateinit var server: MockWebServer
    private val tunnel = RepeaterTunnel(OkHttpClient())
    /** What the "repeater" side received, and its socket to answer on. */
    private val received = LinkedBlockingQueue<ByteString>()
    private val serverSockets = LinkedBlockingQueue<WebSocket>()

    private val echoSide = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            openedAt.add(System.nanoTime())
            serverSockets.add(webSocket)
            allServerSockets.add(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            received.add(bytes)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }
    private val allServerSockets = java.util.concurrent.CopyOnWriteArrayList<WebSocket>()
    private val openedAt = LinkedBlockingQueue<Long>()

    /** The probe [RepeaterTunnel.open] makes: opened, then closed by the tunnel at once. */
    private val probeSide = object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun enqueueProbe() = server.enqueue(MockResponse().withWebSocketUpgrade(probeSide))
    private fun enqueueBridge() = server.enqueue(MockResponse().withWebSocketUpgrade(echoSide))

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        tunnel.close()
        allServerSockets.forEach { runCatching { it.close(1000, null) } }
        server.shutdown()
    }

    private fun url() = server.url(RepeaterTunnel.PATH).toString()

    @Test fun `bytes flow both ways and the handshake names the device`() = runBlocking {
        enqueueProbe()
        enqueueBridge()
        val port = tunnel.open(url(), "dev-42", "s3cret")

        val probe = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer s3cret", probe.getHeader("Authorization"))
        assertEquals("dev-42", probe.getHeader(RepeaterTunnel.DEVICE_HEADER))

        Socket(RepeaterTunnel.LOOPBACK_HOST, port).use { vnc ->
            val bridge = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("Bearer s3cret", bridge.getHeader("Authorization"))
            assertEquals("dev-42", bridge.getHeader(RepeaterTunnel.DEVICE_HEADER))
            vnc.soTimeout = 5000
            // Mode-II: the VNC server announces its id first.
            vnc.getOutputStream().write("ID:123456789012345678".toByteArray())
            val up = StringBuilder()
            while (up.length < 21) up.append(received.poll(5, TimeUnit.SECONDS)!!.utf8())
            assertEquals("ID:123456789012345678", up.toString())

            serverSockets.poll(5, TimeUnit.SECONDS)!!.send("RFB 003.008\n".encodeUtf8())
            val buf = ByteArray(12)
            var n = 0
            while (n < 12) n += vnc.getInputStream().read(buf, n, 12 - n)
            assertEquals("RFB 003.008\n", String(buf))
        }
    }

    @Test fun `a large stream arrives whole and in order`() = runBlocking {
        enqueueProbe()
        enqueueBridge()
        val port = tunnel.open(url(), "dev", "s")
        val payload = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
        Socket(RepeaterTunnel.LOOPBACK_HOST, port).use { vnc ->
            vnc.getOutputStream().write(payload)
            val got = java.io.ByteArrayOutputStream()
            while (got.size() < payload.size) got.write(received.poll(5, TimeUnit.SECONDS)!!.toByteArray())
            assertTrue(payload.contentEquals(got.toByteArray()))
        }
    }

    @Test fun `a refused tunnel fails open with the reason`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        try {
            tunnel.open(url(), "dev", "wrong")
            fail("open must throw when the server refuses the tunnel")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("403"))
        }
    }

    @Test fun `a reconnect gets a fresh websocket`() = runBlocking {
        enqueueProbe()
        enqueueBridge()
        enqueueBridge()
        val port = tunnel.open(url(), "dev", "s")
        Socket(RepeaterTunnel.LOOPBACK_HOST, port).use { it.getOutputStream().write("a".toByteArray()) }
        assertEquals("a", received.poll(5, TimeUnit.SECONDS)!!.utf8())
        Socket(RepeaterTunnel.LOOPBACK_HOST, port).use { it.getOutputStream().write("b".toByteArray()) }
        assertEquals("b", received.poll(5, TimeUnit.SECONDS)!!.utf8())
        assertEquals(3, server.requestCount)
    }

    // The repeater drops a device connection that does not send its session id within ~1 s, so the bridge's
    // WebSocket must open only once droidVNC-NG connects (it can take a minute to start after open()).
    @Test fun `the bridge opens only when droidVNC-NG connects`() = runBlocking {
        enqueueProbe()
        enqueueBridge()
        val port = tunnel.open(url(), "dev", "s")
        Thread.sleep(1500)
        assertTrue("no bridge may be open before droidVNC-NG connects", openedAt.isEmpty())
        val connectedAt = System.nanoTime()
        Socket(RepeaterTunnel.LOOPBACK_HOST, port).use { vnc ->
            vnc.getOutputStream().write("ID:123456789012345678".toByteArray())
            val opened = openedAt.poll(5, TimeUnit.SECONDS)!!
            assertTrue(opened >= connectedAt)
            val up = StringBuilder()
            while (up.length < 21) up.append(received.poll(5, TimeUnit.SECONDS)!!.utf8())
            assertEquals("ID:123456789012345678", up.toString())
        }
    }

    @Test fun `close stops listening`() = runBlocking {
        enqueueProbe()
        val port = tunnel.open(url(), "dev", "s")
        tunnel.close()
        try {
            Socket(RepeaterTunnel.LOOPBACK_HOST, port).close()
            fail("the loopback port must be closed")
        } catch (e: IOException) {
            // expected
        }
    }
}
