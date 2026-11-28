package com.dallycontrol.core.enroll

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The time according to the server, read without TLS: a phone whose clock is years off cannot open any HTTPS
 * connection (every certificate looks "not valid yet"), so it cannot enroll and cannot ask anyone the time over
 * HTTPS either. Any web server answers a plain request on port 80 with a `Date` header - usually a redirect to
 * HTTPS - and that header is enough to put the clock back in a range where certificates validate. It is only used
 * to repair a clock that is already failing certificate checks, never to move a clock that works.
 */
object NetworkClock {

    private const val TIMEOUT_MS = 8_000
    private const val HTTP_PORT = 80
    private const val MAX_HEADER_BYTES = 4_096

    /**
     * A host that answers plain HTTP from any network that has internet: the one Android itself asks on every Wi-Fi
     * it joins (its connectivity check). Used when the server has nothing listening on port 80.
     */
    val FALLBACK_HOSTS = listOf("connectivitycheck.gstatic.com")

    /** Epoch millis from the `Date` header of the first of [sources] that answers, or null when none does. */
    fun fetch(serverUrl: String, fallbackHosts: List<String> = FALLBACK_HOSTS): Long? =
        fetchFrom(sources(serverUrl, fallbackHosts))

    /**
     * Where the time is asked, in order: the server's own port when the server is plain HTTP on another port, the
     * server's port 80 (an HTTPS server normally answers there with a redirect), then [fallbackHosts].
     */
    fun sources(serverUrl: String, fallbackHosts: List<String> = FALLBACK_HOSTS): List<Pair<String, Int>> {
        val uri = runCatching { URI(serverUrl.trim()) }.getOrNull()
        val host = uri?.host
        val own = buildList {
            if (host != null) {
                if (uri.scheme.equals("http", ignoreCase = true) && uri.port > 0 && uri.port != HTTP_PORT) {
                    add(host to uri.port)
                }
                add(host to HTTP_PORT)
            }
        }
        return (own + fallbackHosts.map { it to HTTP_PORT }).distinct()
    }

    fun fetchFrom(sources: List<Pair<String, Int>>): Long? =
        sources.firstNotNullOfOrNull { (host, port) -> fetchOne(host, port) }

    private fun fetchOne(host: String, port: Int): Long? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.getOutputStream().apply {
                write("HEAD / HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val buffer = ByteArray(MAX_HEADER_BYTES)
            var read = 0
            var complete = false
            val input = socket.getInputStream()
            while (!complete && read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n > 0) read += n
                complete = n <= 0 || String(buffer, 0, read, Charsets.US_ASCII).contains("\r\n\r\n")
            }
            parseDateHeader(String(buffer, 0, read, Charsets.US_ASCII))
        }
    }.getOrNull()

    /** The `Date:` header of a raw HTTP response head (RFC 7231 format), as epoch millis. */
    fun parseDateHeader(responseHead: String): Long? {
        val line = responseHead.lineSequence().firstOrNull { it.startsWith("date:", ignoreCase = true) } ?: return null
        val value = line.substringAfter(':').trim()
        return runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(value)?.time
        }.getOrNull()
    }

    /** Only a clock that is clearly wrong (more than a day off) is worth setting. */
    fun needsRepair(deviceNow: Long, serverNow: Long): Boolean =
        serverNow > EnrollFailure.EARLIEST_PLAUSIBLE_NOW && kotlin.math.abs(serverNow - deviceNow) > ONE_DAY_MS

    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
}
