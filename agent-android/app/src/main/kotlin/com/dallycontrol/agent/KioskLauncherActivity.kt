package com.dallycontrol.agent

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import com.dallycontrol.agent.service.CheckInService
import com.dallycontrol.core.store.KioskStateStore
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.kiosk.CrashLoopGuard
import com.dallycontrol.kiosk.KioskController
import com.dallycontrol.proto.KioskApplyPayload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DallyControl kiosk HOME. This is the device's persistent launcher (`CATEGORY_HOME`), repointed to
 * by [KioskController.enter] via `addPersistentPreferredActivity`. It renders the last-applied
 * [KioskApplyPayload] persisted in [KioskStateStore]:
 *
 *  - `mode == "single"` → launch + pin the single allowed app ([KioskApplyPayload.pinPackage]).
 *  - `mode == "launcher"` → a themed grid of [KioskApplyPayload.allowedPackages].
 *  - no payload → an idle "managed device" screen (the agent is not in kiosk).
 *
 * Exit affordance is driven by [KioskApplyPayload.exitMode] (`gesture` 7-tap corner / `visible`
 * button / `remote` none) and gated by [KioskApplyPayload.password].
 *
 * A [CrashLoopGuard] protects against a crashing pinned app bouncing back to HOME in a tight
 * loop: each single-app launch registers a fault, and once the loop trips the launcher stops
 * relaunching and shows a locked "open <app>" screen instead. It never drops kiosk: pressing Back or
 * Home a few times also bounces to HOME, so dropping kiosk there was a way out of it. A device whose
 * app really keeps crashing stays reachable (the check-in service runs) and can leave kiosk remotely.
 */
@AndroidEntryPoint
class KioskLauncherActivity : ComponentActivity() {

    @Inject lateinit var store: KioskStateStore
    @Inject lateinit var controller: KioskController
    @Inject lateinit var events: EventSink
    @Inject lateinit var crashGuard: CrashLoopGuard

    /** Last applied non-null kiosk state, so [onResume] can recover a bounced single-app pin. */
    private var active: KioskApplyPayload? = null

    /** The guard tripped and the paused screen is up (reported once per trip). */
    private var paused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A kiosk device boots straight into HOME (this); keep the command channel alive even if
        // the user never opens the status screen.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        setContentView(idleView())
        // React to kiosk.enter/kiosk.exit live: those run in the check-in service, not here, so we
        // observe the persisted state and re-render (enter → grid/pin, exit → unpin + idle) without
        // waiting for the user to touch the screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                store.flow().distinctUntilChanged().collect(::applyState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A single-app pin that returned us to HOME means the pinned app exited or crashed — re-pin
        // it (counting the bounce so a crash loop trips the guard). Enter/exit transitions are
        // handled by the flow collector, not here.
        val p = active ?: return
        if (p.mode == "single") {
            if (paused) return // waiting for the user's tap (or the operator), no automatic relaunch
            autoLaunch(p)
        }
    }

    /**
     * Every automatic (re)launch of the pinned app counts as a bounce — whether HOME resumed or Android recreated
     * this activity (then the state flow relaunches it). One return can reach both paths, so launches within
     * [BOUNCE_DEDUPE_MS] count once; [lastAutoLaunch] is process-wide because the activity instance may be new.
     */
    private fun autoLaunch(p: KioskApplyPayload) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAutoLaunch > BOUNCE_DEDUPE_MS) crashGuard.registerFault()
        lastAutoLaunch = now
        if (pauseOnCrashLoop(p)) return
        launchPinned(p)
    }

    private fun applyState(p: KioskApplyPayload?) {
        active = p
        com.dallycontrol.agent.kiosk.QuickSettingsNotice.update(this, p?.quickSettings == true)
        if (p == null) {
            paused = false
            stopLockTaskSafely()
            setContentView(idleView())
            return
        }
        startLockTaskSafely()
        if (p.mode == "single" && p.pinPackage != null) {
            if (paused) setContentView(pausedView(p)) else autoLaunch(p)
        } else {
            setContentView(launcherGrid(p))
        }
    }

    /** Launch + show the pinned app (single mode), with a themed splash behind it. */
    private fun launchPinned(p: KioskApplyPayload) {
        val intent = p.pinPackage?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            setContentView(launcherGrid(p)) // unknown package → fall back to the grid
            return
        }
        setContentView(splashView(p))
        runCatching { startActivity(intent) }
    }

    /**
     * @return true if a crash loop tripped: kiosk stays locked and a screen offers to open the app again, so the
     * caller stops. Reported once per trip.
     */
    private fun pauseOnCrashLoop(p: KioskApplyPayload): Boolean {
        if (!crashGuard.isCrashLoopDetected()) return false
        if (!paused) events.record("kioskCrashLoop", "${p.pinPackage} left repeatedly; automatic relaunch paused, kiosk kept")
        paused = true
        setContentView(pausedView(p))
        return true
    }

    private fun startLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        }
    }

    private fun stopLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        }
    }

    // --- Exit flow ---------------------------------------------------------------------------

    private fun promptExit(p: KioskApplyPayload) {
        val pw = p.password
        if (pw.isNullOrBlank()) {
            doExit()
            return
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Admin password"
        }
        AlertDialog.Builder(this)
            .setTitle("Exit kiosk")
            .setView(input)
            .setPositiveButton("Exit") { _, _ ->
                if (input.text.toString() == pw) doExit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doExit() {
        runCatching { if (isFinishing.not()) stopLockTask() }
        controller.exit()
        events.record("kioskExit", "exited on-device")
        // Drop our HOME claim and hand off to the OEM launcher so the device returns to normal
        // (mirrors KioskExitHandler for the remote-exit path).
        runCatching {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, HOME_ALIAS),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
        lifecycleScope.launch {
            store.save(null)
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            finish()
        }
    }

    // --- Views -------------------------------------------------------------------------------

    private fun splashView(p: KioskApplyPayload): View {
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        return frame(bg).apply {
            addView(centeredText("Loading…", 18f, fg))
            addExitAffordance(p, this)
        }
    }

    private fun launcherGrid(p: KioskApplyPayload): View {
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        val cell = iconCellPx(p.theme.iconSize)
        // Width left for the grid: the screen minus the page's side padding (2 x 24dp) and the grid's (2 x 12dp);
        // each cell is the icon plus its own 2 x 12dp padding. Cells then share that width evenly.
        val cols = maxOf(2, (resources.displayMetrics.widthPixels - dp(72)) / (cell + dp(24)))

        val grid = GridLayout(this).apply {
            columnCount = cols
            setPadding(dp(12), dp(16), dp(12), dp(28))
        }
        var rendered = 0
        for (pkg in p.allowedPackages.distinct()) {
            if (packageManager.getLaunchIntentForPackage(pkg) == null) continue // services / in-call UI: allowed, not shown
            val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: continue
            val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            grid.addView(appCell(pkg, label, icon, cell, fg))
            rendered++
        }

        // Always render a header + (when nothing resolved) an empty-state, so kiosk is never a
        // bare black screen — that previously happened whenever the allowlist was empty or none of
        // the packages were installed on the device.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(12))
            layoutParams = ViewGroup.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // The policy/folder logo replaces the title; without one the kiosk keeps its plain heading.
        val topLogo = p.theme.logoUrl?.takeIf { it.isNotBlank() }
        if (topLogo != null) {
            column.addView(
                ImageView(this).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "kiosk-logo"
                    layoutParams = LinearLayout.LayoutParams(MATCH, dp(72)).apply { bottomMargin = dp(8) }
                    com.dallycontrol.agent.kiosk.BrandImages.into(this, topLogo)
                },
            )
        } else {
            column.addView(text("DallyControl Kiosk", 20f, fg, bold = true))
        }
        if (rendered == 0) {
            column.addView(
                text(
                    "No available apps. Add installed app packages to this kiosk's allowed list.",
                    14f,
                    MUTED,
                ).apply { setPadding(0, dp(10), 0, 0) },
            )
        }
        // The support line: one tap calls it.
        val support = p.theme.supportPhone?.takeIf { it.isNotBlank() }
        if (support != null) {
            grid.addView(
                appCell(
                    SUPPORT_TILE + support,
                    p.theme.supportLabel?.takeIf { it.isNotBlank() } ?: getString(R.string.kiosk_support),
                    ContextCompat.getDrawable(this, android.R.drawable.sym_action_call)!!,
                    cell,
                    fg,
                ),
            )
        }
        // Announcements from the console (the inbox), when there are any.
        val anns = com.dallycontrol.agent.announce.Announcements.all(this)
        if (anns.isNotEmpty()) {
            val unseen = anns.count { !it.seen }
            grid.addView(
                appCell(
                    ANNOUNCEMENTS_TILE,
                    getString(R.string.ann_tile) + if (unseen > 0) " ($unseen)" else "",
                    ContextCompat.getDrawable(this, android.R.drawable.ic_dialog_email)!!,
                    cell,
                    fg,
                ),
            )
        }
        // (Quick settings are the strip at the top of the page, not a tile.)
        column.addView(grid)

        // Apps scroll; the footer (serial on the left, second logo on the right) stays at the bottom.
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        // The pull-down "bar": Android keeps its own quick settings closed in a kiosk, so the kiosk offers this strip
        // (tap it, or swipe down from the top) for Wi-Fi, Bluetooth, brightness and volume.
        if (p.quickSettings) {
            page.addView(
                text("⌄  ${getString(R.string.qs_bar)}", 12f, fg).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "kiosk-quick-settings-bar"
                    setPadding(dp(16), dp(10), dp(16), dp(8))
                    setBackgroundColor(Color.argb(40, Color.red(fg), Color.green(fg), Color.blue(fg)))
                    isClickable = true
                    setOnClickListener { openQuickSettings() }
                },
                LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        page.addView(ScrollView(this).apply { addView(column) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val footLogo = p.theme.footerLogoUrl?.takeIf { it.isNotBlank() }
        val showSerial = p.theme.showSerial == true
        if (footLogo != null || showSerial) {
            page.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.argb(230, Color.red(bg), Color.green(bg), Color.blue(bg)))
                    // Leave room for the visible "Exit kiosk" button, which sits over the bottom centre.
                    setPadding(dp(20), dp(6), dp(20), if (p.exitMode == "visible") dp(76) else dp(24))
                    if (showSerial) {
                        addView(
                            text("Serial: ${deviceSerial()}", 12f, fg).apply { contentDescription = "kiosk-serial" },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    } else {
                        addView(View(this@KioskLauncherActivity), LinearLayout.LayoutParams(0, 1, 1f))
                    }
                    if (footLogo != null) {
                        addView(
                            ImageView(this@KioskLauncherActivity).apply {
                                adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_END
                                contentDescription = "kiosk-footer-logo"
                                com.dallycontrol.agent.kiosk.BrandImages.into(this, footLogo)
                            },
                            LinearLayout.LayoutParams(dp(120), dp(36)),
                        )
                    }
                },
                LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        // Android draws the app behind the status and navigation bars: keep the kiosk's content clear of them (the
        // wallpaper still fills the whole screen).
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val root = frame(bg)
        p.theme.backgroundUrl?.takeIf { it.isNotBlank() }?.let { wallpaper ->
            root.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                    com.dallycontrol.agent.kiosk.BrandImages.into(this, wallpaper)
                },
            )
        }
        root.addView(page)
        addExitAffordance(p, root)
        return root
    }

    private fun appCell(
        pkg: String,
        label: String,
        icon: android.graphics.drawable.Drawable,
        cellPx: Int,
        fg: Int,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(12), dp(8), dp(12))
        layoutParams = android.widget.GridLayout.LayoutParams(
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
            android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
        ).apply { width = 0 }
        isClickable = true
        addView(
            ImageView(this@KioskLauncherActivity).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
            },
        )
        addView(
            text(label, 12f, fg).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // Readable over a wallpaper: a soft halo in the opposite tone of the text.
                val dark = (Color.red(fg) * 299 + Color.green(fg) * 587 + Color.blue(fg) * 114) / 1000 < 128
                setShadowLayer(3f, 0f, 1f, if (dark) Color.argb(170, 255, 255, 255) else Color.argb(170, 0, 0, 0))
                setPadding(0, dp(6), 0, 0)
            },
        )
        contentDescription = "kiosk-app-$pkg"
        setOnClickListener {
            runCatching {
                if (pkg.startsWith(SUPPORT_TILE)) {
                    callSupport(pkg.removePrefix(SUPPORT_TILE))
                } else if (pkg == ANNOUNCEMENTS_TILE) {
                    startActivity(Intent(this@KioskLauncherActivity, com.dallycontrol.agent.announce.AnnouncementsActivity::class.java))
                } else if (pkg == QUICK_SETTINGS_TILE) {
                    startActivity(Intent(this@KioskLauncherActivity, com.dallycontrol.agent.kiosk.QuickSettingsActivity::class.java))
                } else {
                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                }
            }
        }
    }

    private fun idleView(): View = frame(INK).apply {
        val col = LinearLayout(this@KioskLauncherActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        col.addView(centeredText("DallyControl", 28f, SIGNAL, bold = true))
        col.addView(centeredText("Managed device", 14f, MUTED))
        addView(col)
    }

    /** Still in kiosk; the pinned app left several times in a row. One tap opens it again. */
    private fun pausedView(p: KioskApplyPayload): View = frame(parseColor(p.theme.backgroundColor, INK)).apply {
        val label = p.pinPackage?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                .getOrNull()
        } ?: getString(R.string.kiosk_app_fallback)
        val col = LinearLayout(this@KioskLauncherActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        col.addView(centeredText(getString(R.string.kiosk_paused_title, label), 20f, parseColor(p.theme.textColor, TEXT), bold = true))
        col.addView(centeredText(getString(R.string.kiosk_paused_body), 14f, MUTED).apply { setPadding(0, dp(12), 0, dp(24)) })
        col.addView(
            Button(this@KioskLauncherActivity).apply {
                text = getString(R.string.kiosk_paused_open, label)
                contentDescription = "kiosk-open-app"
                setOnClickListener {
                    crashGuard.reset()
                    paused = false
                    launchPinned(p)
                }
            },
        )
        addView(col)
        addExitAffordance(p, this)
    }

    /** Add the per-[KioskApplyPayload.exitMode] exit affordance to [parent]. */
    private fun addExitAffordance(p: KioskApplyPayload, parent: ViewGroup) {
        when (p.exitMode) {
            "visible" -> {
                val btn = Button(this).apply {
                    text = "Exit kiosk"
                    setOnClickListener { promptExit(p) }
                }
                parent.addView(
                    FrameWrap(this, btn, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(24)),
                )
            }
            "gesture" -> {
                // Invisible top-right corner target; 7 taps within the window opens the prompt.
                val target = View(this).apply {
                    var taps = 0
                    var first = 0L
                    setOnClickListener {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - first > GESTURE_WINDOW_MS) { taps = 0; first = nowMs }
                        if (++taps >= GESTURE_TAPS) { taps = 0; promptExit(p) }
                    }
                }
                parent.addView(
                    FrameWrap(this, target, Gravity.TOP or Gravity.END, 0, dp(72), dp(72)),
                )
            }
            else -> Unit // "remote": no on-device exit
        }
    }

    // --- View helpers ------------------------------------------------------------------------

    private fun openQuickSettings() {
        runCatching { startActivity(Intent(this, com.dallycontrol.agent.kiosk.QuickSettingsActivity::class.java)) }
    }

    private var swipeFromY = -1f

    /** A swipe down that starts near the top of the kiosk home opens the quick settings, like the system bar would. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (active?.quickSettings == true) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> swipeFromY = if (ev.y < dp(140)) ev.y else -1f
                android.view.MotionEvent.ACTION_UP -> {
                    if (swipeFromY >= 0 && ev.y - swipeFromY > dp(110)) {
                        swipeFromY = -1f
                        openQuickSettings()
                        return true
                    }
                    swipeFromY = -1f
                }
                android.view.MotionEvent.ACTION_CANCEL -> swipeFromY = -1f
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Call the support line directly (the Device Owner grants itself the call permission); else open the dialer. */
    private fun callSupport(number: String) {
        val uri = android.net.Uri.fromParts("tel", number, null)
        runCatching {
            val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            dpm.setPermissionGrantState(
                com.dallycontrol.agent.admin.AdminReceiver.componentName(this), packageName, android.Manifest.permission.CALL_PHONE,
                android.app.admin.DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
        val direct = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        runCatching { startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri)) }
            .onFailure { runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri)) } }
    }

    /** The hardware serial (a Device Owner may read it); "—" when Android withholds it. */
    @android.annotation.SuppressLint("HardwareIds", "MissingPermission")
    private fun deviceSerial(): String = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 26) android.os.Build.getSerial() else @Suppress("DEPRECATION") android.os.Build.SERIAL
    }.getOrNull()?.takeIf { it.isNotBlank() && it != android.os.Build.UNKNOWN } ?: "—"

    private fun frame(bg: Int): android.widget.FrameLayout =
        android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

    private fun centeredText(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        text(s, sizeSp, color, bold).apply { gravity = Gravity.CENTER }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun iconCellPx(size: String?): Int = when (size?.uppercase()) {
        "LARGE" -> dp(96)
        "MEDIUM" -> dp(72)
        else -> dp(56)
    }

    private fun parseColor(value: String?, fallback: Int): Int =
        value?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val BOUNCE_DEDUPE_MS = 1_500L
        /** Pseudo-package of the quick-settings tile on the kiosk home. */
        const val QUICK_SETTINGS_TILE = "dallycontrol.quicksettings"
        const val ANNOUNCEMENTS_TILE = "dallycontrol.announcements"
        const val SUPPORT_TILE = "dallycontrol.support:"
        @Volatile var lastAutoLaunch = 0L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val GESTURE_TAPS = 7
        const val GESTURE_WINDOW_MS = 3_000L
        const val HOME_ALIAS = "com.dallycontrol.agent.KioskHomeAlias"
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
        val SIGNAL = Color.parseColor("#F4B942")
    }
}

/** A [android.widget.FrameLayout.LayoutParams]-positioned wrapper, kept tiny for the launcher's
 *  programmatic UI (no XML). Places [child] at [gravity] with optional margins/size. */
private class FrameWrap(
    activity: ComponentActivity,
    child: View,
    gravity: Int,
    marginPx: Int,
    widthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
) : android.widget.FrameLayout(activity) {
    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(
            child,
            android.widget.FrameLayout.LayoutParams(widthPx, heightPx, gravity).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            },
        )
    }
}
