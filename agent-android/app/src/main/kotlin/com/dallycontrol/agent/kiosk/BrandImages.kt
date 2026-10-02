package com.dallycontrol.agent.kiosk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Kiosk branding images (the logos above and below the apps): downloaded once per URL into the app's files, so the
 * kiosk shows them offline and at once after the first time. A changed logo has a new URL (uploads are time-stamped).
 */
object BrandImages {
    private const val MAX_BYTES = 5L * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())

    /** Show [url] in [view]: from the cache if it is there, else after downloading it (the view stays empty meanwhile). */
    fun into(view: ImageView, url: String) {
        val file = File(File(view.context.filesDir, "kiosk-brand").apply { mkdirs() }, sha1(url))
        view.tag = url
        decode(file, view.resources.displayMetrics.widthPixels)?.let { view.setImageBitmap(it); return }
        thread(name = "kiosk-brand") {
            val bmp = runCatching { download(url, file); decode(file, view.resources.displayMetrics.widthPixels) }.getOrNull()
            if (bmp != null) main.post { if (view.tag == url) view.setImageBitmap(bmp) }
        }
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) return
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { tmp.delete(); return }
                        out.write(buf, 0, n)
                    }
                }
            }
            tmp.renameTo(dest)
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(file: File, screenWidth: Int): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) { file.delete(); return null } // not an image: fetch again next time
        var sample = 1
        while (bounds.outWidth / sample > screenWidth.coerceAtLeast(480) * 2) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
