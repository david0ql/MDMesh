package com.dallycontrol.core.support

import com.dallycontrol.proto.SupportScreenState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The remote-support session channel (ADR 0012), device side. One WebSocket per session carries small JSON text
 * messages (`{"t": "<type>", …}`) and binary messages whose first byte names the content. The server relays them
 * to the operator's console untouched.
 *
 * Console → device: `info.get`, `screen.start {maxDim, quality}`, `screen.set`, `screen.stop`, `ack {seq}`,
 * `tap {x, y}`,
 * `swipe {pts: [[x, y, tMs]…]}`, `key {k}`, `text {s}`, `chat {s}`, `call.start {accept?, testTone?}`, `call.end`,
 * `clip.set {s}`, `clip.get`, `file.put {id, name, size}` + chunks + `file.end {id}`, `file.list {path}`,
 * `file.get {id, path}`, `bye`. Coordinates are fractions (0..1) of the screen.
 *
 * Device → console: `info {…}`, frames, `chat {s}`, `call.state {s}`, `call.stats {…}`, `clip {s}`,
 * `file.entries {…}`, `file.put`/chunks/`file.end`, `file.saved {id, path}`, `error {what, detail}`.
 */
@Suppress("MagicNumber", "TooManyFunctions") // byte layout of the frames; small pure helpers
object SupportProtocol {
    const val BIN_FRAME: Byte = 1
    const val BIN_AUDIO: Byte = 2
    const val BIN_FILE: Byte = 3

    const val FRAME_HEADER = 9
    /** File chunks: small enough to interleave with audio without a gap. */
    const val FILE_CHUNK = 48 * 1024
    const val MAX_UPLOAD_BYTES = 200L * 1024 * 1024

    /** `[1][seq u32][width u16][height u16][jpeg…]`, big-endian. */
    fun frame(seq: Int, width: Int, height: Int, jpeg: ByteArray, length: Int = jpeg.size): ByteArray {
        val out = ByteArray(FRAME_HEADER + length)
        out[0] = BIN_FRAME
        out[1] = (seq ushr 24).toByte()
        out[2] = (seq ushr 16).toByte()
        out[3] = (seq ushr 8).toByte()
        out[4] = seq.toByte()
        out[5] = (width ushr 8).toByte()
        out[6] = width.toByte()
        out[7] = (height ushr 8).toByte()
        out[8] = height.toByte()
        System.arraycopy(jpeg, 0, out, FRAME_HEADER, length)
        return out
    }

    /** `[2][µ-law samples…]`. */
    fun audio(mulaw: ByteArray, length: Int = mulaw.size): ByteArray {
        val out = ByteArray(1 + length)
        out[0] = BIN_AUDIO
        System.arraycopy(mulaw, 0, out, 1, length)
        return out
    }

    /** `[3][transfer id u8][bytes…]`. */
    fun fileChunk(id: Int, data: ByteArray, length: Int): ByteArray {
        val out = ByteArray(2 + length)
        out[0] = BIN_FILE
        out[1] = id.toByte()
        System.arraycopy(data, 0, out, 2, length)
        return out
    }

    fun message(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): String =
        buildJsonObject {
            put("t", type)
            build()
        }.toString()

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** A fraction of the screen (0..1) as a pixel inside it. */
    fun toPixel(fraction: Double, size: Int): Float {
        val f = if (fraction.isNaN()) 0.0 else fraction.coerceIn(0.0, 1.0)
        return (f * (size - 1).coerceAtLeast(0)).toFloat()
    }

    /** The frame size that fits [maxDim] on its longer side, never upscaled, both sides even (JPEG-friendly). */
    fun scaled(width: Int, height: Int, maxDim: Int): Pair<Int, Int> {
        val longer = maxOf(width, height)
        val limit = maxDim.coerceIn(MIN_DIM, MAX_DIM)
        if (longer <= limit) return width to height
        val w = (width.toLong() * limit / longer).toInt().coerceAtLeast(2)
        val h = (height.toLong() * limit / longer).toInt().coerceAtLeast(2)
        return (w and 1.inv()) to (h and 1.inv())
    }

    const val MIN_DIM = 240
    const val MAX_DIM = 1920
    const val DEFAULT_DIM = 960
    const val DEFAULT_QUALITY = 55

    fun quality(asked: Int?): Int = (asked ?: DEFAULT_QUALITY).coerceIn(20, 90)

    /** A file name safe to create in a folder: no path, no control characters, never empty. */
    fun safeName(name: String?): String {
        val base = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().trimStart('.')
        return base.take(MAX_NAME).ifEmpty { "archivo" }
    }

    private const val MAX_NAME = 120
}

/** What the phone's policy says about remote support (ConfigRemoteSupport), with the defaults filled in. */
data class SupportSettings(
    val engine: String = ENGINE_AUTO,
    val notice: Boolean = true,
    val callAccept: String = CALL_ASK,
    val setupPrompt: Boolean = true,
) {
    /** The agent's own screen service may exist on this phone. */
    val builtinAllowed: Boolean get() = engine != ENGINE_DROIDVNC

    companion object {
        const val ENGINE_AUTO = "auto"
        const val ENGINE_BUILTIN = "builtin"
        const val ENGINE_DROIDVNC = "droidvnc"
        const val CALL_ASK = "ask"
        const val CALL_AUTO = "auto"

        fun of(engine: String?, notice: Boolean?, callAccept: String?, setupPrompt: Boolean?) = SupportSettings(
            engine = when (engine) { ENGINE_BUILTIN, ENGINE_DROIDVNC -> engine; else -> ENGINE_AUTO },
            notice = notice != false,
            callAccept = if (callAccept == CALL_AUTO) CALL_AUTO else CALL_ASK,
            setupPrompt = setupPrompt != false,
        )
    }
}

/** Whether the agent's own screen service can serve sessions on this phone, and what is missing if not. */
object SupportReadiness {
    /** dispatchGesture: Android 7. */
    const val MIN_SDK_CONTROL = 24
    /** AccessibilityService.takeScreenshot: Android 11. */
    const val MIN_SDK_A11Y_CAPTURE = 30

    /**
     * @param serviceOn the agent's accessibility service is switched on in the phone's settings
     * @param canSelfEnable the agent may switch it on itself (WRITE_SECURE_SETTINGS, given by the USB enroller)
     */
    fun screen(sdk: Int, settings: SupportSettings, serviceOn: Boolean, canSelfEnable: Boolean): String = when {
        !settings.builtinAllowed -> SupportScreenState.DISABLED
        sdk < MIN_SDK_CONTROL -> SupportScreenState.UNSUPPORTED
        serviceOn || canSelfEnable -> SupportScreenState.READY
        else -> SupportScreenState.NEEDS_AUTHORIZATION
    }

    /** How frames are captured: accessibility screenshots, or screen capture with its consent tapped by the service. */
    fun capture(sdk: Int): String = when {
        sdk >= MIN_SDK_A11Y_CAPTURE -> "a11y"
        sdk >= MIN_SDK_CONTROL -> "projection"
        else -> "none"
    }
}

/** Typing into a text field through accessibility: the field's new content and caret after one edit. */
object TextEdit {
    data class State(val text: String, val caret: Int)

    /** Insert [typed] over the selection [start, end) of [current]. */
    fun insert(current: String, start: Int, end: Int, typed: String): State {
        val (a, b) = range(current, start, end)
        return State(current.substring(0, a) + typed + current.substring(b), a + typed.length)
    }

    /** Backspace: delete the selection, or the character before the caret (a whole surrogate pair). */
    fun backspace(current: String, start: Int, end: Int): State {
        val (a, b) = range(current, start, end)
        return when {
            a != b -> State(current.substring(0, a) + current.substring(b), a)
            a == 0 -> State(current, 0)
            else -> {
                val pair = a >= 2 &&
                    Character.isLowSurrogate(current[a - 1]) && Character.isHighSurrogate(current[a - 2])
                val cut = if (pair) 2 else 1
                State(current.substring(0, a - cut) + current.substring(a), a - cut)
            }
        }
    }

    /** A missing selection (-1) means "at the end", as accessibility reports for a field never touched. */
    private fun range(current: String, start: Int, end: Int): Pair<Int, Int> {
        val len = current.length
        val s = if (start < 0) len else start.coerceAtMost(len)
        val e = if (end < 0) len else end.coerceAtMost(len)
        return minOf(s, e) to maxOf(s, e)
    }
}
