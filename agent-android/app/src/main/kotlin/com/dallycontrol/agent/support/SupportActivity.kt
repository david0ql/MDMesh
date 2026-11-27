package com.dallycontrol.agent.support

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dallycontrol.agent.R

/**
 * The support screen on the phone (ADR 0012): the conversation with the operator (their messages, a reply box) and
 * the voice call (accept / reject while it rings, mute / hang up while it is on). It opens by itself when support
 * writes or calls, also in kiosk and over the lock screen.
 */
@Suppress("MagicNumber") // layout sizes in dp, next to the views they size
class SupportActivity : Activity() {

    private lateinit var manager: SupportSessionManager
    private lateinit var status: TextView
    private lateinit var callPanel: LinearLayout
    private lateinit var callText: TextView
    private lateinit var accept: Button
    private lateinit var reject: Button
    private lateinit var mute: Button
    private lateinit var lines: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val refresh: () -> Unit = { runOnUiThread { render() } }
    private var shown = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = SupportGraph.manager(this)
        // A call from support must be answerable on a locked phone.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        setContentView(build())
    }

    override fun onStart() {
        super.onStart()
        manager.listeners += refresh
        manager.screenVisible = true
        SupportForegroundService.clearChatNotification(this)
        shown = 0
        lines.removeAllViews()
        render()
    }

    override fun onStop() {
        manager.listeners -= refresh
        manager.screenVisible = false
        super.onStop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(28), dp(16), dp(12))
        }
        SupportInsets.keepClear(root, dp(16), dp(12))
        root.addView(TextView(this).apply {
            text = getString(R.string.support_title)
            textSize = TITLE_SP
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        status = TextView(this).apply {
            textSize = BODY_SP
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(status)

        callPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(CALL_BG); cornerRadius = dp(12).toFloat() }
            visibility = View.GONE
        }
        callText = TextView(this).apply { textSize = CALL_SP; setTextColor(Color.BLACK) }
        callPanel.addView(callText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        accept = button(R.string.support_accept) { manager.acceptCall() }
        reject = button(R.string.support_reject) { manager.hangUp(byUser = true) }
        mute = button(R.string.support_mute) { manager.setMuted(!manager.muted) }
        row.addView(accept); row.addView(mute); row.addView(reject)
        callPanel.addView(row)
        root.addView(callPanel)

        lines = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(lines) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val compose = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = getString(R.string.support_reply_hint)
            contentDescription = getString(R.string.support_reply_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            // "Send" on the on-screen keyboard, or Enter on a physical one.
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
                val send = action == EditorInfo.IME_ACTION_SEND || (enter && event?.action == android.view.KeyEvent.ACTION_DOWN)
                if (send) sendReply()
                send || enter
            }
        }
        compose.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        compose.addView(button(R.string.support_send) { sendReply() })
        root.addView(compose)
        root.addView(button(R.string.support_close) { finish() })
        return root
    }

    private fun button(label: Int, action: () -> Unit) = Button(this).apply {
        text = getString(label)
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun sendReply() {
        val text = input.text.toString()
        if (text.isBlank()) return
        if (manager.reply(text)) input.setText("") else status.text = getString(R.string.support_not_connected)
    }

    private fun render() {
        status.text = when {
            !manager.active -> getString(R.string.support_ended)
            manager.operator != null -> getString(R.string.support_with, manager.operator)
            else -> getString(R.string.support_connected)
        }
        when (manager.callState) {
            SupportSessionManager.CallState.IDLE -> callPanel.visibility = View.GONE
            SupportSessionManager.CallState.RINGING -> {
                callPanel.visibility = View.VISIBLE
                callText.text = getString(R.string.support_call_ringing)
                accept.visibility = View.VISIBLE
                mute.visibility = View.GONE
                reject.text = getString(R.string.support_reject)
            }
            SupportSessionManager.CallState.ACTIVE -> {
                callPanel.visibility = View.VISIBLE
                callText.text =
                    getString(if (manager.muted) R.string.support_call_muted else R.string.support_call_active)
                accept.visibility = View.GONE
                mute.visibility = View.VISIBLE
                mute.text = getString(if (manager.muted) R.string.support_unmute else R.string.support_mute)
                reject.text = getString(R.string.support_hang_up)
            }
        }
        val all = manager.chat.toList()
        if (all.size < shown) { lines.removeAllViews(); shown = 0 }
        for (line in all.drop(shown)) lines.addView(bubble(line))
        if (all.size != shown) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        shown = all.size
    }

    private fun bubble(line: SupportSessionManager.ChatLine) = TextView(this).apply {
        text = getString(if (line.fromSupport) R.string.support_line_support else R.string.support_line_me, line.text)
        textSize = BODY_SP
        setTextColor(Color.BLACK)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setColor(if (line.fromSupport) SUPPORT_BG else ME_BG)
            cornerRadius = dp(10).toFloat()
        }
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply {
            topMargin = dp(6)
            gravity = if (line.fromSupport) Gravity.START else Gravity.END
        }
    }

    companion object {
        private const val TITLE_SP = 20f
        private const val BODY_SP = 16f
        private const val CALL_SP = 17f
        private const val MUTED = 0xFF5F6368.toInt()
        private const val CALL_BG = 0xFFE8F0FE.toInt()
        private const val SUPPORT_BG = 0xFFF1F3F4.toInt()
        private const val ME_BG = 0xFFD2E3FC.toInt()

        fun intent(context: Context): Intent = Intent(context, SupportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        /** Bring the support screen up (a Device Owner may start it from the background). */
        fun open(context: Context) {
            runCatching { context.startActivity(intent(context)) }
        }
    }
}
