package com.dallycontrol.agent.support

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.dallycontrol.agent.R

/**
 * The one guided step of a QR / zero-touch enrollment (ADR 0012): Android gives a Device Owner no way to switch
 * its own screen service on, so the person setting the phone up does it here, once. The screen says what to tap,
 * opens the right settings page, notices by itself when the switch is on, and closes. From then on support
 * sessions need nobody at the phone. The console shows "Falta autorización" until this is done, and can bring this
 * screen up again (`remote.inputSetup`).
 */
@Suppress("MagicNumber") // layout sizes in dp, next to the views they size
class RemoteSetupActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var open: Button
    private var done = false

    private val poll = object : Runnable {
        override fun run() {
            if (SupportEngine.isServiceOn(this@RemoteSetupActivity)) {
                finished()
            } else {
                handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SupportEngine.markPrompted(this)
        setContentView(build())
    }

    override fun onStart() {
        super.onStart()
        handler.post(poll)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(24), dp(32), dp(24), dp(24))
        SupportInsets.keepClear(this, dp(24), dp(24))
        addView(TextView(context).apply {
            text = getString(R.string.remote_setup_title)
            textSize = TITLE_SP
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
        })
        addView(TextView(context).apply {
            text = getString(R.string.remote_setup_body, getString(R.string.support_service_label))
            textSize = BODY_SP
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(12), 0, dp(16))
        })
        state = TextView(context).apply {
            text = getString(R.string.remote_setup_pending)
            textSize = BODY_SP
            setTextColor(PENDING)
            setPadding(0, 0, 0, dp(16))
        }
        addView(state)
        open = Button(context).apply {
            text = getString(R.string.remote_setup_open)
            isAllCaps = false
            setOnClickListener { openSettings() }
        }
        addView(open)
        addView(Button(context).apply {
            text = getString(R.string.remote_setup_later)
            isAllCaps = false
            setOnClickListener { finish() }
        })
    }

    /** Accessibility settings, scrolled to (and highlighting) the agent's service where the phone supports it. */
    private fun openSettings() {
        val component = SupportEngine.component(this).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // Its own task: stacked on this screen's task, Settings is cleared when the console asks for this screen
            // again, and Android 11 then leaves this window without input focus (its buttons stop answering).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) })
        }
        runCatching { startActivity(intent) }
            .onFailure { state.text = getString(R.string.remote_setup_failed) }
    }

    private fun finished() {
        if (done) return
        done = true
        state.text = getString(R.string.remote_setup_done)
        state.setTextColor(DONE)
        open.visibility = View.GONE
        // Come back on top of Settings so the person sees it worked, then close.
        runCatching { startActivity(intent(this).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        handler.postDelayed({ finish() }, CLOSE_MS)
    }

    companion object {
        private const val TITLE_SP = 22f
        private const val BODY_SP = 16f
        private const val POLL_MS = 700L
        private const val CLOSE_MS = 2_500L
        private const val PENDING = 0xFFB3261E.toInt()
        private const val DONE = 0xFF137333.toInt()
        private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"

        fun intent(context: Context): Intent =
            Intent(context, RemoteSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun open(context: Context): Boolean {
            val app = context.applicationContext
            // The console queues this right after enrollment, and a QR / zero-touch phone enrolls while Android's
            // setup wizard is still on screen: opening now would cover the wizard (and the enrollment status the
            // agent shows inside it) with a screen the person cannot make sense of yet. Wait for the wizard to end.
            if (com.dallycontrol.agent.service.BatteryExemption.setupComplete(app)) {
                return runCatching { app.startActivity(intent(app)) }.isSuccess
            }
            if (waiting.compareAndSet(false, true)) {
                Thread { openAfterSetup(app) }.start()
            }
            return true
        }

        private fun openAfterSetup(app: Context) {
            try {
                val until = System.currentTimeMillis() + WAIT_FOR_SETUP_MS
                var ready = false
                while (!ready && System.currentTimeMillis() < until) {
                    Thread.sleep(SETUP_POLL_MS)
                    ready = com.dallycontrol.agent.service.BatteryExemption.setupComplete(app)
                }
                if (ready) {
                    Thread.sleep(SETUP_SETTLE_MS) // let the wizard's last screen leave first
                    runCatching { app.startActivity(intent(app)) }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                waiting.set(false)
            }
        }

        private val waiting = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val WAIT_FOR_SETUP_MS = 30L * 60L * 1000L
        private const val SETUP_POLL_MS = 3_000L
        private const val SETUP_SETTLE_MS = 4_000L
    }
}
