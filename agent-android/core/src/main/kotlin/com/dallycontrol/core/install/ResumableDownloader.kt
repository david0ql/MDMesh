package com.dallycontrol.core.install

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Big APKs over a shaky mobile link: up to [tries] attempts that resume where the last one stopped (Range), and one
 * file per (url, expected content) kept between calls — a download cut short (the phone lost internet, the system cut
 * the app's network with the screen off) is resumed by the next check-in instead of starting over.
 */
class ResumableDownloader(
    private val dir: File,
    private val client: OkHttpClient,
    private val tries: Int,
    private val pauseMs: (attempt: Int) -> Long = { 2_000L * it },
) {
    fun download(url: String, expectedSha: String): File {
        val key = MessageDigest.getInstance("SHA-1").digest("$url|$expectedSha".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(20)
        val dest = File(dir, "mdm-install-$key.apk")
        val staleBefore = System.currentTimeMillis() - STALE_MS
        dir.listFiles { f -> f.name.startsWith("mdm-install-") && f != dest && f.lastModified() < staleBefore }
            ?.forEach { it.delete() }
        var lastError: Throwable? = null
        for (attempt in 1..tries) {
            try {
                val have = if (dest.exists()) dest.length() else 0L
                val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                client.newCall(req).execute().use { response ->
                    // Already whole from an earlier try (cut before installing): nothing left to fetch.
                    if (have > 0 && response.code == 416) return dest
                    if (!response.isSuccessful) error("HTTP ${response.code} for $url")
                    val resumed = have > 0 && response.code == 206
                    val body = response.body ?: error("empty response body for $url")
                    body.byteStream().use { input -> FileOutputStream(dest, resumed).use { input.copyTo(it) } }
                }
                return dest
            } catch (e: Throwable) {
                lastError = e
                if (isNotThere(e)) break // not found / forbidden: no retry
                Thread.sleep(pauseMs(attempt))
            }
        }
        // Keep what arrived for the next try, unless the server said the file is not there (4xx).
        if (lastError != null && isNotThere(lastError)) dest.delete()
        throw lastError ?: IllegalStateException("download failed")
    }

    private fun isNotThere(e: Throwable) = e is IllegalStateException && e.message?.startsWith("HTTP 4") == true

    private companion object {
        const val STALE_MS = 2 * 24 * 3600_000L
    }
}
