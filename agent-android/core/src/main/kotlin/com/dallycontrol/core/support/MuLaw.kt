package com.dallycontrol.core.support

/**
 * G.711 µ-law: one byte per 16-bit sample. The voice channel of a support session (ADR 0012) carries 20 ms
 * frames of 16 kHz mono speech encoded this way (128 kbit/s): no native codec to bundle, every Android release
 * and every browser can do it, and companding keeps speech clear at 8 bits.
 */
@Suppress("MagicNumber") // the G.711 bit layout (sign, 3-bit segment, 4-bit step), as in the standard
object MuLaw {
    const val SAMPLE_RATE = 16_000
    /** Samples (and bytes on the wire) in one 20 ms frame. */
    const val FRAME_SAMPLES = 320

    private const val BIAS = 0x84
    private const val CLIP = 32_635

    fun encode(sample: Short): Byte {
        var pcm = sample.toInt()
        val sign = if (pcm < 0) 0x80 else 0
        if (pcm < 0) pcm = -pcm
        if (pcm > CLIP) pcm = CLIP
        pcm += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && pcm and mask == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (pcm shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun decode(byte: Byte): Short {
        val u = byte.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (sign != 0) -magnitude else magnitude).toShort()
    }

    /** Encode [count] samples of [pcm] into [out] starting at [outOffset]. */
    fun encode(pcm: ShortArray, count: Int, out: ByteArray, outOffset: Int = 0) {
        for (i in 0 until count) out[outOffset + i] = encode(pcm[i])
    }

    /** Decode [count] bytes of [data] from [offset] into [out]. */
    fun decode(data: ByteArray, offset: Int, count: Int, out: ShortArray) {
        for (i in 0 until count) out[i] = decode(data[offset + i])
    }

    /** Root mean square of the first [count] samples (0..32767): how loud a frame is. */
    fun rms(pcm: ShortArray, count: Int): Int {
        if (count <= 0) return 0
        var sum = 0.0
        for (i in 0 until count) sum += pcm[i].toDouble() * pcm[i].toDouble()
        return Math.sqrt(sum / count).toInt()
    }

    /** A sine tone frame, continuing at sample [position]: what a debug build sends instead of the microphone. */
    fun tone(frequencyHz: Int, position: Long, out: ShortArray, count: Int, amplitude: Int = 12_000) {
        val step = 2.0 * Math.PI * frequencyHz / SAMPLE_RATE
        for (i in 0 until count) out[i] = (amplitude * Math.sin(step * (position + i))).toInt().toShort()
    }
}
