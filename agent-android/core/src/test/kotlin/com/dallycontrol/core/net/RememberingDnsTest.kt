package com.dallycontrol.core.net

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class RememberingDnsTest {
    private class MapStore : RememberingDns.Store {
        val m = HashMap<String, List<String>>()
        override fun get(host: String) = m[host].orEmpty()
        override fun put(host: String, addresses: List<String>) { m[host] = addresses }
    }

    @Test
    fun `when the carrier DNS fails the last good answer is used`() {
        var up = true
        val system = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!up) throw UnknownHostException("Unable to resolve host \"$hostname\"")
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(51, 81.toByte(), 34, 1)))
            }
        }
        val dns = RememberingDns(MapStore(), system)
        dns.lookup("mdm.example.com")
        up = false

        val got = dns.lookup("mdm.example.com")

        assertEquals("51.81.34.1", got.single().hostAddress)
        assertEquals("mdm.example.com", got.single().hostName)
    }

    @Test
    fun `with nothing remembered the failure stands`() {
        val dns = RememberingDns(MapStore(), object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("nope")
        })
        assertTrue(runCatching { dns.lookup("x.example.com") }.exceptionOrNull() is UnknownHostException)
    }
}
