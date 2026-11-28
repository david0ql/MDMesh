package com.dallycontrol.agent.support

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.dallycontrol.core.support.MuLaw
import com.dallycontrol.core.support.SupportProtocol
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The phone's side of a support voice call (ADR 0012): microphone → 20 ms µ-law frames → the session socket, and
 * the operator's frames → the loudspeaker. Android's communication audio path gives echo cancellation, noise
 * suppression and gain control; no codec or WebRTC library is bundled (the agent APK stays under 2 MB).
 *
 * [testTone] (debug builds only) sends a 440 Hz tone instead of the microphone, so the end-to-end test can tell
 * real audio from silence on an emulator without a microphone.
 */
class VoiceCall(
    private val context: Context,
    private val testTone: Boolean,
    private val send: (ByteArray) -> Boolean,
    private val onStats: (Stats) -> Unit,
) {
    data class Stats(
        val txFrames: Long,
        val rxFrames: Long,
        val micRms: Int,
        val rxRms: Int,
        val recording: Boolean,
        val muted: Boolean,
    )

    @Volatile private var running = false
    @Volatile var muted = false
    @Volatile private var txFrames = 0L
    @Volatile private var rxFrames = 0L
    @Volatile private var micRms = 0
    @Volatile private var rxRms = 0
    @Volatile private var recording = false
    private val incoming = ArrayBlockingQueue<ByteArray>(JITTER_FRAMES)
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    /** Start capturing and playing. Returns an error text, or null when the call is up. */
    @SuppressLint("MissingPermission") // checked by the caller (SupportEngine.grantMicrophone)
    // One audio set-up from top to bottom, each step with its own way to fail.
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    fun start(): String? {
        if (running) return null
        val audio = context.getSystemService(AudioManager::class.java)
        previousMode = audio.mode
        @Suppress("DEPRECATION") run { previousSpeaker = audio.isSpeakerphoneOn }
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            // Loudspeaker: the person is looking at the screen with support, not holding the phone to the ear.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.availableCommunicationDevices
                    .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { audio.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = true }
            }
        }
        val pcm16 = AudioFormat.ENCODING_PCM_16BIT
        val minRecord = AudioRecord.getMinBufferSize(MuLaw.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, pcm16)
        val minTrack = AudioTrack.getMinBufferSize(MuLaw.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, pcm16)
        if (minTrack <= 0) return "audio output unavailable"
        val out = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(MuLaw.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(maxOf(minTrack, MuLaw.FRAME_SAMPLES * 2 * TRACK_FRAMES))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrElse { return "audio output: ${it.message}" }
        track = out
        if (!testTone) {
            if (minRecord <= 0) { release(); return "microphone unavailable" }
            val rec = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, MuLaw.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minRecord, MuLaw.FRAME_SAMPLES * 2 * RECORD_FRAMES),
                )
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release()
                release()
                return "microphone could not be opened"
            }
            record = rec
            val session = rec.audioSessionId
            // The platform's own echo canceller, noise suppressor and gain control, where the phone has them.
            keep { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null }
            keep { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null }
            keep { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session) else null }
        }
        running = true
        runCatching { out.play() }
        Thread(::captureLoop, "support-call-tx").apply { isDaemon = true }.start()
        Thread(::playLoop, "support-call-rx").apply { isDaemon = true }.start()
        Thread(::statsLoop, "support-call-stats").apply { isDaemon = true }.start()
        return null
    }

    private fun keep(create: () -> android.media.audiofx.AudioEffect?) {
        runCatching { create()?.also { it.enabled = true; effects += it } }
    }

    /** A frame from the operator: `[2][µ-law…]`. Dropped when the phone falls behind (latency over smoothness). */
    fun onAudio(frame: ByteArray, offset: Int, length: Int) {
        if (!running || length <= 0) return
        val copy = frame.copyOfRange(offset, offset + length)
        while (!incoming.offer(copy)) incoming.poll()
    }

    @Suppress("NestedBlockDepth") // the read loop inside the frame loop
    private fun captureLoop() {
        val pcm = ShortArray(MuLaw.FRAME_SAMPLES)
        val encoded = ByteArray(MuLaw.FRAME_SAMPLES)
        val rec = record
        runCatching { rec?.startRecording() }.onFailure { Log.w(TAG, "startRecording failed", it) }
        recording = rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING
        var position = 0L
        var nextAt = System.nanoTime()
        while (running) {
            if (rec != null) {
                var read = 0
                while (running && read < pcm.size) {
                    val n = rec.read(pcm, read, pcm.size - read)
                    if (n <= 0) { read = -1; break }
                    read += n
                }
                if (read < 0) {
                    recording = false
                    Thread.sleep(FRAME_MS)
                    java.util.Arrays.fill(pcm, 0)
                }
            } else {
                // Test tone, paced like a microphone.
                MuLaw.tone(TONE_HZ, position, pcm, pcm.size)
                position += pcm.size
                nextAt += FRAME_MS * NANOS_PER_MS
                val wait = (nextAt - System.nanoTime()) / NANOS_PER_MS
                if (wait > 0) Thread.sleep(wait)
            }
            if (muted) java.util.Arrays.fill(pcm, 0)
            micRms = MuLaw.rms(pcm, pcm.size)
            MuLaw.encode(pcm, pcm.size, encoded)
            if (send(SupportProtocol.audio(encoded))) txFrames++
        }
    }

    private fun playLoop() {
        val pcm = ShortArray(MAX_FRAME)
        val out = track ?: return
        while (running) {
            val frame = incoming.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
            val n = minOf(frame.size, pcm.size)
            MuLaw.decode(frame, 0, n, pcm)
            rxRms = MuLaw.rms(pcm, n)
            rxFrames++
            runCatching { out.write(pcm, 0, n) }
        }
    }

    private fun statsLoop() {
        while (running) {
            Thread.sleep(STATS_MS)
            if (running) onStats(stats())
        }
    }

    fun stats() = Stats(txFrames, rxFrames, micRms, rxRms, recording || testTone, muted)

    fun stop() {
        if (!running) return
        running = false
        release()
    }

    private fun release() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { track?.pause(); track?.flush(); track?.release() }
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        record = null
        track = null
        incoming.clear()
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            else @Suppress("DEPRECATION") run { audio.isSpeakerphoneOn = previousSpeaker }
            audio.mode = previousMode
        }
    }

    private companion object {
        const val TAG = "SupportCall"
        const val FRAME_MS = 20L
        const val NANOS_PER_MS = 1_000_000L
        /** 200 ms of the operator's audio at most waits to be played. */
        const val JITTER_FRAMES = 10
        const val TRACK_FRAMES = 6
        const val RECORD_FRAMES = 4
        const val MAX_FRAME = 1_920
        const val POLL_MS = 200L
        const val STATS_MS = 1_000L
        const val TONE_HZ = 440
    }
}
