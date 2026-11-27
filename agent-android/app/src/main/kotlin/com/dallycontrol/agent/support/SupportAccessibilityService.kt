package com.dallycontrol.agent.support

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.dallycontrol.core.support.SupportProtocol
import com.dallycontrol.core.support.TextEdit
import java.util.concurrent.Executor

/**
 * The agent's own screen service for remote support (ADR 0012): the ONE accessibility service in the agent.
 * During a support session it
 *  - captures the screen without any consent dialog ([screenshot], Android 11+),
 *  - replays the operator's touches ([gesture]) and navigation keys ([key]),
 *  - types into the focused field ([type], [backspace], [enter]),
 *  - shows the "Soporte está viendo tu pantalla" notice above every app ([showNotice]), and
 *  - taps "Start now" on Android's screen-capture consent on Android 7-10 ([ProjectionCapture]).
 *
 * It listens only to System UI window changes (for that consent) and reads no other app's events. It is switched
 * on once: by the agent itself when it holds WRITE_SECURE_SETTINGS (USB enrollment), otherwise by one tap on the
 * guided screen after a QR enrollment ([RemoteSetupActivity]). Android offers a Device Owner no API for it.
 */
class SupportAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var notice: TextView? = null
    /** What was typed into the focused password field: accessibility hides a password's real text. */
    private var shadow: String? = null
    private var shadowNode: Int = 0

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "screen service connected")
        SupportEngine.onServiceChanged(applicationContext)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        SupportEngine.onServiceChanged(applicationContext)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only System UI events arrive (see res/xml/support_screen_service.xml): the screen-capture consent.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ProjectionCapture.onSystemWindow(this, event)
        }
    }

    override fun onInterrupt() = Unit

    // --- capture -------------------------------------------------------------------------------------------------

    /** One screenshot as a software bitmap, or null with the platform's error code. Android 11+. */
    fun screenshot(executor: Executor, done: (Bitmap?, Int) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done(null, ERROR_UNSUPPORTED)
            return
        }
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    buffer.close()
                    done(bitmap, if (bitmap == null) ERROR_UNSUPPORTED else 0)
                }

                override fun onFailure(errorCode: Int) = done(null, errorCode)
            })
        }.onFailure {
            Log.w(TAG, "takeScreenshot threw", it)
            done(null, ERROR_UNSUPPORTED)
        }
    }

    /** The real size of the screen in its current rotation. */
    fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }

    // --- input ---------------------------------------------------------------------------------------------------

    /**
     * Replay a touch: [points] are (x fraction, y fraction, milliseconds from the start). One point is a tap (or a
     * long press when [holdMs] is long); several are a swipe along them.
     */
    fun gesture(points: List<Triple<Double, Double, Long>>, holdMs: Long = TAP_MS): Boolean {
        if (points.isEmpty()) return false
        val (w, h) = screenSize()
        val path = Path()
        val first = points.first()
        path.moveTo(SupportProtocol.toPixel(first.first, w), SupportProtocol.toPixel(first.second, h))
        for (p in points.drop(1)) path.lineTo(SupportProtocol.toPixel(p.first, w), SupportProtocol.toPixel(p.second, h))
        val duration = if (points.size == 1) holdMs.coerceIn(1, MAX_GESTURE_MS)
        else (points.last().third - first.third).coerceIn(MIN_SWIPE_MS, MAX_GESTURE_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    /** Navigation and system keys of the session bar. */
    fun key(name: String): Boolean = when (name) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quicksettings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "power" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        "lock" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "backspace" -> backspace()
        "enter" -> enter()
        else -> false
    }

    /** Type [text] at the caret of the focused field. */
    fun type(text: String): Boolean = edit { current, start, end -> TextEdit.insert(current, start, end, text) }

    fun backspace(): Boolean = edit { current, start, end -> TextEdit.backspace(current, start, end) }

    fun enter(): Boolean {
        val node = focusedInput() ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ime = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
            if (node.actionList.any { it.id == ime.id } && node.performAction(ime.id)) return true
        }
        return if (node.isMultiLine) type("\n") else false
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.takeIf { it.isEditable }

    private fun edit(change: (String, Int, Int) -> TextEdit.State): Boolean {
        val node = focusedInput() ?: return false
        val id = node.hashCode()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) "" else null
        val current: String
        val start: Int
        val end: Int
        if (node.isPassword) {
            if (shadowNode != id) { shadow = ""; shadowNode = id }
            current = shadow ?: ""
            start = current.length
            end = current.length
        } else {
            current = hint ?: node.text?.toString() ?: ""
            start = if (hint != null) 0 else node.textSelectionStart
            end = if (hint != null) 0 else node.textSelectionEnd
        }
        val next = change(current, start, end)
        if (node.isPassword) shadow = next.text
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next.text)
        })
        if (ok) {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.caret)
            })
        }
        return ok
    }

    /** The text selected (or all the text) of the focused field: what "copy from the phone" can read. */
    fun focusedText(): String? {
        val node = focusedInput() ?: return null
        if (node.isPassword) return null
        val text = node.text?.toString() ?: return null
        val a = node.textSelectionStart
        val b = node.textSelectionEnd
        return if (a in 0 until b && b <= text.length) text.substring(a, b) else text
    }

    // --- notice --------------------------------------------------------------------------------------------------

    /** A small pill above every app (an accessibility overlay: no "draw over apps" permission involved). */
    fun showNotice(text: String) = main.post {
        hideNoticeNow()
        val view = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = NOTICE_SP
            val pad = (resources.displayMetrics.density * NOTICE_PAD_DP).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply {
                setColor(NOTICE_COLOR)
                cornerRadius = resources.displayMetrics.density * NOTICE_RADIUS_DP
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (resources.displayMetrics.density * NOTICE_TOP_DP).toInt()
        }
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            notice = view
        }.onFailure { Log.w(TAG, "could not show the support notice", it) }
    }

    fun hideNotice() = main.post { hideNoticeNow() }

    private fun hideNoticeNow() {
        val view = notice ?: return
        notice = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    companion object {
        private const val TAG = "SupportScreen"
        const val ERROR_UNSUPPORTED = -1
        /** AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT: asked again too soon. */
        const val ERROR_TOO_SOON = 3
        private const val TAP_MS = 60L
        const val LONG_PRESS_MS = 700L
        private const val MIN_SWIPE_MS = 80L
        private const val MAX_GESTURE_MS = 8_000L
        private const val NOTICE_SP = 13f
        private const val NOTICE_PAD_DP = 6
        private const val NOTICE_RADIUS_DP = 16
        private const val NOTICE_TOP_DP = 28
        private const val NOTICE_COLOR = 0xE6B3261E.toInt()

        /** The connected service, or null while it is switched off. */
        @Volatile var instance: SupportAccessibilityService? = null
            private set
    }
}
