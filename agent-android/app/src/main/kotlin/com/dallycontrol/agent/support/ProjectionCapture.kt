package com.dallycontrol.agent.support

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dallycontrol.core.support.SupportProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screen capture on Android 7-10, where accessibility cannot take screenshots: Android's screen capture
 * (MediaProjection), whose consent dialog the agent's own screen service answers ("Start now", and "Don't show
 * again" where Android still offers it). So these releases also need nobody at the phone once the service is on.
 *
 * Not used on Android 11+: there the service captures directly and no dialog exists to answer.
 * In a kiosk (lock task) Android does not let the System UI consent appear: capture is unavailable there on
 * these releases (droidVNC-NG's path, ADR 0010, has the same limit).
 */
object ProjectionCapture {
    private const val TAG = "SupportProjection"
    private const val CONSENT_WINDOW_MS = 20_000L
    private const val SYSTEM_UI = "com.android.systemui"

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var pending: CompletableDeferred<Boolean>? = null
    @Volatile private var consentUntil = 0L
    @Volatile private var last: Bitmap? = null
    private var width = 0
    private var height = 0
    private var thread: HandlerThread? = null

    val active: Boolean get() = projection != null

    /** [bitmap] is the capture's own last frame (kept between calls): not the caller's to recycle. */
    fun owns(bitmap: Bitmap): Boolean = bitmap === last

    /** Ask for (and self-accept) the capture, sized to fit [maxDim]. False when it could not be obtained. */
    @Suppress("ReturnCount") // already running / could not ask / the answer
    suspend fun ensure(context: Context, service: SupportAccessibilityService, maxDim: Int): Boolean {
        val (sw, sh) = service.screenSize()
        val (w, h) = SupportProtocol.scaled(sw, sh, maxDim)
        if (projection != null) {
            if (w != width || h != height) resize(context, w, h)
            return true
        }
        val answer = CompletableDeferred<Boolean>()
        pending = answer
        consentUntil = System.currentTimeMillis() + CONSENT_WINDOW_MS
        width = w
        height = h
        runCatching {
            context.startActivity(
                Intent(context, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Log.w(TAG, "could not ask for screen capture", it)
            return false
        }
        val ok = withTimeoutOrNull(CONSENT_WINDOW_MS) { answer.await() } ?: false
        consentUntil = 0
        pending = null
        return ok
    }

    /** The consent's answer, from [ProjectionRequestActivity]. */
    internal fun onResult(context: Context, resultCode: Int, data: Intent?) {
        val answer = pending
        if (resultCode != Activity.RESULT_OK || data == null) {
            answer?.complete(false)
            return
        }
        // Android 10 only hands out a projection to an app whose media-projection foreground service is already
        // in the foreground; the service start is asynchronous, so the first attempts may be refused.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) SupportForegroundService.start(context, projection = true)
        obtain(context, resultCode, data, OBTAIN_TRIES)
    }

    private fun obtain(context: Context, resultCode: Int, data: Intent, triesLeft: Int) {
        val answer = pending
        runCatching {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            val p = manager.getMediaProjection(resultCode, data)
            val handlerThread = HandlerThread("support-projection").apply { start() }
            thread = handlerThread
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() = release()
            }, Handler(handlerThread.looper))
            projection = p
            open(context, p)
            answer?.complete(true)
        }.onFailure {
            if (it is SecurityException && triesLeft > 0) {
                Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { obtain(context, resultCode, data, triesLeft - 1) }, OBTAIN_RETRY_MS,
                )
                return
            }
            Log.w(TAG, "screen capture did not start", it)
            release()
            answer?.complete(false)
        }
    }

    private const val OBTAIN_TRIES = 15
    private const val OBTAIN_RETRY_MS = 150L

    private fun open(context: Context, p: MediaProjection) {
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        display = p.createVirtualDisplay(
            "dc-support", width, height, context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
    }

    private fun resize(context: Context, w: Int, h: Int) {
        val p = projection ?: return
        runCatching {
            display?.release()
            reader?.close()
            width = w
            height = h
            last = null
            open(context, p)
        }.onFailure { Log.w(TAG, "could not resize the capture", it) }
    }

    /** The newest frame (the previous one again while the screen has not changed), or null before the first. */
    @Suppress("ReturnCount") // not capturing / nothing new / the new frame
    fun frame(): Bitmap? {
        val r = reader ?: return null
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return last
        return try {
            val plane = image.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(rowPixels, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (rowPixels == image.width) padded
            else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            last = bitmap
            bitmap
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "frame conversion failed", e)
            last
        } finally {
            image.close()
        }
    }

    fun release() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { thread?.quitSafely() }
        display = null
        reader = null
        projection = null
        thread = null
        last = null
    }

    /**
     * Called by the screen service for System UI windows: while a capture request of ours is pending, press the
     * consent's positive button (ticking "Don't show again" first where the dialog has it).
     */
    @Suppress("ReturnCount") // guards: no request pending, not System UI, no window to read
    internal fun onSystemWindow(service: SupportAccessibilityService, event: AccessibilityEvent) {
        if (System.currentTimeMillis() > consentUntil || event.packageName?.toString() != SYSTEM_UI) return
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: event.source ?: return
        runCatching {
            root.findAccessibilityNodeInfosByViewId("$SYSTEM_UI:id/remember").firstOrNull()
                ?.takeIf { it.isCheckable && !it.isChecked }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val start = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
            if (start != null && start.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "screen-capture consent accepted by the screen service")
            }
        }.onFailure { Log.w(TAG, "could not answer the capture consent", it) }
    }
}

/** Invisible activity that asks Android for the screen capture (the request needs an activity result). */
class ProjectionRequestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        runCatching { @Suppress("DEPRECATION") startActivityForResult(manager.createScreenCaptureIntent(), REQUEST) }
            .onFailure {
                ProjectionCapture.onResult(applicationContext, RESULT_CANCELED, null)
                finish()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST) ProjectionCapture.onResult(applicationContext, resultCode, data)
        finish()
    }

    private companion object {
        const val REQUEST = 7
    }
}
