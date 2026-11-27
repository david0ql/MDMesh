package com.dallycontrol.agent.provisioning

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dallycontrol.agent.MainActivity
import com.dallycontrol.agent.R
import com.dallycontrol.core.enroll.EnrollFailure
import com.dallycontrol.core.enroll.EnrollStatusStore
import com.dallycontrol.core.store.DeviceIdStore
import kotlinx.coroutines.runBlocking

/** The enrollment failure codes in the phone's language. */
object EnrollMessages {

    fun describe(context: Context, code: String?, detail: String? = null): String = when (code) {
        null -> ""
        EnrollFailure.CLOCK -> context.getString(R.string.enroll_err_clock)
        EnrollFailure.NO_NETWORK -> context.getString(R.string.enroll_err_no_network)
        EnrollFailure.TIMEOUT -> context.getString(R.string.enroll_err_timeout)
        EnrollFailure.CAPTIVE_PORTAL -> context.getString(R.string.enroll_err_captive_portal)
        EnrollFailure.TLS -> context.getString(R.string.enroll_err_tls)
        EnrollFailure.SERVER_ERROR -> context.getString(R.string.enroll_err_server)
        EnrollFailure.NO_TOKEN -> context.getString(R.string.enroll_err_no_token)
        EnrollFailure.TOKEN_USED -> context.getString(R.string.enroll_err_token_used)
        EnrollFailure.TOKEN_EXPIRED -> context.getString(R.string.enroll_err_token_expired)
        EnrollFailure.TOKEN_INVALID -> context.getString(R.string.enroll_err_token_invalid)
        EnrollFailure.DISABLED -> context.getString(R.string.enroll_err_disabled)
        else -> context.getString(R.string.enroll_err_other, detail?.take(MAX_DETAIL).orEmpty())
    }

    /** True when waiting will not help: the screen then asks for a new QR or a typed code instead of "retrying". */
    fun needsPerson(code: String?): Boolean = code != null && EnrollFailure(code, "").isTerminal

    private const val MAX_DETAIL = 120
}

/**
 * The screen a technician sees while a freshly provisioned phone enrolls: what is happening, why it is failing, and a
 * retry button. Used inside the setup wizard ([AdminPolicyComplianceActivity], Android 12+) and right after the older
 * provisioning ([ProvisioningSuccessActivity]). It never blocks the phone: "continue" is always there, and it leaves by
 * itself on success or after [MAX_WAIT_MS] (enrollment keeps retrying in the background either way).
 */
@Suppress("TooManyFunctions") // small view helpers; splitting them out would only move them
abstract class EnrollStatusActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: EnrollStatusStore
    private lateinit var ids: DeviceIdStore
    private lateinit var headline: TextView
    private lateinit var reason: TextView
    private lateinit var technical: TextView
    private lateinit var retry: Button
    private var shownAt = 0L
    private var closing = false

    /** Called once when the screen is done (enrolled, "continue" tapped or timed out). */
    protected abstract fun onDone()

    protected fun showStatus() {
        status = EnrollStatusStore(applicationContext)
        ids = DeviceIdStore(applicationContext)
        shownAt = System.currentTimeMillis()
        setContentView(buildUi())
        tick()
    }

    private fun tick() {
        if (closing) return
        val enrolled = runBlocking { ids.current() }.orEmpty().isNotBlank()
        val snap = status.snapshot()
        when {
            enrolled -> {
                headline.setText(R.string.enroll_ui_done)
                headline.setTextColor(OK)
                reason.text = ""
                technical.text = ""
                retry.visibility = View.GONE
            }
            snap.errorCode != null -> showFailure(snap)
            else -> showWorking()
        }
        when {
            enrolled -> close(DONE_DELAY_MS)
            System.currentTimeMillis() - shownAt > MAX_WAIT_MS -> close(0)
            else -> handler.postDelayed(::tick, TICK_MS)
        }
    }

    private fun showFailure(snap: EnrollStatusStore.Snapshot) {
        val needsPerson = EnrollMessages.needsPerson(snap.errorCode)
        headline.setText(if (needsPerson) R.string.enroll_ui_failed else R.string.enroll_ui_retrying)
        headline.setTextColor(ALERT)
        reason.text = EnrollMessages.describe(this, snap.errorCode, snap.errorDetail)
        technical.text = getString(R.string.enroll_ui_technical, snap.errorCode, snap.attempts, deviceLabel())
        retry.visibility = View.VISIBLE
    }

    private fun showWorking() {
        headline.setText(R.string.enroll_ui_working)
        headline.setTextColor(TEXT)
        reason.setText(R.string.enroll_ui_working_hint)
        technical.text = ""
        retry.visibility = View.GONE
    }

    private fun close(delayMs: Long) {
        if (closing) return
        closing = true
        handler.postDelayed({ onDone() }, delayMs)
    }

    override fun onDestroy() {
        closing = true
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Leaving with Back is the same as "continue": provisioning must never fail because of this screen. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        close(0)
    }

    private fun deviceLabel() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(PAD), dp(PAD_TOP), dp(PAD), dp(PAD))
        }
        root.addView(text("DallyControl", TITLE_SP, SIGNAL, bold = true))
        headline = text("", HEAD_SP, TEXT, bold = true).apply { setPadding(0, dp(GAP), 0, dp(GAP_SMALL)) }
        reason = text("", BODY_SP, TEXT)
        technical = text("", SMALL_SP, MUTED).apply { setPadding(0, dp(GAP_SMALL), 0, dp(GAP)) }
        retry = Button(this).apply {
            setText(R.string.enroll_ui_retry)
            contentDescription = "enroll-retry"
            visibility = View.GONE
            setOnClickListener {
                EnrollRetry.now(this@EnrollStatusActivity)
                showWorking()
            }
        }
        val skip = Button(this).apply {
            setText(R.string.enroll_ui_continue)
            contentDescription = "enroll-continue"
            setOnClickListener { close(0) }
        }
        listOf(headline, reason, technical, retry, skip).forEach { root.addView(it) }
        return ScrollView(this).apply {
            setBackgroundColor(INK)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(MATCH, MATCH))
        }
    }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER_HORIZONTAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val TICK_MS = 1_000L
        const val DONE_DELAY_MS = 1_500L
        const val MAX_WAIT_MS = 180_000L
        const val PAD = 28
        const val PAD_TOP = 72
        const val GAP = 24
        const val GAP_SMALL = 10
        const val TITLE_SP = 28f
        const val HEAD_SP = 20f
        const val BODY_SP = 16f
        const val SMALL_SP = 12f
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
        val SIGNAL = Color.parseColor("#F4B942")
        val OK = Color.parseColor("#3FD08A")
        val ALERT = Color.parseColor("#F2545B")
    }
}

/**
 * `ACTION_PROVISIONING_SUCCESSFUL` (Android 8-11): Android starts it when the older Device-Owner provisioning ends.
 * It takes the payload in again (idempotent - a safety net for makers whose provisioning did not deliver the
 * `PROFILE_PROVISIONING_COMPLETE` broadcast) and shows the enrollment status before leaving for the agent's screen.
 */
class ProvisioningSuccessActivity : EnrollStatusActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProvisioningIntake.accept(this, ProvisioningIntake.extrasOf(intent), EnrollStatusStore.PATH_QR_LEGACY)
        showStatus()
    }

    override fun onDone() {
        runCatching { startActivity(Intent(this, MainActivity::class.java)) }
        finish()
    }
}
