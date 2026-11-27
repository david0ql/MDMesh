package com.dallycontrol.agent.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.dallycontrol.agent.BuildConfig
import com.dallycontrol.agent.R
import com.dallycontrol.core.config.ServerConfigStore
import com.dallycontrol.core.store.DeviceIdentity
import com.dallycontrol.core.support.SupportChannel
import com.dallycontrol.core.support.SupportProtocol
import com.dallycontrol.core.support.SupportProtocol.bool
import com.dallycontrol.core.support.SupportProtocol.int
import com.dallycontrol.core.support.SupportProtocol.long
import com.dallycontrol.core.support.SupportProtocol.num
import com.dallycontrol.core.support.SupportProtocol.str
import com.dallycontrol.core.support.SupportReadiness
import com.dallycontrol.core.support.SupportSettings
import com.dallycontrol.proto.SupportScreenState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The phone's side of a remote-support session (ADR 0012): one socket to the server carrying the screen, the
 * operator's touches, a voice call, a chat and files. The agent does all of it itself, with nothing else installed.
 *
 * A session starts when the console opens one: the server names it in a wake on the live channel (or in a
 * `remote.session.start` command when the phone's socket was down) and [join] dials in. It ends when the operator
 * closes it, the person on the phone hangs it up, or the network stays away for longer than [REJOIN_MS].
 */
@Singleton
@Suppress("TooManyFunctions") // one session, five features (screen, input, chat, voice, files): a short function each
class SupportSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val channel: SupportChannel,
    private val serverConfig: ServerConfigStore,
    private val identity: DeviceIdentity,
) {
    data class ChatLine(val fromSupport: Boolean, val text: String, val at: Long)

    enum class CallState { IDLE, RINGING, ACTIVE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val captureExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "support-capture").apply { isDaemon = true }
    }
    private val files = SupportFiles(context)
    /**
     * The operator's touches and keys, in order, on a thread of their own: an accessibility call can wait seconds
     * for a busy app, and that must not hold up the chat, the call or the frame acknowledgements.
     */
    private val inputExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "support-input").apply { isDaemon = true }
    }

    @Volatile private var sessionId: String? = null
    @Volatile private var connection: SupportChannel.Connection? = null
    @Volatile private var viewOnly = false
    @Volatile private var open = false
    @Volatile private var lostAt = 0L
    @Volatile private var streamJob: Job? = null
    @Volatile private var sendJob: Job? = null
    @Volatile private var ackedSeq = -1
    @Volatile private var maxDim = SupportProtocol.DEFAULT_DIM
    @Volatile private var quality = SupportProtocol.DEFAULT_QUALITY
    @Volatile private var voice: VoiceCall? = null
    @Volatile private var ringJob: Job? = null
    @Volatile private var pendingTone = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: android.media.Ringtone? = null

    @Volatile var operator: String? = null
        private set
    @Volatile var callState = CallState.IDLE
        private set
    val chat = CopyOnWriteArrayList<ChatLine>()
    /** Told (on any thread) when the chat, the call or the session changes: the support screen redraws. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    /** Whether the support screen is on top (a chat line then needs no notification). */
    @Volatile var screenVisible = false

    val active: Boolean get() = sessionId != null
    val muted: Boolean get() = voice?.muted == true

    init {
        SupportEngine.listeners += {
            if (open) {
                sendInfo()
                syncNotice()
            }
        }
    }

    private fun changed() = listeners.forEach { runCatching { it() } }

    /** The policy changed during a session: a notice that is now turned off goes away (a call keeps its own). */
    private fun syncNotice() {
        if (SupportEngine.settings(context).notice || callState != CallState.IDLE) return
        SupportAccessibilityService.instance?.hideNotice()
        if (!ProjectionCapture.active) SupportForegroundService.stop(context)
    }

    // --- joining -------------------------------------------------------------------------------------------------

    /**
     * Join the session the console opened. Returns null once the socket is up (or was already), else the reason.
     */
    @Suppress("ReturnCount") // each early return is one reason not to dial
    suspend fun join(id: String): String? {
        if (!SupportChannel.isSessionId(id)) return "invalid session"
        if (sessionId == id && open) return null
        val deviceId = identity.current()
        val secret = identity.secret()
        if (deviceId == null || secret == null) return "not enrolled"
        val fresh = synchronized(this) {
            if (sessionId == id && connection != null) {
                false
            } else {
                if (sessionId != null) teardown("replaced", tellPeer = true)
                sessionId = id
                chat.clear()
                lostAt = 0
                true
            }
        }
        if (!fresh) return null // already dialling this session (the wake and the queued command both name it)
        val opened = CompletableDeferred<String>()
        dial(id, deviceId, secret, opened)
        val error = withTimeoutOrNull(JOIN_TIMEOUT_MS) { opened.await() } ?: "the server did not answer"
        return error.ifEmpty { null }
    }

    private fun dial(id: String, deviceId: String, secret: String, opened: CompletableDeferred<String>?) {
        connection = channel.connect(serverConfig.baseUrl(), deviceId, secret, id, object : SupportChannel.Listener {
            override fun onOpen() {
                if (sessionId != id) return
                open = true
                lostAt = 0
                opened?.complete("")
                Log.i(TAG, "joined support session")
            }

            override fun onMessage(type: String, message: JsonObject) {
                if (sessionId != id) return
                runCatching { handle(type, message) }.onFailure { Log.w(TAG, "message $type failed", it) }
            }

            override fun onBinary(kind: Byte, data: ByteArray) {
                if (sessionId == id) runCatching { handleBinary(kind, data) }
            }

            override fun onClosed(refused: Boolean, reason: String) {
                if (sessionId != id) return
                open = false
                opened?.complete(if (refused) "session refused: $reason" else "network: $reason")
                if (refused) {
                    teardown(reason, tellPeer = false)
                } else {
                    retry(id, deviceId, secret)
                }
            }
        })
    }

    /** The network dropped: keep trying for [REJOIN_MS], then give the session up. */
    private fun retry(id: String, deviceId: String, secret: String) {
        if (lostAt == 0L) lostAt = System.currentTimeMillis()
        stopStream()
        scope.launch {
            delay(RETRY_MS)
            if (sessionId != id) return@launch
            if (System.currentTimeMillis() - lostAt > REJOIN_MS) {
                teardown("network", tellPeer = false)
            } else {
                dial(id, deviceId, secret, null)
            }
        }
    }

    /** End the session from the phone (the stop command, or the person on the support screen). */
    fun leave(reason: String = "device") {
        if (sessionId != null) teardown(reason, tellPeer = true)
    }

    @Synchronized
    private fun teardown(reason: String, tellPeer: Boolean) {
        val conn = connection
        if (sessionId == null && conn == null) return
        Log.i(TAG, "support session over ($reason)")
        sessionId = null
        open = false
        connection = null
        stopStream()
        stopCall(notifyPeer = false)
        files.abortAll()
        sendJob?.cancel()
        if (tellPeer) runCatching { conn?.send(SupportProtocol.message("bye")) }
        runCatching { conn?.close() }
        SupportAccessibilityService.instance?.hideNotice()
        ProjectionCapture.release()
        SupportForegroundService.stop(context)
        SupportForegroundService.clearChatNotification(context)
        releaseWake()
        operator = null
        changed()
    }

    // --- messages ------------------------------------------------------------------------------------------------

    private fun send(text: String): Boolean = connection?.send(text) == true

    // One branch per message type of the protocol: long by nature, flat to read.
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun handle(type: String, m: JsonObject) {
        when (type) {
            "session" -> {
                viewOnly = m.bool("viewOnly") == true
                operator = m.str("operator")
                if (SupportEngine.settings(context).notice) SupportForegroundService.start(context)
                sendInfo()
                changed()
            }
            "peer" -> if (m.str("state") == "joined") sendInfo() else { stopStream(); stopCall(notifyPeer = false) }
            "info.get" -> sendInfo()
            "end" -> teardown(m.str("reason") ?: "ended", tellPeer = false)
            "bye" -> teardown("console", tellPeer = false)
            "screen.start" -> startStream(m.int("maxDim"), m.int("quality"))
            "screen.set" -> {
                m.int("maxDim")?.let { maxDim = it.coerceIn(SupportProtocol.MIN_DIM, SupportProtocol.MAX_DIM) }
                m.int("quality")?.let { quality = SupportProtocol.quality(it) }
            }
            "screen.stop" -> stopStream()
            "ack" -> m.int("seq")?.let { ackedSeq = it }
            "tap", "swipe", "key", "text", "wake" -> if (!viewOnly) {
                inputExecutor.execute { runCatching { input(type, m) }.onFailure { Log.w(TAG, "input $type failed", it) } }
            }
            "chat" -> m.str("s")?.takeIf { it.isNotBlank() }?.let(::onChat)
            "call.start" -> onCallStart(m.bool("testTone") == true)
            "call.end" -> { stopCall(notifyPeer = false); sendCallState("ended") }
            "clip.set" -> if (!viewOnly) setClipboard(m.str("s") ?: "")
            "clip.get" -> readClipboard()
            "file.put" -> {
                val id = m.int("id") ?: return
                files.begin(id, m.str("name"), m.long("size") ?: 0)?.let { fileError(id, it) }
            }
            "file.end" -> {
                val id = m.int("id") ?: return
                runCatching { files.finish(id) }.fold(
                    { path -> send(SupportProtocol.message("file.saved") { put("id", id); put("path", path) }) },
                    { e -> fileError(id, e.message ?: "error") },
                )
            }
            "file.list" -> listFiles(m.str("path"))
            "file.get" -> sendFile(m.int("id") ?: 0, m.str("path") ?: "")
        }
    }

    private fun fileError(id: Int, detail: String) {
        send(SupportProtocol.message("file.error") { put("id", id); put("detail", detail) })
    }

    private fun handleBinary(kind: Byte, bytes: ByteArray) {
        when (kind) {
            SupportProtocol.BIN_AUDIO -> voice?.onAudio(bytes, 1, bytes.size - 1)
            SupportProtocol.BIN_FILE -> {
                if (bytes.size < 2) return
                val id = bytes[1].toInt() and BYTE_MASK
                files.chunk(id, bytes, 2, bytes.size - 2)?.let { fileError(id, it) }
            }
        }
    }

    private fun sendInfo() {
        val cap = SupportEngine.supportCapability(context)
        val settings = SupportEngine.settings(context)
        val service = SupportAccessibilityService.instance
        val size = service?.screenSize()
        send(SupportProtocol.message("info") {
            put("sdk", Build.VERSION.SDK_INT)
            put("release", Build.VERSION.RELEASE)
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("agent", BuildConfig.VERSION_NAME)
            put("engine", if (cap.screen == SupportScreenState.READY) "builtin" else "none")
            put("screen", cap.screen)
            put("capture", cap.capture)
            put("control", cap.control && !viewOnly)
            put("voice", cap.voice)
            put("connected", service != null)
            put("viewOnly", viewOnly)
            put("notice", settings.notice)
            put("callAccept", settings.callAccept)
            put("probe", SupportEngine.probe)
            if (size != null) { put("w", size.first); put("h", size.second) }
        })
    }

    // --- screen --------------------------------------------------------------------------------------------------

    private fun startStream(askedDim: Int?, askedQuality: Int?) {
        maxDim = (askedDim ?: SupportProtocol.DEFAULT_DIM).coerceIn(SupportProtocol.MIN_DIM, SupportProtocol.MAX_DIM)
        quality = SupportProtocol.quality(askedQuality)
        if (streamJob?.isActive == true) return
        streamJob = scope.launch { stream() }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun screenError(detail: String) {
        send(SupportProtocol.message("error") { put("what", "screen"); put("detail", detail) })
        sendInfo()
    }

    // The capture loop: wait for the previous image to be drawn, capture, scale, encode, skip if unchanged, send.
    // Kept as one function so the pacing rules can be read top to bottom.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
    private suspend fun stream() {
        var service = SupportEngine.awaitService(context)
        if (service == null) {
            screenError(SupportEngine.screenState(context))
            return
        }
        sendInfo()
        wakeScreen()
        val notice = SupportEngine.settings(context).notice
        if (notice) service.showNotice(context.getString(R.string.support_viewing))
        val projection = Build.VERSION.SDK_INT < SupportReadiness.MIN_SDK_A11Y_CAPTURE
        if (projection && !ProjectionCapture.ensure(context, service, maxDim)) {
            screenError("capture refused")
            return
        }
        val jpeg = ByteArrayOutputStream(JPEG_BUFFER)
        var previous: Bitmap? = null
        var seq = 0
        var sentSeq = -1
        var sentAt = 0L
        // Android refuses an accessibility screenshot asked less than a third of a second after the previous one;
        // screen capture (Android 7-10) has no such limit.
        var interval = if (projection) PROJECTION_INTERVAL_MS else A11Y_INTERVAL_MS
        var lastCapture = 0L
        var statsAt = System.currentTimeMillis()
        var captures = 0
        var sent = 0
        var captureMs = 0L
        var encodeMs = 0L
        var sentBytes = 0L
        ackedSeq = -1
        while (scope.isActive && streamJob?.isActive == true) {
            val conn = connection ?: break
            // Android may unbind and rebind the service (an update of the agent, another tool taking over
            // accessibility for a moment): always work with the connected instance, and wait while there is none.
            val current = SupportAccessibilityService.instance
            if (current == null) { delay(REBIND_WAIT_MS); continue }
            if (current !== service) {
                service = current
                if (notice) current.showNotice(context.getString(R.string.support_viewing))
            }
            val now = System.currentTimeMillis()
            // One frame in flight: the next is taken only once the console has drawn the last one (or it is overdue).
            if (sentSeq > ackedSeq && now - sentAt < ACK_TIMEOUT_MS) { delay(POLL_MS); continue }
            if (conn.backlog() > MAX_BACKLOG) { delay(BACKLOG_WAIT_MS); continue }
            val wait = lastCapture + interval - now
            if (wait > 0) delay(wait)
            lastCapture = System.currentTimeMillis()
            // Every few seconds: how the capture is doing (shown in the console's status line, read by the e2e test).
            if (lastCapture - statsAt >= STATS_MS) {
                val n = captures.coerceAtLeast(1)
                send(SupportProtocol.message("screen.stats") {
                    put("captures", captures)
                    put("sent", sent)
                    put("captureMs", captureMs / n)
                    put("encodeMs", encodeMs / n)
                    put("intervalMs", interval)
                    put("bytes", sentBytes)
                    put("windowMs", lastCapture - statsAt)
                })
                statsAt = lastCapture
                captures = 0
                sent = 0
                captureMs = 0
                encodeMs = 0
                sentBytes = 0
            }
            val bitmap: Bitmap? = if (projection) {
                if (!ProjectionCapture.active && !ProjectionCapture.ensure(context, current, maxDim)) {
                    screenError("capture refused")
                    return
                }
                ProjectionCapture.frame()
            } else {
                val (b, error) = screenshot(current)
                if (error == SupportAccessibilityService.ERROR_TOO_SOON) {
                    interval = minOf(interval + INTERVAL_STEP_MS, MAX_INTERVAL_MS)
                }
                b
            }
            if (bitmap == null) { delay(EMPTY_WAIT_MS); continue }
            val captured = System.currentTimeMillis()
            captures++
            captureMs += captured - lastCapture
            val (w, h) = SupportProtocol.scaled(bitmap.width, bitmap.height, maxDim)
            // Always a private copy at the size sent, so it can be compared with the next one.
            val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
            val scaled = if (resized === bitmap) resized.copy(Bitmap.Config.ARGB_8888, false) else resized
            if (!projection) bitmap.recycle()
            // An unchanged screen is not sent again (a still screen costs no data and no encoding), except as a slow
            // keep-alive. Comparing pixels is far cheaper than encoding, so the loop is back at the screen sooner.
            val still = previous?.let { it.width == w && it.height == h && it.sameAs(scaled) } == true
            if (still && System.currentTimeMillis() - sentAt < STILL_REFRESH_MS) {
                scaled.recycle()
                delay(STILL_WAIT_MS)
                continue
            }
            previous?.recycle()
            previous = scaled
            jpeg.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)
            val bytes = jpeg.toByteArray()
            encodeMs += System.currentTimeMillis() - captured
            if (conn.send(SupportProtocol.frame(seq, w, h, bytes))) {
                sentSeq = seq
                sentAt = System.currentTimeMillis()
                seq++
                sent++
                sentBytes += bytes.size
            }
        }
    }

    private suspend fun screenshot(service: SupportAccessibilityService): Pair<Bitmap?, Int> =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                service.screenshot(captureExecutor) { bitmap, error -> if (cont.isActive) cont.resume(bitmap to error) }
            }
        } ?: (null to SupportAccessibilityService.ERROR_UNSUPPORTED)

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // four kinds of input, each validated before use
    private fun input(type: String, m: JsonObject) {
        if (type == "wake") { wakeScreen(); return }
        val service = SupportAccessibilityService.instance ?: return
        when (type) {
            "tap" -> {
                val x = m.num("x") ?: return
                val y = m.num("y") ?: return
                val hold = if (m.bool("long") == true) SupportAccessibilityService.LONG_PRESS_MS else TAP_MS
                service.gesture(listOf(Triple(x, y, 0L)), hold)
            }
            "swipe" -> {
                val pts = (m["pts"] as? JsonArray)?.mapNotNull { p ->
                    val a = p as? JsonArray ?: return@mapNotNull null
                    val x = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val y = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    Triple(x, y, (a.getOrNull(2) as? JsonPrimitive)?.longOrNull ?: 0L)
                }.orEmpty().take(MAX_SWIPE_POINTS)
                if (pts.size >= 2) service.gesture(pts)
            }
            "key" -> m.str("k")?.let { service.key(it) }
            "text" -> m.str("s")?.takeIf { it.isNotEmpty() }?.let { service.type(it.take(MAX_TEXT)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java)
            synchronized(this) {
                wakeLock?.takeIf { it.isHeld }?.release()
                wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "dallycontrol:support",
                ).apply { acquire(WAKE_MS) }
            }
        }.onFailure { Log.w(TAG, "could not wake the screen", it) }
    }

    private fun releaseWake() = synchronized(this) {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    // --- chat ----------------------------------------------------------------------------------------------------

    private fun onChat(text: String) {
        chat += ChatLine(true, text.take(MAX_TEXT), System.currentTimeMillis())
        changed()
        // Always bring the support screen up (it is a no-op when it is already on top): relying on the screen's own
        // "I am visible" flag left a message unseen when Android was late telling the screen it had been covered.
        if (!screenVisible) SupportForegroundService.chatNotification(context, text)
        SupportActivity.open(context)
    }

    /** A reply typed on the phone. */
    fun reply(text: String): Boolean {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty() || !send(SupportProtocol.message("chat") { put("s", clean) })) return false
        chat += ChatLine(false, clean, System.currentTimeMillis())
        changed()
        return true
    }

    // --- voice ---------------------------------------------------------------------------------------------------

    private fun sendCallState(state: String, detail: String? = null) {
        send(SupportProtocol.message("call.state") {
            put("s", state)
            if (detail != null) put("detail", detail)
            put("muted", muted)
        })
    }

    @Suppress("ReturnCount") // already in a call / no microphone / answered by policy / ringing
    private fun onCallStart(testTone: Boolean) {
        if (callState != CallState.IDLE) {
            sendCallState(if (callState == CallState.ACTIVE) "active" else "ringing")
            return
        }
        pendingTone = testTone && BuildConfig.DEBUG
        if (!pendingTone && !SupportEngine.grantMicrophone(context)) {
            sendCallState("error", "microphone permission missing")
            return
        }
        if (SupportEngine.settings(context).callAccept == SupportSettings.CALL_AUTO) {
            acceptCall()
            SupportActivity.open(context)
            return
        }
        callState = CallState.RINGING
        sendCallState("ringing")
        changed()
        startRinging()
        SupportActivity.open(context)
        ringJob = scope.launch {
            delay(RING_MS)
            if (callState == CallState.RINGING) declineCall("missed")
        }
    }

    private fun startRinging() = main.post {
        runCatching {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
            ringtone = android.media.RingtoneManager.getRingtone(context, uri)?.apply { play() }
        }
    }

    private fun stopRinging() = main.post {
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    /** The person accepted (or the policy answers calls by itself). */
    fun acceptCall() {
        if (callState == CallState.ACTIVE) return
        ringJob?.cancel()
        stopRinging()
        callState = CallState.ACTIVE
        changed()
        scope.launch {
            SupportForegroundService.start(context, microphone = !pendingTone, projection = ProjectionCapture.active)
            val until = System.currentTimeMillis() + FOREGROUND_WAIT_MS
            fun waiting() = !SupportForegroundService.foreground && SupportForegroundService.lastError == null
            while (waiting() && System.currentTimeMillis() < until) delay(POLL_MS)
            val call = VoiceCall(context, pendingTone, { connection?.send(it) == true }) { s ->
                send(SupportProtocol.message("call.stats") {
                    put("tx", s.txFrames); put("rx", s.rxFrames); put("micRms", s.micRms); put("rxRms", s.rxRms)
                    put("recording", s.recording); put("muted", s.muted)
                })
            }
            val error = if (callState != CallState.ACTIVE) "cancelled" else call.start()
            if (error != null) {
                callState = CallState.IDLE
                sendCallState("error", error)
                changed()
                return@launch
            }
            voice = call
            sendCallState("active")
            changed()
        }
    }

    fun declineCall(why: String = "declined") {
        if (callState != CallState.RINGING) return
        ringJob?.cancel()
        stopRinging()
        callState = CallState.IDLE
        sendCallState(why)
        changed()
    }

    /** Hang up from the phone (the support screen or the notification). */
    fun hangUp(byUser: Boolean) {
        if (callState == CallState.RINGING) { declineCall(); return }
        if (callState == CallState.IDLE) return
        stopCall(notifyPeer = byUser)
    }

    fun setMuted(on: Boolean) {
        voice?.muted = on
        if (callState == CallState.ACTIVE) sendCallState("active")
        changed()
    }

    private fun stopCall(notifyPeer: Boolean) {
        ringJob?.cancel()
        stopRinging()
        val was = callState
        callState = CallState.IDLE
        voice?.stop()
        voice = null
        if (was != CallState.IDLE) {
            if (notifyPeer) sendCallState("ended")
            // Back to the plain session notification (no microphone type), or none when the notice is off.
            if (sessionId != null && SupportEngine.settings(context).notice) {
                SupportForegroundService.start(context, projection = ProjectionCapture.active)
            } else if (!ProjectionCapture.active) {
                SupportForegroundService.stop(context)
            }
            changed()
        }
    }

    // --- clipboard -----------------------------------------------------------------------------------------------

    private fun setClipboard(text: String) = main.post {
        val ok = runCatching {
            val clip = ClipData.newPlainText("Soporte", text.take(MAX_CLIP))
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }.isSuccess
        send(SupportProtocol.message("clip.ok") { put("ok", ok) })
    }

    /**
     * Android 10+ only lets the focused app read the clipboard, so from the background the agent usually cannot:
     * it then offers the text selected in the focused field (through the screen service) instead.
     */
    private fun readClipboard() = main.post {
        val clip = runCatching {
            context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        val text = clip ?: SupportAccessibilityService.instance?.focusedText()
        send(SupportProtocol.message("clip") {
            put("s", (text ?: "").take(MAX_CLIP))
            put("source", if (clip != null) "clipboard" else if (text != null) "field" else "none")
        })
    }

    // --- files ---------------------------------------------------------------------------------------------------

    private fun listFiles(path: String?) {
        scope.launch(Dispatchers.IO) {
            val listing = runCatching { files.list(path) }.getOrElse {
                send(SupportProtocol.message("file.error") { put("detail", it.message ?: "no se pudo listar") })
                return@launch
            }
            send(buildJsonObject {
                put("t", "file.entries")
                put("path", listing.path)
                put("limited", listing.limited)
                put("entries", buildJsonArray {
                    for (e in listing.entries) add(buildJsonObject {
                        put("name", e.name)
                        put("path", e.path)
                        put("dir", e.dir)
                        put("size", e.size)
                        put("modified", e.modified)
                    })
                })
            }.toString())
        }
    }

    private fun sendFile(id: Int, path: String) {
        sendJob?.cancel()
        sendJob = scope.launch(Dispatchers.IO) {
            val file = files.open(path)
            if (file == null) {
                fileError(id, "no se pudo abrir el archivo")
                return@launch
            }
            file.stream.use { input ->
                send(SupportProtocol.message("file.put") {
                    put("id", id)
                    put("name", file.name)
                    put("size", file.size)
                })
                val buffer = ByteArray(SupportProtocol.FILE_CHUNK)
                var n = 0
                while (isActive && n >= 0) {
                    val conn = connection ?: return@launch
                    if (conn.backlog() > MAX_BACKLOG) {
                        delay(BACKLOG_WAIT_MS)
                    } else {
                        n = input.read(buffer)
                        if (n > 0) conn.send(SupportProtocol.fileChunk(id, buffer, n))
                    }
                }
                send(SupportProtocol.message("file.end") { put("id", id) })
            }
        }
    }

    companion object {
        private const val TAG = "SupportSession"
        private const val JOIN_TIMEOUT_MS = 15_000L
        private const val RETRY_MS = 2_000L
        /** Same window the server gives a device to come back (SupportSessions.REJOIN_MILLIS). */
        private const val REJOIN_MS = 45_000L
        private const val POLL_MS = 8L
        private const val PROJECTION_INTERVAL_MS = 80L
        private const val A11Y_INTERVAL_MS = 340L
        private const val INTERVAL_STEP_MS = 20L
        private const val MAX_INTERVAL_MS = 500L
        private const val ACK_TIMEOUT_MS = 1_500L
        private const val MAX_BACKLOG = 256L * 1024
        private const val BACKLOG_WAIT_MS = 20L
        private const val EMPTY_WAIT_MS = 80L
        private const val STILL_WAIT_MS = 20L
        private const val STILL_REFRESH_MS = 5_000L
        private const val CAPTURE_TIMEOUT_MS = 3_000L
        private const val STATS_MS = 2_000L
        private const val REBIND_WAIT_MS = 150L
        private const val JPEG_BUFFER = 256 * 1024
        private const val TAP_MS = 60L
        private const val MAX_SWIPE_POINTS = 60
        private const val MAX_TEXT = 4_000
        private const val MAX_CLIP = 100_000
        private const val WAKE_MS = 10 * 60_000L
        private const val RING_MS = 45_000L
        private const val FOREGROUND_WAIT_MS = 2_500L
        private const val BYTE_MASK = 0xFF
    }
}

/** Reaches the session manager from components Android creates (services, activities). */
object SupportGraph {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Entry {
        fun supportSessionManager(): SupportSessionManager
    }

    fun manager(context: Context): SupportSessionManager =
        EntryPointAccessors.fromApplication(context.applicationContext, Entry::class.java).supportSessionManager()
}
