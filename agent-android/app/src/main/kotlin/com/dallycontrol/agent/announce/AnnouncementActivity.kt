package com.dallycontrol.agent.announce

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.dallycontrol.agent.R
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/**
 * One announcement: title, text, image or video, and "Entendido". A mandatory one cannot be dismissed (Back does
 * nothing, and it comes back if the person leaves it) until they confirm; with a video, confirming unlocks when the
 * video ends. An optional one closes normally and stays in the inbox.
 */
class AnnouncementActivity : ComponentActivity() {
    private var ann: Announcement? = null
    private var confirmed = false
    private lateinit var ok: Button
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ann?.mandatory != true || confirmed) finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val a = Announcements.get(this, intent.getIntExtra(EXTRA_ID, -1))
        if (a == null) { finish(); return }
        ann = a
        confirmed = a.acked
        Announcements.markSeen(this, a.id)
        setContentView(build(a))
    }

    /** A mandatory announcement left without confirming comes back to the front. */
    override fun onStop() {
        super.onStop()
        val a = ann ?: return
        if (a.mandatory && !confirmed && !isFinishing) {
            main.postDelayed({ Announcements.showNextMandatory(applicationContext) }, 1_500)
        }
    }

    private fun build(a: Announcement): ViewGroup {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }
        col.addView(text(if (a.mandatory) getString(R.string.ann_mandatory) else getString(R.string.ann_label), 13f, SIGNAL, bold = true))
        col.addView(text(a.title, 24f, TEXT, bold = true).apply { setPadding(0, dp(6), 0, dp(4)) })
        col.addView(text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(a.createdAt)), 12f, MUTED))

        var waitForVideo = false
        when (a.mediaType) {
            "image" -> col.addView(image(a))
            "video" -> { col.addView(video(a)); waitForVideo = a.mandatory && !a.acked }
        }
        a.body?.takeIf { it.isNotBlank() }?.let { col.addView(text(it, 17f, TEXT).apply { setPadding(0, dp(16), 0, 0); setLineSpacing(0f, 1.25f) }) }

        ok = Button(this).apply {
            text = getString(if (a.acked) R.string.ann_close else R.string.ann_ok)
            textSize = 17f
            isAllCaps = false
            setOnClickListener { confirm() }
            isEnabled = !waitForVideo
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(ACCENT, Color.parseColor("#3A4252")),
                ))
            }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
            setBackgroundColor(INK)
            if (waitForVideo) addView(text(getString(R.string.ann_watch_video), 13f, MUTED).apply { gravity = Gravity.CENTER })
            addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
            addView(ScrollView(this@AnnouncementActivity).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom)
        }
    }

    private fun confirm() {
        val a = ann ?: return
        confirmed = true
        Announcements.markAcked(this, a.id)
        // The next mandatory one (if any), else back to what the person was doing.
        val next = Announcements.pendingMandatory(this).firstOrNull()
        if (next != null) load(intent(this, next.id)) else finish()
    }

    private fun image(a: Announcement): ImageView = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        val view = this
        thread {
            val path = a.localMedia?.takeIf { File(it).exists() } ?: runCatching { Announcements.download(context, a) }.getOrNull()
            val bmp = path?.let { decode(it) }
            main.post { if (bmp != null) view.setImageBitmap(bmp) }
        }
    }

    private fun decode(path: String) = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        val max = resources.displayMetrics.widthPixels.coerceAtLeast(720) * 2
        while (bounds.outWidth / sample > max) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun video(a: Announcement): FrameLayout {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)).apply { topMargin = dp(16) }
            setBackgroundColor(Color.BLACK)
        }
        val v = VideoView(this)
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val local = a.localMedia?.takeIf { File(it).exists() }
        v.setVideoURI(if (local != null) Uri.fromFile(File(local)) else Uri.parse(a.mediaUrl))
        v.setMediaController(MediaController(this).also { it.setAnchorView(v) })
        v.setOnPreparedListener { v.start() }
        v.setOnCompletionListener { ok.isEnabled = true }
        // A video that cannot play must not lock the person in: confirming unlocks after a few seconds.
        v.setOnErrorListener { _, _, _ -> main.postDelayed({ ok.isEnabled = true }, 5_000); true }
        return frame
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "announcement_id"
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
        val SIGNAL = Color.parseColor("#F4B942")
        val ACCENT = Color.parseColor("#2F6FDF")

        fun intent(context: Context, id: Int): Intent =
            Intent(context, AnnouncementActivity::class.java).putExtra(EXTRA_ID, id)
    }
}

/** The inbox: every announcement on this phone, newest first; unread ones marked. */
class AnnouncementsActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        setContentView(build())
    }

    private fun build(): ScrollView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(AnnouncementActivity.INK)
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.ann_inbox)
            setTextColor(AnnouncementActivity.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        })
        val items = Announcements.all(this)
        if (items.isEmpty()) {
            col.addView(TextView(this).apply {
                text = getString(R.string.ann_empty)
                setTextColor(AnnouncementActivity.MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        items.forEach { a ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundColor(Color.parseColor(if (a.seen) "#161B24" else "#1F2A3A"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                addView(TextView(context).apply {
                    text = (if (!a.seen) "● " else "") + a.title
                    setTextColor(AnnouncementActivity.TEXT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    typeface = if (a.seen) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
                })
                addView(TextView(context).apply {
                    text = listOfNotNull(
                        fmt.format(Date(a.createdAt)),
                        if (a.mediaType == "video") getString(R.string.ann_has_video) else if (a.mediaType == "image") getString(R.string.ann_has_image) else null,
                        if (a.mandatory) getString(R.string.ann_mandatory) else null,
                    ).joinToString(" · ")
                    setTextColor(AnnouncementActivity.MUTED)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                })
                setOnClickListener { startActivity(AnnouncementActivity.intent(this@AnnouncementsActivity, a.id)) }
            })
        }
        return ScrollView(this).apply { setBackgroundColor(AnnouncementActivity.INK); addView(col) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
