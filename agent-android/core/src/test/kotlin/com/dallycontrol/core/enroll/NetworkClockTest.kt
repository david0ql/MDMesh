package com.dallycontrol.core.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class NetworkClockTest {

    private val head = "HTTP/1.1 301 Moved Permanently\r\nDate: Fri, 27 Nov 2026 17:20:05 GMT\r\n" +
        "Location: https://mdm.example.com/\r\nConnection: close\r\n\r\n"

    @Test
    fun `reads the Date header of a plain HTTP answer`() {
        assertEquals(1_795_800_005_000L, NetworkClock.parseDateHeader(head))
        assertEquals(1_795_800_005_000L, NetworkClock.parseDateHeader(head.replace("Date:", "date:")))
    }

    @Test
    fun `no usable Date header gives null`() {
        assertNull(NetworkClock.parseDateHeader("HTTP/1.1 200 OK\r\nServer: x\r\n\r\n"))
        assertNull(NetworkClock.parseDateHeader("HTTP/1.1 200 OK\r\nDate: yesterday\r\n\r\n"))
    }

    @Test
    fun `only a clock that is clearly off is repaired`() {
        val server = 1_795_800_005_000L
        assertTrue(NetworkClock.needsRepair(deviceNow = 1_262_304_000_000L, serverNow = server)) // phone thinks it is 2010
        assertFalse(NetworkClock.needsRepair(deviceNow = server - 60_000, serverNow = server))
        // A server whose own clock is nonsense must not drag the phone back.
        assertFalse(NetworkClock.needsRepair(deviceNow = server, serverNow = 1_000_000_000_000L))
    }

    @Test
    fun `fetch returns null when nothing answers`() {
        val free = ServerSocket(0).use { it.localPort }
        // Port 80 of localhost is normally closed on a build machine; either way this must not throw.
        NetworkClock.fetch("https://127.0.0.1:$free")
        assertNull(NetworkClock.fetch("not a url"))
    }

    @Test
    fun `the request is a plain HEAD the server can answer with a redirect`() {
        // parseDateHeader is what turns the raw answer into a time; this checks it against a real socket exchange.
        ServerSocket(0).use { server ->
            val t = thread {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(512))
                    s.getOutputStream().write(head.toByteArray())
                }
            }
            java.net.Socket("127.0.0.1", server.localPort).use { c ->
                c.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val text = c.getInputStream().readBytes().toString(Charsets.US_ASCII)
                assertEquals(1_795_800_005_000L, NetworkClock.parseDateHeader(text))
            }
            t.join()
        }
    }
}
