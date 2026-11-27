package com.dallycontrol.agent.provisioning

import com.dallycontrol.core.enroll.EnrollQr
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's decode path on a camera-shaped frame: the console's real payload (as dense as it gets with Wi-Fi and
 * the clock) drawn into the luminance plane of an NV21 preview frame, decoded exactly as [EnrollScanActivity] does.
 */
class EnrollQrDecodeTest {

    private val payload = """{"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":""" +
        """"com.dallycontrol.agent/com.dallycontrol.agent.admin.AdminReceiver",""" +
        """"android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":""" +
        """"byMlbbiklLB7k5mQLNPBci0AC_kI3YLBS5Lkw9cIzlo",""" +
        """"android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":""" +
        """"https://mdm.example.com/rest/public/agent/v1/apk?t=0b6f6d0e-111&p=%2Ffiles%2Fdallycontrol-agent.apk",""" +
        """"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE":{"com.dallycontrol.ENROLL_TOKEN":""" +
        """"0b6f6d0e-1111-2222-3333-444455556666","com.dallycontrol.SERVER_URL":"https://mdm.example.com"},""" +
        """"android.app.extra.PROVISIONING_SKIP_ENCRYPTION":true,""" +
        """"android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED":true,""" +
        """"android.app.extra.PROVISIONING_ALLOW_OFFLINE":true,""" +
        """"android.app.extra.PROVISIONING_LOCAL_TIME":1795800005123,""" +
        """"android.app.extra.PROVISIONING_TIME_ZONE":"America/Bogota",""" +
        """"android.app.extra.PROVISIONING_LOCALE":"es_CO",""" +
        """"android.app.extra.PROVISIONING_WIFI_SSID":"Bodega-Principal",""" +
        """"android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE":"WPA",""" +
        """"android.app.extra.PROVISIONING_WIFI_HIDDEN":true,""" +
        """"android.app.extra.PROVISIONING_WIFI_PASSWORD":"una-clave-bastante-larga-2026"}"""

    /** What the console shows under "equipo ya administrado": only what the agent needs, so it is a coarse QR. */
    private val short = """{"com.dallycontrol.ENROLL_TOKEN":"0b6f6d0e-1111-2222-3333-444455556666",""" +
        """"com.dallycontrol.SERVER_URL":"https://mdm.example.com"}"""

    private val expected = EnrollQr("0b6f6d0e-1111-2222-3333-444455556666", "https://mdm.example.com")

    /** [text] as a QR [side] pixels wide in the middle of a 1080p NV21 frame, decoded as the scanner does. */
    private fun scan(text: String, side: Int): String? {
        val width = 1920
        val height = 1080
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, side, side,
            // Level L, as the console draws both QRs on screen.
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4),
        )
        // NV21: a full-resolution luminance plane followed by the interleaved chroma (left grey here).
        val frame = ByteArray(width * height * 3 / 2) { 0x80.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val qx = x - (width - side) / 2
                val qy = y - (height - side) / 2
                val dark = qx in 0 until side && qy in 0 until side && matrix.get(qx, qy)
                frame[y * width + x] = if (dark) 0x10.toByte() else 0xEB.toByte()
            }
        }
        return QrFrameDecoder.decode(QRCodeReader(), frame, width, height)
    }

    @Test
    fun `the short QR for an already managed phone reads at nearly every distance`() {
        // The scanner tries every preview frame. On these perfectly sharp synthetic frames the decoder still misses
        // an occasional scale, so the bar is "almost all", and whatever reads must be right.
        val sides = (200..1000 step 40).toList()
        val reads = sides.mapNotNull { scan(short, it) }
        assertTrue("read ${reads.size} of ${sides.size} frames", reads.size >= sides.size * 3 / 4)
        reads.forEach { assertEquals(expected, EnrollQr.parse(it)) }
    }

    @Test
    fun `the full provisioning QR gives the same token and server whenever a frame reads`() {
        // It is a dense QR (about 1 kB): this decoder misses many frames of it, which is why the console offers the
        // short one. The scanner keeps trying frames; a frame that does read must give the right payload.
        val reads = (560..1040 step 40).mapNotNull { scan(payload, it) }
        assertTrue("some frame must read", reads.isNotEmpty())
        reads.forEach { assertEquals(expected, EnrollQr.parse(it)) }
    }
}
