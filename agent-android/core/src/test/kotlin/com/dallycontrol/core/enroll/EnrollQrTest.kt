package com.dallycontrol.core.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollQrTest {

    private val console = """{"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":"com.dallycontrol.agent/x",
        "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":"https://mdm.example.com/files/a.apk",
        "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE":{"com.dallycontrol.ENROLL_TOKEN":"tok-1",
        "com.dallycontrol.SERVER_URL":"https://mdm.example.com/"},
        "android.app.extra.PROVISIONING_LOCAL_TIME":1795800005123,
        "android.app.extra.PROVISIONING_WIFI_HIDDEN":true}"""

    @Test
    fun `reads token and server from the console's provisioning QR`() {
        assertEquals(EnrollQr("tok-1", "https://mdm.example.com"), EnrollQr.parse(console))
    }

    @Test
    fun `reads the bare admin extras (the KME JSON)`() {
        val kme = """{"com.dallycontrol.ENROLL_TOKEN":"ABCDEFGH","com.dallycontrol.SERVER_URL":"http://10.0.2.2:8088"}"""
        assertEquals(EnrollQr("ABCDEFGH", "http://10.0.2.2:8088"), EnrollQr.parse(kme))
    }

    @Test
    fun `a folder code written as text has no server`() {
        assertEquals(EnrollQr("ABCD-EFGH", null), EnrollQr.parse(" ABCD-EFGH "))
        assertEquals(EnrollQr("ABCDEFGH", null), EnrollQr.parse("ABCDEFGH"))
    }

    @Test
    fun `anything else is not an enrollment QR`() {
        assertNull(EnrollQr.parse(null))
        assertNull(EnrollQr.parse(""))
        assertNull(EnrollQr.parse("WIFI:S:Oficina;T:WPA;P:clave;;"))
        assertNull(EnrollQr.parse("https://example.com/algo"))
        assertNull(EnrollQr.parse("{not json"))
        assertNull(EnrollQr.parse("""{"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE":{}}"""))
        assertNull(EnrollQr.parse("""{"com.dallycontrol.ENROLL_TOKEN":"  "}"""))
    }

    @Test
    fun `a server that is not http is dropped, the token is kept`() {
        val q = EnrollQr.parse("""{"com.dallycontrol.ENROLL_TOKEN":"t","com.dallycontrol.SERVER_URL":"javascript:alert(1)"}""")
        assertEquals(EnrollQr("t", null), q)
    }
}
