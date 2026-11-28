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
    @Inject lateinit var applier: com.dallycontrol.core.kiosk.KioskApplier

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

    @Suppress("ReturnCount") // not in kiosk / the guided step is up / paused
    override fun onResume() {
        super.onResume()
        inFront = true
        // Back on the kiosk home: any system screen that was let through (sharing data…) is closed again at once.
        com.dallycontrol.agent.kiosk.TimedAllow.revokeAll(this)
        // A single-app pin that returned us to HOME means the pinned app exited or crashed — re-pin
        // it (counting the bounce so a crash loop trips the guard). Enter/exit transitions are
        // handled by the flow collector, not here.
        val p = active ?: return
        // The "authorisation pending" strip follows the guard's state (it changes after the kiosk was drawn).
        val guard = com.dallycontrol.agent.kiosk.AndroidKioskLockdown.guardState(this)
        gateTick.run()
        // Closed until authorised: nothing is opened from here, only the guided step (see KioskHardening.authGate).
        if (gated()) {
            if (!shownGate) setContentView(gateView(p))
            askAuthorisation()
            return
        }
        if (guard != shownGuard && !(p.mode == "single" && p.pinPackage != null)) setContentView(launcherGrid(p))
        if (askAuthorisation()) return // the guided step is up; this screen resumes again when it closes
        if (p.mode == "single") {
            if (paused) return // waiting for the user's tap (or the operator), no automatic relaunch
            autoLaunch(p)
        }
    }

    override fun onPause() {
        inFront = false
        gateHandler.removeCallbacks(gateTick)
        super.onPause()
    }

    private fun gated(): Boolean = com.dallycontrol.agent.kiosk.AndroidKioskLockdown.gated(this)

    /** The gate screen is what is drawn now. */
    private var shownGate = false
    private var inFront = false
    private val gateHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The gate is decided by the lock-down (the check-in service, another thread), a moment after the kiosk state
     * reaches this screen, and lifted the moment the authorisation is given: while this screen is in front it follows.
     */
    private val gateTick = object : Runnable {
        override fun run() {
            gateHandler.removeCallbacks(this)
            val p = active
            if (p != null && gated() != shownGate) {
                if (gated()) setContentView(gateView(p)) else applyState(p)
            }
            // The gate closed the kiosk: the guided step comes up by itself over this screen, once per closing (the
            // lock-down may have opened it a moment before this screen came to the front and covered it).
            if (p != null) com.dallycontrol.agent.kiosk.KioskAuthPrompt.askForGate(this@KioskLauncherActivity)
            if (p != null) gateHandler.postDelayed(this, GATE_TICK_MS)
        }
    }

    /** Closed until authorised: what the kiosk shows instead of its apps. */
    private fun gateView(p: KioskApplyPayload): View = frame(parseColor(p.theme.backgroundColor, INK)).apply {
        shownGate = true
        val col = LinearLayout(this@KioskLauncherActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        col.addView(
            text(getString(R.string.kiosk_auth_banner), BANNER_SP, Color.WHITE, bold = true).apply {
                contentDescription = "kiosk-auth-banner"
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setBackgroundColor(Color.parseColor("#B3261E"))
            },
        )
        col.addView(centeredText(getString(R.string.kiosk_gate_title), 20f, parseColor(p.theme.textColor, TEXT), bold = true).apply { setPadding(0, dp(20), 0, 0) })
        col.addView(centeredText(getString(R.string.kiosk_gate_body), 15f, parseColor(p.theme.textColor, TEXT)).apply { setPadding(0, dp(12), 0, dp(24)) })
        col.addView(
            Button(this@KioskLauncherActivity).apply {
                text = getString(R.string.kiosk_gate_button)
                isAllCaps = false
                contentDescription = "kiosk-auth-gate"
                setOnClickListener { askAuthorisation(force = true) }
            },
        )
        addView(col)
        addExitAffordance(p, this)
    }

    /** The guided authorisation step, while it is pending (see KioskAuthPrompt). */
    private fun askAuthorisation(force: Boolean = false): Boolean =
        com.dallycontrol.agent.kiosk.KioskAuthPrompt.ask(this, force)

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
        applier.syncNativeLock(p)
        com.dallycontrol.agent.kiosk.QuickSettingsNotice.update(this, p?.quickSettings == true)
        // Anti-theft follows the kiosk: on with the policy's PIN, off (and its restrictions lifted) without it.
        runCatching {
            com.dallycontrol.agent.kiosk.AntiTheft.apply(
                this, p?.powerPin,
                if (p == null) 0 else com.dallycontrol.kiosk.lockTaskFeatures(
                    com.dallycontrol.kiosk.KioskToggles(
                        home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                        systemInfo = p.features.systemInfo, keyguard = p.features.keyguard,
                        lockButtons = if (!p.powerPin.isNullOrBlank()) true else p.features.lockButtons,
                    ),
                ),
            )
        }
        if (p == null) {
            paused = false
            stopLockTaskSafely()
            setContentView(idleView())
            return
        }
        startLockTaskSafely()
        // The kiosk state usually arrives after onResume: the gate is followed from here on.
        if (inFront) gateHandler.postDelayed(gateTick, GATE_TICK_MS)
        if (gated()) {
            setContentView(gateView(p))
        } else if (p.mode == "single" && p.pinPackage != null) {
            if (paused) setContentView(pausedView(p)) else autoLaunch(p)
        } else {
            setContentView(launcherGrid(p))
        }
    }

    /**
     * The app's launch intent. Android 7–10 and 16+ (where a guard sends Settings back): when the app had opened a screen the kiosk keeps closed
     * inside its own task (Settings), that task is started clean once, or returning to the app would land on that
     * screen again and be sent back here forever.
     */
    private fun cleanLaunchIntent(pkg: String): Intent? = packageManager.getLaunchIntentForPackage(pkg)?.apply {
        val clean = com.dallycontrol.agent.kiosk.KioskForegroundGuard.consumeTainted(pkg)
        if (clean) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    /** Launch + show the pinned app (single mode), with a themed splash behind it. */
    private fun launchPinned(p: KioskApplyPayload) {
        if (gated()) {
            setContentView(gateView(p))
            return
        }
        val intent = p.pinPackage?.let { cleanLaunchIntent(it) }
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

    /** Android 16 (SDK 36): its LockTaskController NPEs in system_server during the app transition when entering
     *  lock task (confirmed on Samsung A17: NullPointerException on DisplayContent.executeAppTransition), which
     *  crashes the whole system → a reboot loop, most visibly on opening the pinned app. There is no way to catch
     *  a system_server crash from the app, so on Android 16 we do NOT enter lock task: we run a "soft" kiosk — this
     *  activity is the persistent HOME launcher, and what lock task would have enforced is enforced by the kiosk
     *  lock-down instead (AndroidKioskLockdown: status bar off, Settings and every app outside the kiosk suspended,
     *  kiosk restrictions). Older Android uses the real lock task as before. */
    private val softKiosk get() = com.dallycontrol.kiosk.KioskHardening.softKiosk(android.os.Build.VERSION.SDK_INT)

    private fun setStatusBarDisabled(disabled: Boolean) = runCatching {
        getSystemService(android.app.admin.DevicePolicyManager::class.java)
            ?.setStatusBarDisabled(com.dallycontrol.agent.admin.AdminReceiver.componentName(this), disabled)
    }

    private fun startLockTaskSafely() {
        val p = active
        // Soft kiosk (Android 16+): the status bar — and with it the system quick settings and their gear to
        // Settings — is disabled unless the policy explicitly allows notifications (safe by default). Settings and
        // the apps outside the kiosk are suspended anyway, so even then the gear leads nowhere.
        if (softKiosk) {
            setStatusBarDisabled(com.dallycontrol.kiosk.KioskHardening.statusBarDisabled(p?.features?.softStatusBar))
            return
        }
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        }.onFailure { android.util.Log.w("Kiosk", "startLockTask", it) }
        // Self-heal the lock-task feature mask (Home / Recents / power menu) on every (re)lock. The mask is otherwise
        // set only by the explicit enter() path, so a mask cleared by a kiosk.exit during remote support, or a boot
        // that did not re-run enter(), left Home/Recents/power dead until a fresh kiosk.enter. Re-asserting it here
        // (idempotent) keeps the buttons working without a config.apply. Android ≤15 only (soft kiosk returned above).
        if (p != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) runCatching {
            val mask = com.dallycontrol.kiosk.lockTaskFeatures(
                com.dallycontrol.kiosk.KioskToggles(
                    home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                    systemInfo = p.features.systemInfo, keyguard = p.features.keyguard,
                    lockButtons = if (!p.powerPin.isNullOrBlank()) true else p.features.lockButtons,
                ),
            )
            getSystemService(android.app.admin.DevicePolicyManager::class.java)
                ?.setLockTaskFeatures(com.dallycontrol.agent.admin.AdminReceiver.componentName(this), mask)
        }
    }

    private fun stopLockTaskSafely() {
        if (softKiosk) { setStatusBarDisabled(false); return }
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        }.onFailure { android.util.Log.w("Kiosk", "stopLockTask", it) }
    }

    // --- Exit flow ---------------------------------------------------------------------------

    /**
     * Leaving the kiosk on the device ALWAYS needs the policy's exit password. A kiosk without one (an older
     * policy, or a kiosk.enter sent without it) cannot be left here at all — only from the console (kiosk.exit).
     * Wrong attempts are limited (growing wait, see PinGate) and every outcome is reported to the server.
     */
    private fun promptExit(p: KioskApplyPayload) {
        val gate = com.dallycontrol.agent.kiosk.PinGates.exit(this)
        val waitMs = gate.remainingLockMs()
        if (p.password.isNullOrBlank() || waitMs > 0) {
            val refused = if (p.password.isNullOrBlank()) com.dallycontrol.kiosk.PinResult.NoSecret
            else com.dallycontrol.kiosk.PinResult.Locked(waitMs)
            reportExitAttempt(refused)
            AlertDialog.Builder(this)
                .setTitle(R.string.kiosk_exit_title)
                .setMessage(com.dallycontrol.agent.kiosk.PinGates.message(this, refused))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.kiosk_exit_password)
            contentDescription = "kiosk-exit-password"
            maxLines = 1
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.kiosk_exit_title)
            .setView(input)
            .setPositiveButton(R.string.kiosk_exit_confirm) { _, _ ->
                val result = gate.verify(p.password, input.text.toString())
                reportExitAttempt(result)
                if (result == com.dallycontrol.kiosk.PinResult.Ok) {
                    doExit()
                } else {
                    val why = com.dallycontrol.agent.kiosk.PinGates.message(this, result)
                    android.widget.Toast.makeText(this, why, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // "Done" / Enter on the keyboard confirms, like the button.
        input.setOnEditorActionListener { _, _, _ ->
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick()
            true
        }
    }

    /** Every exit attempt reaches the console: the exit itself, a wrong password, a refused attempt. */
    private fun reportExitAttempt(result: com.dallycontrol.kiosk.PinResult) {
        val type = when (result) {
            com.dallycontrol.kiosk.PinResult.Ok -> com.dallycontrol.proto.EventType.KIOSK_EXIT
            is com.dallycontrol.kiosk.PinResult.Wrong -> com.dallycontrol.proto.EventType.KIOSK_EXIT_FAILED
            else -> com.dallycontrol.proto.EventType.KIOSK_EXIT_DENIED
        }
        val detail = if (result == com.dallycontrol.kiosk.PinResult.Ok) "salida en el dispositivo con la contraseña"
        else com.dallycontrol.agent.kiosk.PinGates.eventDetail(result)
        runCatching { events.record(type, detail) }
        runCatching { com.dallycontrol.core.sync.CheckInWorker.scheduleNow(applicationContext) }
    }

    private fun doExit() {
        runCatching { if (!softKiosk && !isFinishing) stopLockTask() }
        lifecycleScope.launch {
            // The one exit path, shared with the console's kiosk.exit: lock-task allowlist, HOME claim, kiosk
            // lock-down (restrictions, suspended apps, status bar) and the persisted state, then the OEM launcher.
            applier.exit()
            finish()
        }
    }

    // --- Views -------------------------------------------------------------------------------

    private fun splashView(p: KioskApplyPayload): View {
        shownGate = false
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        return frame(bg).apply {
            addView(centeredText(getString(R.string.kiosk_loading), 18f, fg))
            addExitAffordance(p, this)
        }
    }

    /** The guard state the kiosk home was last drawn with. */
    private var shownGuard: String? = null

    private fun launcherGrid(p: KioskApplyPayload): View {
        shownGuard = com.dallycontrol.agent.kiosk.AndroidKioskLockdown.guardState(this)
        shownGate = false
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        val cell = iconCellPx(p.theme.iconSize)
        // Width left for the grid: the screen minus the page's side padding (2 x 24dp) and the grid's (2 x 12dp);
        // each cell is the icon plus its own 2 x 8dp padding. Cells then share that width evenly (4 small icons fit a
        // 360dp phone).
        val cols = maxOf(2, (resources.displayMetrics.widthPixels - dp(72)) / (cell + dp(16)))

        val grid = GridLayout(this).apply {
            columnCount = cols
            setPadding(dp(12), dp(16), dp(12), dp(28))
        }
        var rendered = 0
        for (pkg in p.allowedPackages.distinct()) {
            if (pkg in p.hidden) continue // allowed, but the policy keeps it off the home
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
            column.addView(text(getString(R.string.kiosk_default_title), 20f, fg, bold = true))
        }
        if (rendered == 0) {
            column.addView(
                text(
                    getString(R.string.kiosk_no_apps),
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
        // Sharing data (hotspot): unless the policy blocks it, an icon that opens ONLY the phone's hotspot screen (turn
        // it on or off there; Android does not let the MDM switch it on by itself) — see the tile's click handler.
        if (com.dallycontrol.agent.policy.DeviceRules.tetheringOffered(this)) {
            grid.addView(
                appCell(TETHER_TILE, getString(R.string.tether_tile), ContextCompat.getDrawable(this, R.drawable.ic_kiosk_hotspot)!!, cell, fg),
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
        if (com.dallycontrol.agent.kiosk.AndroidKioskLockdown.guardState(this) ==
            com.dallycontrol.kiosk.KioskHardening.GUARD_MISSING_ACCESS
        ) {
            page.addView(
                text(getString(R.string.kiosk_auth_banner), BANNER_SP, Color.WHITE, bold = true).apply {
                    contentDescription = "kiosk-auth-banner"
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setBackgroundColor(Color.parseColor("#B3261E"))
                    isClickable = true
                    setOnClickListener { askAuthorisation(force = true) }
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
                            text(getString(R.string.kiosk_serial, deviceSerial()), 12f, fg).apply { contentDescription = "kiosk-serial" },
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
                } else if (pkg == TETHER_TILE) {
                    // Only the hotspot screen, alone in its task (Back returns here, there is no way "up" into the
                    // rest of Settings), for a short window that ends the moment the kiosk home is back in front.
                    com.dallycontrol.agent.kiosk.TimedAllow.open(
                        this@KioskLauncherActivity, Intent("android.settings.TETHER_SETTINGS"),
                        windowMs = com.dallycontrol.agent.kiosk.TimedAllow.CONFINED_WINDOW_MS, confined = true,
                    )
                        ?.let { android.widget.Toast.makeText(this@KioskLauncherActivity, it, android.widget.Toast.LENGTH_LONG).show() }
                } else if (pkg == QUICK_SETTINGS_TILE) {
                    startActivity(Intent(this@KioskLauncherActivity, com.dallycontrol.agent.kiosk.QuickSettingsActivity::class.java))
                } else if (gated()) {
                    active?.let { setContentView(gateView(it)) }
                    askAuthorisation(force = true)
                } else {
                    cleanLaunchIntent(pkg)?.let { startActivity(it) }
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
        col.addView(centeredText(getString(R.string.kiosk_idle_subtitle), 14f, MUTED))
        addView(col)
    }

    /** Still in kiosk; the pinned app left several times in a row. One tap opens it again. */
    private fun pausedView(p: KioskApplyPayload): View = frame(parseColor(p.theme.backgroundColor, INK)).apply {
        shownGate = false
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
                    text = getString(R.string.kiosk_exit_title)
                    contentDescription = "kiosk-exit-button"
                    setOnClickListener { promptExit(p) }
                }
                parent.addView(
                    FrameWrap(this, btn, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(24)),
                )
            }
            "gesture" -> {
                // Invisible top-right corner target; 7 taps within the window opens the prompt.
                val target = View(this).apply {
                    contentDescription = "kiosk-exit-gesture"
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
        "LARGE" -> dp(80)
        "MEDIUM" -> dp(64)
        else -> dp(48)
    }

    private fun parseColor(value: String?, fallback: Int): Int =
        value?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val BOUNCE_DEDUPE_MS = 1_500L
        const val BANNER_SP = 13f
        const val GATE_TICK_MS = 1_000L

        /** Pseudo-package of the quick-settings tile on the kiosk home. */
        const val QUICK_SETTINGS_TILE = "dallycontrol.quicksettings"
        const val ANNOUNCEMENTS_TILE = "dallycontrol.announcements"
        const val SUPPORT_TILE = "dallycontrol.support:"
        const val TETHER_TILE = "dallycontrol.tether"
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
