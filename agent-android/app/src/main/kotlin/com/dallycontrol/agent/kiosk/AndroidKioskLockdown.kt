package com.dallycontrol.agent.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.dallycontrol.agent.admin.AdminReceiver
import com.dallycontrol.core.kiosk.KioskLockdown
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.kiosk.KioskHardening
import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.KioskApplyPayload

/**
 * The kiosk lock-down on the device (Device Owner). On top of lock task (Android ≤ 15) or instead of it (the soft
 * kiosk of Android 16+, see [KioskHardening.softKiosk]):
 *
 *  - the kiosk user restrictions ([KioskHardening.restrictions]): no safe mode, no new users, no crash dialogs;
 *  - the apps that lead into the system Settings (its search app…) are suspended, and on the soft kiosk every app
 *    outside the kiosk is: a suspended app cannot be opened by anyone;
 *  - the Settings app itself is NEVER suspended or hidden: it hosts the home screen Android shows while it starts
 *    (FallbackHome). Suspended, the phone boots into an "action not allowed" notice instead of the kiosk; hidden, it
 *    does not finish booting at all (both seen on emulators). It is kept out of reach by lock task, which from
 *    Android 11 also blocks an allowed app from opening it inside its own task
 *    ([KioskHardening.blockInTaskStarts]); where that is not available (Android 7–10, and the soft kiosk)
 *    [KioskForegroundGuard] sends the phone back to the kiosk the moment Settings shows up;
 *  - on the soft kiosk the status bar is disabled unless the policy explicitly allows notifications.
 *
 * Only what this class set is undone on [release] (kept in its own preferences), so restrictions owned by the
 * policy's device rules or the anti-theft PIN are never lifted from here.
 */
class AndroidKioskLockdown(
    context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val events: EventSink,
    /** Never suspended: packages the agent drives itself (droidVNC-NG for remote support). */
    private val supportPackages: List<String> = emptyList(),
) : KioskLockdown {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun engage(payload: KioskApplyPayload, allowed: List<String>) {
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val sdk = Build.VERSION.SDK_INT
        val soft = KioskHardening.softKiosk(sdk)
        // A phone that was already working in a kiosk when this agent arrived as an update keeps the old behaviour
        // (strip + periodic prompt) until it is authorised or leaves the kiosk; everything else is gated (see below).
        if (!prefs.contains(KEY_FIELD)) {
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val field = info != null && KioskHardening.fieldKiosk(
                prefs.contains(KEY_ENGAGED), info.firstInstallTime, info.lastUpdateTime, System.currentTimeMillis(),
            )
            prefs.edit().putBoolean(KEY_FIELD, field).apply()
        }
        prefs.edit().putBoolean(KEY_ENGAGED, true).apply()
        restrict(KioskHardening.restrictions(sdk))
        // Settings (never suspended) is kept out of the foreground by the guard wherever lock task does not already
        // do it; on the soft kiosk the guard sends back ANY app the kiosk does not allow (whatever Android refused to
        // suspend included).
        val settings = settingsPackages().filter { it in BOOT_CRITICAL }.toSet() - allowed.toSet()
        val guardNeeded = KioskHardening.needsForegroundGuard(sdk) && settings.isNotEmpty()
        val open = (allowed + protectedPackages()).toSet() - settings
        val closed: (String) -> Boolean = if (soft) { pkg -> pkg !in open } else { pkg -> pkg in settings }
        // "Switched on" counts from the setting itself: right after a restart the service is on but connects a
        // few seconds later, and that must not look like a phone that was never authorised.
        val serviceOn = KioskForegroundGuard.screenServiceOn() ||
            com.dallycontrol.agent.support.SupportEngine.isServiceOn(context)
        val canWatch = guardNeeded && (serviceOn || KioskForegroundGuard.canWatch(context))
        // How Settings is kept closed on this phone, for the console (reported when it changes): by lock task, by the
        // guard, or — the guard needs one tap on the phone (the agent's screen service) — not yet.
        val state = KioskHardening.settingsGuard(sdk, guardNeeded, canWatch)
        // Closed until authorised: without the guard's one tap no app of the kiosk can be opened (they are suspended
        // too, so neither the kiosk home nor Recents nor a notification opens them) — there is no kiosk in use from
        // which an allowed app could reach Settings. Lifted by the next engage once the screen service is on.
        if (state != KioskHardening.GUARD_MISSING_ACCESS && prefs.getBoolean(KEY_FIELD, false)) {
            prefs.edit().putBoolean(KEY_FIELD, false).apply()
        }
        val gate = KioskHardening.authGate(state, strict(context))
        val gateChanged = gate != prefs.getBoolean(KEY_GATE, false)
        // The kiosk home follows this flag: it is raised BEFORE the apps are suspended and lowered only AFTER they are
        // usable again, so the home never opens an app that is still (or already) suspended.
        if (gate && gateChanged) prefs.edit().putBoolean(KEY_GATE, true).commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            suspend(soft, allowed, if (gate) KioskHardening.gatePlan(allowed, protectedPackages()) else emptySet())
        }
        if (!gate && gateChanged) prefs.edit().putBoolean(KEY_GATE, false).commit()
        if (gateChanged) {
            events.record(
                EventType.KIOSK_LOCKDOWN_GAP,
                if (gate) "authGate: quiosco cerrado hasta dar la autorización en el teléfono" else "authGate: off",
            )
        }
        if (state != prefs.getString(KEY_GUARD, null)) {
            prefs.edit().putString(KEY_GUARD, state).apply()
            events.record(EventType.KIOSK_GUARD, state)
            // Authorised now: Settings, let through for the guided step, is closed again at once.
            if (state != KioskHardening.GUARD_MISSING_ACCESS) TimedAllow.revoke(context, "com.android.settings")
            // Tell the console now (the tap on the phone is not followed by any command that would).
            runCatching { com.dallycontrol.core.sync.CheckInWorker.scheduleNow(context) }
        }
        if (soft) {
            // Without a guard the status bar stays closed whatever the policy says: its gear leads to Settings.
            val bar = KioskHardening.statusBarDisabled(payload.features.softStatusBar) || !canWatch
            runCatching { dpm.setStatusBarDisabled(admin, bar) }
        }
        // Not authorised yet: ask now (and again whenever the kiosk home comes back), never stay quiet about it.
        // (With the gate up the kiosk home asks, once it is in front: asked from here it would be covered by that home.)
        if (state == KioskHardening.GUARD_MISSING_ACCESS && !gate) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { KioskAuthPrompt.ask(context) } }
        }
        KioskForegroundGuard.onServiceChange = { reengage() }
        if (guardNeeded) {
            KioskForegroundGuard.guard(context, closed, { it in lifted }) { pkg ->
                events.record(EventType.KIOSK_ESCAPE_BLOCKED, pkg)
            }
        } else {
            KioskForegroundGuard.stop()
        }
        lastEngaged = payload to allowed
    }

    @Volatile private var lastEngaged: Pair<KioskApplyPayload, List<String>>? = null

    /** The screen service came or went: re-evaluate the guard with what was engaged last. */
    private fun reengage() {
        kotlin.concurrent.thread(name = "kiosk-lockdown") {
            // Under the same lock as release(): a kiosk that was left meanwhile is never engaged again from here.
            synchronized(this) {
                val (payload, allowed) = lastEngaged ?: return@thread
                if (engaged(context)) runCatching { engage(payload, allowed) }
            }
        }
    }

    /**
     * The native lock of Android 16+ is honoured unless this phone was seen restarting in a loop with it on (its
     * known failure): then the kiosk goes back to the soft lock by itself and says so.
     */
    @Synchronized
    @Suppress("ReturnCount") // not asked for / already tripped / tripping now / allowed
    override fun nativeLockAllowed(wanted: Boolean): Boolean {
        if (!wanted || Build.VERSION.SDK_INT < KioskHardening.SOFT_KIOSK_SDK) return wanted
        if (prefs.getBoolean(KEY_NATIVE_TRIPPED, false)) return false
        val boot = runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "boot_count") }
            .getOrDefault(NO_BOOT_COUNT)
        if (boot != prefs.getInt(KEY_NATIVE_BOOT, NO_BOOT_SEEN)) {
            val times = (prefs.getString(KEY_NATIVE_BOOTS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() } +
                System.currentTimeMillis()).takeLast(KioskHardening.NATIVE_LOCK_MAX_QUICK_BOOTS)
            prefs.edit().putInt(KEY_NATIVE_BOOT, boot).putString(KEY_NATIVE_BOOTS, times.joinToString(",")).apply()
            if (KioskHardening.nativeLockLooping(times)) {
                prefs.edit().putBoolean(KEY_NATIVE_TRIPPED, true).apply()
                events.record(EventType.KIOSK_LOCKDOWN_GAP, "nativeLock16 apagado en este equipo: reinicios en cadena")
                return false
            }
        }
        return true
    }

    @Synchronized
    override fun release() {
        KioskForegroundGuard.stop()
        // The console shows the guard's state per device: say when there is no kiosk any more.
        if (prefs.getString(KEY_GUARD, null) != null) {
            events.record(EventType.KIOSK_GUARD, "off")
            runCatching { com.dallycontrol.core.sync.CheckInWorker.scheduleNow(context) }
        }
        prefs.edit().putBoolean(KEY_ENGAGED, false).apply()
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val mine = prefs.getStringSet(KEY_RESTRICTIONS, emptySet()).orEmpty()
        mine.forEach { runCatching { dpm.clearUserRestriction(admin, it) } }
        val suspended = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        if (suspended.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false) }
        }
        prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            .forEach { runCatching { dpm.setApplicationHidden(admin, it, false) } }
        prefs.edit().remove(KEY_RESTRICTIONS).remove(KEY_SUSPENDED).remove(KEY_HIDDEN).remove(KEY_GAP).remove(KEY_GUARD)
            .remove(KEY_GATE).putBoolean(KEY_FIELD, false).apply()
        lastEngaged = null
        if (Build.VERSION.SDK_INT >= KioskHardening.SOFT_KIOSK_SDK) {
            runCatching { dpm.setStatusBarDisabled(admin, false) }
        }
    }

    /** Add the restrictions; remember as "mine" only those that were not already on (someone else owns those). */
    private fun restrict(wanted: Set<String>) {
        val mine = prefs.getStringSet(KEY_RESTRICTIONS, emptySet()).orEmpty()
        val active = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) dpm.getUserRestrictions(admin) else null
        }.getOrNull()
        val added = mutableSetOf<String>()
        for (key in wanted) {
            val alreadyOn = active?.getBoolean(key, false) == true
            if (alreadyOn) continue
            if (runCatching { dpm.addUserRestriction(admin, key) }.isSuccess) added += key
        }
        if (added.isNotEmpty()) prefs.edit().putStringSet(KEY_RESTRICTIONS, mine + added).apply()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private fun suspend(soft: Boolean, allowed: List<String>, gated: Set<String>) {
        val pm = context.packageManager
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val protected = protectedPackages()
        val hiddenBefore = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        // What this kiosk hid stays in the plan (a hidden app no longer shows up in the query above).
        val plan = KioskHardening.suspendPlan(soft, launchable, settingsPackages(), allowed, protected) +
            (hiddenBefore - allowed.toSet() - protected.toSet()) + gated
        val before = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

        // Lift what this kiosk no longer wants closed (an app the policy now allows).
        val lift = before - plan
        if (lift.isNotEmpty()) runCatching { dpm.setPackagesSuspended(admin, lift.toTypedArray(), false) }
        (hiddenBefore - plan).forEach { runCatching { dpm.setApplicationHidden(admin, it, false) } }

        // Suspend only what is not suspended yet (no package broadcasts in steady state); skip a package a console
        // action is letting through right now (TimedAllow).
        val todo = plan.filter { it !in lifted && it !in hiddenBefore && !isSuspended(it) }
        val refused = if (todo.isEmpty()) emptyList()
        else runCatching { dpm.setPackagesSuspended(admin, todo.toTypedArray(), true).toList() }.getOrDefault(todo)
        // An app Android will not suspend is hidden instead. Never the Settings app nor anything hosting a home
        // screen: those are in `protected`, so they are not in the plan at all.
        val hiddenNow = refused.filter { runCatching { dpm.setApplicationHidden(admin, it, true) }.getOrDefault(false) }
        val failed = refused - hiddenNow.toSet()
        prefs.edit().putStringSet(KEY_SUSPENDED, plan)
            .putStringSet(KEY_HIDDEN, (hiddenBefore intersect plan) + hiddenNow).apply()

        // Android refuses to suspend a few packages; say which once, so the console knows what stays reachable.
        val gap = failed.sorted().joinToString(",")
        if (gap != prefs.getString(KEY_GAP, "").orEmpty()) {
            prefs.edit().putString(KEY_GAP, gap).apply()
            if (gap.isNotEmpty()) events.record(EventType.KIOSK_LOCKDOWN_GAP, "notSuspended=$gap")
        }
    }

    private fun isSuspended(pkg: String): Boolean = runCatching {
        (context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SUSPENDED) != 0
    }.getOrDefault(false)

    /** The packages serving the system Settings (whoever answers the plain "Settings" intent, and its search app). */
    private fun settingsPackages(): List<String> {
        val pm = context.packageManager
        val byIntent = runCatching {
            pm.queryIntentActivities(Intent(Settings.ACTION_SETTINGS), 0)
                .filter { (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val known = KNOWN_SETTINGS.filter { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
        return (byIntent + known).distinct()
    }

    /** Never suspended, whatever the kiosk allows: without them the phone (or the agent) stops working. */
    private fun protectedPackages(): List<String> {
        val pm = context.packageManager
        val homes = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val keyboards = runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList
                .map { it.packageName }
        }.getOrDefault(emptyList())
        val dialer = runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
        }.getOrNull()
        // `homes` includes Settings wherever it hosts Android's boot-time home (FallbackHome): never suspended.
        return (ALWAYS_PROTECTED + BOOT_CRITICAL + context.packageName + supportPackages + homes + keyboards +
            listOfNotNull(dialer)).distinct()
    }

    companion object {
        private const val PREFS = "mdm_kiosk_lockdown"
        private const val KEY_ENGAGED = "engaged"
        private const val KEY_RESTRICTIONS = "restrictions"
        private const val KEY_SUSPENDED = "suspended"
        private const val KEY_GAP = "gap"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_GUARD = "guard_state"
        private const val KEY_GATE = "auth_gate"
        private const val KEY_FIELD = "field_kiosk"

        /**
         * Whether a kiosk without its authorisation is closed altogether ([KioskHardening.authGate]): yes, unless the
         * policy turned the guided step off or this phone was already working in a kiosk when the agent was updated.
         */
        fun strict(context: Context): Boolean =
            com.dallycontrol.agent.support.SupportEngine.settings(context).setupPrompt &&
                !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FIELD, false)

        /** The kiosk is closed until the one authorisation is given on the phone (nothing can be opened). */
        fun gated(context: Context): Boolean =
            engaged(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GATE, false)
        private const val KEY_NATIVE_TRIPPED = "native16_tripped"
        private const val KEY_NATIVE_BOOT = "native16_boot"
        private const val KEY_NATIVE_BOOTS = "native16_boots"
        private const val NO_BOOT_COUNT = -1
        private const val NO_BOOT_SEEN = -2

        /** How this kiosk keeps Settings closed, as last reported; null when there is no kiosk. */
        fun guardState(context: Context): String? =
            if (!engaged(context)) null
            else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GUARD, null)
        private val KNOWN_SETTINGS = listOf(
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.google.android.settings.intelligence",
        )
        /** Hosts the home screen Android shows while it boots: suspending or hiding it breaks the start-up. */
        private val BOOT_CRITICAL = listOf("com.android.settings")
        private val ALWAYS_PROTECTED = listOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.google.android.gms",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )

        /** Packages a console action is letting through for a moment ([TimedAllow]); [engage] leaves them alone. */
        private val lifted: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        /** True while a kiosk lock-down is in force on this device. */
        fun engaged(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENGAGED, false)

        private fun suspendedByKiosk(context: Context, pkg: String): Boolean =
            pkg in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

        /** Let one package the kiosk keeps closed (suspended, or sent back by the guard) open for a moment. */
        fun lift(context: Context, pkg: String) {
            lifted += pkg
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !suspendedByKiosk(context, pkg)) return
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            runCatching { dpm.setPackagesSuspended(AdminReceiver.componentName(context), arrayOf(pkg), false) }
        }

        /** Close it again (a no-op once the kiosk was left or no longer suspends it). */
        fun restore(context: Context, pkg: String) {
            lifted -= pkg
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            if (!engaged(context) || !suspendedByKiosk(context, pkg)) return
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            runCatching { dpm.setPackagesSuspended(AdminReceiver.componentName(context), arrayOf(pkg), true) }
        }
    }
}

/**
 * Where lock task cannot keep the system Settings closed — Android 7–10 (an allowed app may open it inside its own
 * task) and the soft kiosk of Android 16+ (no lock task) — this is the last line: while the screen is on it looks at
 * which app is in front (usage access, granted by the cable enrollment) a few times a second and, when it is one the
 * kiosk keeps closed, brings the kiosk home back at once and reports it. A mitigation, not a wall: the screen can
 * show for a moment.
 */
@Suppress("TooManyFunctions") // two sources (screen service, usage access), each with its start/stop
internal object KioskForegroundGuard {
    private const val PERIOD_MS = 350L
    private const val BACKSTOP_MS = 600L
    private const val LOOKBACK_MS = 4_000L
    private const val REPORT_EVERY_MS = 60_000L
    private var thread: android.os.HandlerThread? = null

    /** The app seen in front last and the one before it (kept between polls and guard restarts). */
    @Volatile private var last: Pair<String?, String?> = null to null

    /** What the running guard watches for (null = no kiosk needs a guard right now). */
    private class Watch(
        val closed: (String) -> Boolean,
        val letThrough: (String) -> Boolean,
        val onBlocked: (String) -> Unit,
    )

    @Volatile private var watch: Watch? = null

    /** True while a kiosk needs the screen service to report every app's window changes. */
    val watching: Boolean get() = watch != null

    /** Asked for after the screen service connected or went away: re-evaluate how the kiosk is guarded. */
    @Volatile var onServiceChange: (() -> Unit)? = null

    fun onScreenServiceChanged() {
        runCatching { onServiceChange?.invoke() }
    }

    /** The agent's screen service is connected: the guard then needs neither usage access nor polling. */
    fun screenServiceOn(): Boolean = com.dallycontrol.agent.support.SupportAccessibilityService.instance != null

    @Volatile private var lastWindow: String? = null
    @Volatile private var lastWindowReport = 0L

    /**
     * From the screen service, the moment a window of [pkg] opens: when the kiosk keeps that app closed the phone
     * goes Home (the kiosk) at once.
     */
    fun onWindow(service: android.accessibilityservice.AccessibilityService, pkg: String) {
        val w = watch ?: return
        val before = lastWindow
        lastWindow = pkg
        if (!w.closed(pkg) || w.letThrough(pkg)) return
        val fromApp = before != null && before != pkg && before != service.packageName
        if (fromApp && !w.closed(before!!)) tainted += before
        // Home twice over: the global action (instant) and the HOME intent (lock task ignores the first on some
        // releases).
        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            service.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastWindowReport == 0L || now - lastWindowReport > REPORT_EVERY_MS) {
            lastWindowReport = now
            runCatching { w.onBlocked(pkg) }
        }
    }

    /** Start guarding with whatever source there is: the screen service (instant) or usage access (polling). */
    @Synchronized
    fun guard(
        context: Context,
        closed: (String) -> Boolean,
        letThrough: (String) -> Boolean,
        onBlocked: (String) -> Unit,
    ) {
        watch = Watch(closed, letThrough, onBlocked)
        com.dallycontrol.agent.support.SupportAccessibilityService.instance?.watchAllWindows(true)
        if (screenServiceOn()) stopPolling() else start(context, closed, letThrough, onBlocked)
        startBackstop()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var backstop = false

    /**
     * Android does not always report a window change (seen on emulators: an existing task brought back to the front,
     * and the first seconds after a restart). So, while the screen service is on, the window in front is also
     * looked at every [BACKSTOP_MS] — inside the agent, no usage access involved.
     */
    private fun startBackstop() {
        if (backstop) return
        backstop = true
        mainHandler.post(object : Runnable {
            override fun run() {
                if (watch == null) {
                    backstop = false
                    return
                }
                runCatching {
                    val service = com.dallycontrol.agent.support.SupportAccessibilityService.instance
                    val front = service?.rootInActiveWindow?.packageName?.toString()
                    if (service != null && front != null) onWindow(service, front)
                }
                mainHandler.postDelayed(this, BACKSTOP_MS)
            }
        })
    }

    @Synchronized
    fun stop() {
        watch = null
        com.dallycontrol.agent.support.SupportAccessibilityService.instance?.watchAllWindows(false)
        stopPolling()
    }

    /**
     * Apps whose task was left with a closed screen on top (the allowed app opened Settings inside its own task):
     * the kiosk opens them afresh once, or every return to the app would land on that screen and bounce again.
     */
    private val tainted: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** True (once) when [pkg] must be opened with a clean task. */
    fun consumeTainted(pkg: String): Boolean = tainted.remove(pkg)

    fun canWatch(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val op = android.app.AppOpsManager.OPSTR_GET_USAGE_STATS
        val mode = ops.checkOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        // "Default" means "as the permission says" (an installer may have granted it).
        mode == android.app.AppOpsManager.MODE_ALLOWED || (
            mode == android.app.AppOpsManager.MODE_DEFAULT &&
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            )
    }.getOrDefault(false)

    @Synchronized
    private fun start(
        context: Context,
        closed: (String) -> Boolean,
        letThrough: (String) -> Boolean,
        onBlocked: (String) -> Unit,
    ) {
        stopPolling()
        val app = context.applicationContext
        val t = android.os.HandlerThread("kiosk-foreground-guard").also { it.start() }
        thread = t
        val handler = android.os.Handler(t.looper)
        val usage = app.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager ?: return
        val power = app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        var lastReport = 0L
        handler.post(object : Runnable {
            override fun run() {
                if (thread !== t) return
                runCatching {
                    if (!power.isInteractive) return@runCatching
                    // What was seen last is remembered: a closed screen that stays in front (the return home was
                    // slow, or refused) keeps being sent back, also once its "came to the front" event is old.
                    val seen = foreground(usage)
                    if (seen.first != null) last = seen
                    val (front, before) = last
                    if (front == null || !closed(front) || letThrough(front)) return@runCatching
                    if (before != null && before != app.packageName) tainted += before
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    app.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport > REPORT_EVERY_MS || lastReport == 0L) {
                        lastReport = now
                        onBlocked(front)
                    }
                }
                handler.postDelayed(this, PERIOD_MS)
            }
        })
    }

    @Synchronized
    private fun stopPolling() {
        thread?.quitSafely()
        thread = null
    }

    /**
     * The package whose screen was resumed last and the one in front just before it; nulls when unknown (no usage
     * access, nothing recent).
     */
    private fun foreground(usage: android.app.usage.UsageStatsManager): Pair<String?, String?> {
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - LOOKBACK_MS, now) ?: return null to null
        val e = android.app.usage.UsageEvents.Event()
        var front: String? = null
        var before: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName != front) {
                before = front
                front = e.packageName
            }
        }
        return front to before
    }
}
