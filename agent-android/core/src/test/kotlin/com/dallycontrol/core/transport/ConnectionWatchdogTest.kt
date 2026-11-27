package com.dallycontrol.core.transport

import com.dallycontrol.core.net.ConnectionDiagnostics
import com.dallycontrol.core.net.MemoryKeyValueStore
import com.dallycontrol.core.transport.ConnectionWatchdog.Action
import com.dallycontrol.proto.FailureKind
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ConnectionWatchdogTest {
    private val minute = 60_000L
    private val t0 = 10_000_000L

    @Test fun `quiet while check-ins are recent`() {
        val w = ConnectionWatchdog()
        assertEquals(Action.NONE, w.decide(t0, t0 - 2 * minute, networkAvailable = true))
    }

    @Test fun `checks in after five minutes, restarts the transport after eight`() {
        val w = ConnectionWatchdog()
        assertEquals(Action.CHECK_IN, w.decide(t0, t0 - 5 * minute, true))
        assertEquals(Action.RESTART, w.decide(t0, t0 - 8 * minute, true))
    }

    @Test fun `does not restart again right away`() {
        val w = ConnectionWatchdog()
        val last = t0 - 9 * minute
        assertEquals(Action.RESTART, w.decide(t0, last, true))
        assertEquals(Action.CHECK_IN, w.decide(t0 + minute, last, true))
        assertEquals(Action.CHECK_IN, w.decide(t0 + 2 * minute, last, true))
        assertEquals(Action.RESTART, w.decide(t0 + 3 * minute, last, true))
    }

    @Test fun `nothing to do without network`() {
        val w = ConnectionWatchdog()
        assertEquals(Action.NONE, w.decide(t0, t0 - 60 * minute, networkAvailable = false))
    }

    @Test fun `a device that never reported is measured from when the watch began`() {
        val w = ConnectionWatchdog()
        assertEquals(Action.NONE, w.decide(t0, 0L, true))
        assertEquals(Action.CHECK_IN, w.decide(t0 + 5 * minute, 0L, true))
    }
}

class NetworkChangeTrackerTest {
    @Test fun `the first network is not a change, another one is`() {
        val t = NetworkChangeTracker()
        assertFalse(t.onAvailable(1))
        assertFalse(t.onAvailable(1))
        assertTrue(t.onAvailable(2)) // Wi-Fi -> mobile data
    }

    @Test fun `coming back from no network is a change, even to the same network`() {
        val t = NetworkChangeTracker()
        t.onAvailable(1)
        assertTrue(t.onLost(1))
        assertTrue(t.onAvailable(1))
    }

    @Test fun `losing the old network after a hand-over is not a loss`() {
        val t = NetworkChangeTracker()
        t.onAvailable(1)
        t.onAvailable(2)
        assertFalse(t.onLost(1))
    }
}

class TransportReconnectTest {
    private fun accept() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit
    })

    @Test fun `the backoff never waits more than fifteen seconds and is jittered`() {
        val waits = (0..40).map { TransportManager.backoffMs(it, Random(it)) }
        assertTrue(waits.all { it in 500..15_000 })
        assertTrue(waits.drop(6).all { it >= 12_000 })
        assertTrue(waits.drop(6).distinct().size > 1)
        assertTrue(TransportManager.backoffMs(0, Random(1)) <= 1_200)
    }

    @Test fun `reconnectNow opens a new socket at once, skipping the pending backoff`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503)) // first attempt fails -> long backoff pending
        server.enqueue(accept())
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val t = TransportManager(OkHttpClient(), { base }, backoff = { 60_000L })
        try {
            t.start("dev", "s") {}
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            Thread.sleep(300) // let the failure schedule its (one minute) retry
            t.reconnectNow()
            assertNotNull("no immediate reconnection", server.takeRequest(3, TimeUnit.SECONDS))
        } finally {
            t.stop()
            server.shutdown()
        }
    }

    @Test fun `reconnectNow replaces an open socket and counts the reconnection`() {
        val server = MockWebServer()
        repeat(2) { server.enqueue(accept()) }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val diagnostics = ConnectionDiagnostics(MemoryKeyValueStore())
        val t = TransportManager(OkHttpClient(), { base }, diagnostics)
        try {
            t.start("dev", "s") {}
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            waitUntil { t.isOpen }
            t.reconnectNow()
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            waitUntil { diagnostics.pendingReport().reconnects == 1 }
            assertEquals(2, server.requestCount)
        } finally {
            t.stop()
            server.shutdown()
        }
    }

    @Test fun `a refused upgrade is recorded with its status`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val diagnostics = ConnectionDiagnostics(MemoryKeyValueStore())
        val t = TransportManager(OkHttpClient(), { base }, diagnostics, backoff = { 60_000L })
        try {
            t.start("dev", "s") {}
            waitUntil { diagnostics.pendingReport().lastFailure != null }
            val f = diagnostics.pendingReport().lastFailure!!
            assertEquals(FailureKind.WS_UPGRADE, f.kind)
            assertEquals(403, f.httpCode)
            assertEquals(ConnectionDiagnostics.CHANNEL_SOCKET, f.channel)
        } finally {
            t.stop()
            server.shutdown()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in time" }
            Thread.sleep(20)
        }
    }
}
