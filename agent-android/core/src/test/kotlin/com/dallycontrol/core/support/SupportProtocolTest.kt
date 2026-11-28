package com.dallycontrol.core.support

import com.dallycontrol.core.transport.WakeSignal
import com.dallycontrol.proto.ConfigApplyPayload
import com.dallycontrol.proto.ProtocolJson
import com.dallycontrol.proto.RemoteControlCapability
import com.dallycontrol.proto.SupportCapability
import com.dallycontrol.proto.SupportScreenState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The pure rules of the remote-support session channel (ADR 0012). */
class SupportProtocolTest {

    // --- voice codec ---------------------------------------------------------------------------------------------

    @Test
    fun `mu-law round trip keeps speech within companding error`() {
        for (v in listOf(0, 1, -1, 100, -100, 1_000, -1_000, 8_000, -8_000, 20_000, -20_000, 32_000, -32_000)) {
            val back = MuLaw.decode(MuLaw.encode(v.toShort())).toInt()
            // 8-bit companding: error grows with level, about 3 % of full scale at most.
            assertTrue("$v -> $back", abs(back - v) <= maxOf(16, abs(v) / 16))
        }
    }

    @Test
    fun `mu-law matches the G711 reference bytes`() {
        assertEquals(0xFF.toByte(), MuLaw.encode(0))
        assertEquals(0x7F.toByte(), MuLaw.encode(-1)) // negative zero segment
        assertEquals(0x80.toByte(), MuLaw.encode(Short.MAX_VALUE))
        assertEquals(0x00.toByte(), MuLaw.encode(Short.MIN_VALUE))
        assertEquals(0, MuLaw.decode(0xFF.toByte()).toInt())
    }

    @Test
    fun `test tone is a 440 Hz sine that survives the codec`() {
        val pcm = ShortArray(MuLaw.SAMPLE_RATE) // one second, generated frame by frame like the call does
        val frame = ShortArray(MuLaw.FRAME_SAMPLES)
        var pos = 0L
        while (pos < pcm.size) {
            MuLaw.tone(440, pos, frame, frame.size)
            frame.copyInto(pcm, pos.toInt())
            pos += frame.size
        }
        val encoded = ByteArray(pcm.size).also { MuLaw.encode(pcm, pcm.size, it) }
        val decoded = ShortArray(pcm.size).also { MuLaw.decode(encoded, 0, encoded.size, it) }
        var crossings = 0
        for (i in 1 until decoded.size) if ((decoded[i - 1] < 0) != (decoded[i] < 0)) crossings++
        assertEquals(440.0, crossings / 2.0, 3.0)
        assertEquals(12_000 / Math.sqrt(2.0), MuLaw.rms(decoded, decoded.size).toDouble(), 400.0)
        assertEquals(0, MuLaw.rms(ShortArray(320), 320))
    }

    // --- frames ----------------------------------------------------------------------------------------------------

    @Test
    fun `screen frame header is kind, sequence, width and height big-endian`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val f = SupportProtocol.frame(seq = 0x01020304, width = 540, height = 1200, jpeg = jpeg)
        assertEquals(SupportProtocol.FRAME_HEADER + jpeg.size, f.size)
        assertArrayEquals(byteArrayOf(1, 1, 2, 3, 4, 0x02, 0x1C, 0x04, 0xB0.toByte()), f.copyOfRange(0, 9))
        assertArrayEquals(jpeg, f.copyOfRange(9, f.size))
    }

    @Test
    fun `audio and file chunks carry their kind byte`() {
        assertArrayEquals(byteArrayOf(2, 7, 8), SupportProtocol.audio(byteArrayOf(7, 8)))
        assertArrayEquals(byteArrayOf(3, 9, 5, 6), SupportProtocol.fileChunk(9, byteArrayOf(5, 6, 99), 2))
    }

    @Test
    fun `frames are scaled to the asked size and never upscaled`() {
        assertEquals(432 to 960, SupportProtocol.scaled(1080, 2400, 960))
        assertEquals(960 to 432, SupportProtocol.scaled(2400, 1080, 960)) // landscape
        assertEquals(720 to 1280, SupportProtocol.scaled(720, 1280, 1920)) // already smaller
        val (w, h) = SupportProtocol.scaled(1080, 2340, 777)
        assertTrue(w % 2 == 0 && h % 2 == 0 && h <= 777)
        // A silly request is clamped rather than obeyed.
        assertEquals(SupportProtocol.MIN_DIM, SupportProtocol.scaled(1080, 2400, 1).second)
        assertEquals(20, SupportProtocol.quality(1))
        assertEquals(90, SupportProtocol.quality(100))
        assertEquals(SupportProtocol.DEFAULT_QUALITY, SupportProtocol.quality(null))
    }

    @Test
    fun `touch fractions land inside the screen`() {
        assertEquals(0f, SupportProtocol.toPixel(0.0, 1080))
        assertEquals(1079f, SupportProtocol.toPixel(1.0, 1080))
        assertEquals(539.5f, SupportProtocol.toPixel(0.5, 1080))
        assertEquals(1079f, SupportProtocol.toPixel(7.0, 1080)) // outside the image: clamped
        assertEquals(0f, SupportProtocol.toPixel(-3.0, 1080))
        assertEquals(0f, SupportProtocol.toPixel(Double.NaN, 1080))
    }

    @Test
    fun `received file names cannot leave the folder`() {
        assertEquals("manual.pdf", SupportProtocol.safeName("manual.pdf"))
        assertEquals("passwd", SupportProtocol.safeName("../../etc/passwd"))
        assertEquals("evil.sh", SupportProtocol.safeName("C:\\temp\\evil.sh"))
        assertEquals("hidden", SupportProtocol.safeName(".hidden"))
        assertEquals("archivo", SupportProtocol.safeName(""))
        assertEquals("archivo", SupportProtocol.safeName(null))
        assertEquals("ab", SupportProtocol.safeName("a\u0000b"))
    }

    @Test
    fun `messages are plain json with the type first`() {
        assertEquals("""{"t":"bye"}""", SupportProtocol.message("bye"))
    }

    // --- typing ----------------------------------------------------------------------------------------------------

    @Test
    fun `typed text goes in at the caret`() {
        assertEquals(TextEdit.State("hola mundo", 10), TextEdit.insert("hola ", 5, 5, "mundo"))
        assertEquals(TextEdit.State("hXla", 2), TextEdit.insert("hola", 1, 2, "X")) // replaces the selection
        assertEquals(TextEdit.State("abc", 3), TextEdit.insert("ab", -1, -1, "c")) // no caret reported: at the end
        assertEquals(TextEdit.State("ab", 2), TextEdit.insert("", 0, 0, "ab"))
        assertEquals(TextEdit.State("abX", 3), TextEdit.insert("ab", 9, 9, "X")) // stale caret past the end
    }

    @Test
    fun `backspace removes the selection or one character`() {
        assertEquals(TextEdit.State("hol", 3), TextEdit.backspace("hola", 4, 4))
        assertEquals(TextEdit.State("ha", 1), TextEdit.backspace("hola", 1, 3))
        assertEquals(TextEdit.State("hola", 0), TextEdit.backspace("hola", 0, 0))
        assertEquals(TextEdit.State("a", 1), TextEdit.backspace("a\uD83D\uDE00", 3, 3)) // a whole emoji
        assertEquals(TextEdit.State("", 0), TextEdit.backspace("", -1, -1))
    }

    // --- readiness -------------------------------------------------------------------------------------------------

    @Test
    fun `screen is ready when the service is on or the agent may switch it on`() {
        val s = SupportSettings()
        fun state(sdk: Int, on: Boolean, self: Boolean, settings: SupportSettings = s) =
            SupportReadiness.screen(sdk, settings, serviceOn = on, canSelfEnable = self)
        assertEquals(SupportScreenState.READY, state(34, on = true, self = false))
        assertEquals(SupportScreenState.READY, state(34, on = false, self = true))
        // QR enrollment before the one tap: the console must say "falta autorización".
        assertEquals(SupportScreenState.NEEDS_AUTHORIZATION, state(34, on = false, self = false))
        assertEquals(SupportScreenState.UNSUPPORTED, state(23, on = true, self = true))
        val vncOnly = SupportSettings.of("droidvnc", null, null, null)
        assertEquals(SupportScreenState.DISABLED, state(34, on = true, self = true, settings = vncOnly))
    }

    @Test
    fun `capture method follows the android release`() {
        assertEquals("a11y", SupportReadiness.capture(31))
        assertEquals("a11y", SupportReadiness.capture(36))
        // Android 11 has accessibility screenshots, but only one a second: screen capture is preferred there.
        assertEquals("projection", SupportReadiness.capture(30))
        assertEquals("projection", SupportReadiness.capture(29))
        assertEquals("projection", SupportReadiness.capture(24))
        assertEquals("none", SupportReadiness.capture(23))
    }

    @Test
    fun `policy settings default to notice on and calls accepted by the person`() {
        val d = SupportSettings.of(null, null, null, null)
        assertEquals(SupportSettings.ENGINE_AUTO, d.engine)
        assertTrue(d.notice && d.setupPrompt && d.builtinAllowed)
        assertEquals(SupportSettings.CALL_ASK, d.callAccept)
        val custom = SupportSettings.of("builtin", false, "auto", false)
        assertFalse(custom.notice)
        assertEquals(SupportSettings.CALL_AUTO, custom.callAccept)
        assertFalse(custom.setupPrompt)
        // Unknown values from a newer server fall back to the defaults.
        assertEquals(SupportSettings(), SupportSettings.of("hologram", null, "shout", null))
    }

    // --- compatibility ---------------------------------------------------------------------------------------------

    @Test
    fun `support wake names the session and older wakes still parse`() {
        assertEquals(WakeSignal("support", sessionId = "0123456789abcdef01234567"),
            WakeSignal.parse("""{"wake":"support","sessionId":"0123456789abcdef01234567"}"""))
        assertEquals(WakeSignal("commands"), WakeSignal.parse("""{"wake":"commands"}"""))
        assertEquals(WakeSignal("interactive", 120), WakeSignal.parse("""{"wake":"interactive","ttlSec":120}"""))
        assertTrue(SupportChannel.isSessionId("0123456789abcdef01234567"))
        assertFalse(SupportChannel.isSessionId("../admin"))
        assertFalse(SupportChannel.isSessionId(null))
        assertEquals("https://mdm.example.com/agent/ws/dev-1/support/0123456789abcdef01234567",
            SupportChannel.url("https://mdm.example.com/", "dev-1", "0123456789abcdef01234567"))
    }

    @Test
    fun `a configuration from an older server has no remote support section`() {
        val json = ProtocolJson.json
        val old = json.decodeFromString(ConfigApplyPayload.serializer(), """{"revision":"r1","policies":{}}""")
        assertNull(old.remoteSupport)
        val new = json.decodeFromString(
            ConfigApplyPayload.serializer(),
            """{"revision":"r2","remoteSupport":{"callAccept":"auto","futureKey":1}}""",
        )
        assertEquals("auto", new.remoteSupport?.callAccept)
        assertNull(new.remoteSupport?.engine)
    }

    @Test
    fun `capability report keeps the fields older servers read`() {
        val cap = RemoteControlCapability(
            tier = "control", screenCapture = true, inputInjection = true, transport = listOf("support-ws"),
            support = SupportCapability(
                screen = SupportScreenState.READY, control = true, voice = "ready", capture = "a11y",
            ),
        )
        val json = ProtocolJson.json.encodeToString(RemoteControlCapability.serializer(), cap)
        assertTrue(json, json.contains(""""tier":"control""""))
        assertTrue(json, json.contains(""""support":{"""))
        assertTrue(json, json.contains(""""screen":"ready""""))
        // An agent without the engine section serialises exactly as before.
        val plain = ProtocolJson.json.encodeToString(RemoteControlCapability.serializer(), RemoteControlCapability())
        assertFalse(plain.contains("support"))
    }
}
