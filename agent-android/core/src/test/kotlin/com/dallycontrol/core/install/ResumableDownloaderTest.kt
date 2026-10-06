package com.dallycontrol.core.install

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val content = ByteArray(200_000) { (it % 251).toByte() }

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun downloader(tries: Int) = ResumableDownloader(tmp.root, OkHttpClient(), tries) { 0L }

    @Test
    fun `a download cut in one call is resumed by the next call`() {
        // First call: the connection drops halfway (the only try).
        server.enqueue(MockResponse().setBody(Buffer().write(content)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val url = server.url("/app.apk").toString()
        val failed = runCatching { downloader(1).download(url, "abc") }.exceptionOrNull()
        assertTrue(failed is java.io.IOException)
        server.takeRequest()

        val partial = tmp.root.listFiles()!!.single()
        val have = partial.length().toInt()
        assertTrue(have in 1 until content.size)

        // Next call (a later check-in): asks only for the rest.
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(content.copyOfRange(have, content.size))))
        val file = downloader(1).download(url, "abc")
        assertEquals("bytes=$have-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(content, file.readBytes())
    }

    @Test
    fun `a file already whole is not fetched again`() {
        val url = server.url("/app.apk").toString()
        server.enqueue(MockResponse().setBody(Buffer().write(content)))
        val first = downloader(1).download(url, "x")
        server.enqueue(MockResponse().setResponseCode(416))
        val again = downloader(1).download(url, "x")
        assertEquals(first, again)
        assertArrayEquals(content, again.readBytes())
    }

    @Test
    fun `a missing file is dropped and not retried`() {
        val url = server.url("/gone.apk").toString()
        server.enqueue(MockResponse().setResponseCode(404))
        val e = runCatching { downloader(3).download(url, "y") }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals(1, server.requestCount)
        assertFalse(tmp.root.listFiles()!!.any { it.name.startsWith("mdm-install-") })
    }
}
