package com.dallycontrol.core.transport

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class TransportManagerTest {
    private val server = MockWebServer()

    @After fun tearDown() = server.shutdown()

    private fun acceptWebSocket() = server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {}
    }))

    @Test fun `new credentials reconnect under the new device id, once`() {
        repeat(3) { acceptWebSocket() }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val t = TransportManager(OkHttpClient(), { base })

        t.start("old-device", "s1") {}
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/agent/ws/old-device", first.path)
        assertEquals("Bearer s1", first.getHeader("Authorization"))

        t.start("old-device", "s1") {} // same credentials: nothing happens
        t.start("new-device", "s2") {} // re-enrolled: reconnect with the new ones
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/agent/ws/new-device", second.path)
        assertEquals("Bearer s2", second.getHeader("Authorization"))

        // The replaced socket's cancellation must not trigger a reconnect (a second, duplicate socket).
        assertNull(server.takeRequest(3, TimeUnit.SECONDS))
        t.stop()
    }
}
