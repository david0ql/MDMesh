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
        prefs.edit().putBoolean(KEY_ENGAGED, true).apply()
        restrict(KioskHardening.restrictions(sdk))
        if (soft) {
            val closed = KioskHardening.statusBarDisabled(payload.features.softStatusBar)
            runCatching { dpm.setStatusBarDisabled(admin, closed) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) suspend(soft, allowed)
        // Settings (never suspended) and, on the soft kiosk, whatever Android refused to suspend are kept out of the
        // foreground by the guard wherever lock task does not already do it.
        val unsuspended = prefs.getString(KEY_GAP, "").orEmpty().split(',').filter { it.isNotBlank() }
        val open = (settingsPackages().filter { it in BOOT_CRITICAL } + if (soft) unsuspended else emptyList()).toSet() -
            allowed.toSet()
        val blind = KioskHardening.needsForegroundGuard(sdk) && open.isNotEmpty() && !KioskForegroundGuard.canWatch(context)
        if (blind != prefs.getBoolean(KEY_BLIND, false)) {
            prefs.edit().putBoolean(KEY_BLIND, blind).apply()
            // Without usage access nothing sends the phone back from Settings on this Android version: say so.
            if (blind) events.record(EventType.KIOSK_LOCKDOWN_GAP, "settingsNotGuarded=sin acceso de uso para el agente")
        }
        if (KioskHardening.needsForegroundGuard(sdk) && open.isNotEmpty()) {
            KioskForegroundGuard.start(context, open, { it in lifted }) { pkg ->
                events.record(EventType.KIOSK_ESCAPE_BLOCKED, pkg)
            }
        } else {
            KioskForegroundGuard.stop()
        }
    }

    @Synchronized
    override fun release() {
        KioskForegroundGuard.stop()
        prefs.edit().putBoolean(KEY_ENGAGED, false).apply()
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val mine = prefs.getStringSet(KEY_RESTRICTIONS, emptySet()).orEmpty()
        mine.forEach { runCatching { dpm.clearUserRestriction(admin, it) } }
        val suspended = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        if (suspended.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false) }
        }
        prefs.edit().remove(KEY_RESTRICTIONS).remove(KEY_SUSPENDED).remove(KEY_GAP).remove(KEY_BLIND).apply()
        if (KioskHardening.softKiosk(Build.VERSION.SDK_INT)) runCatching { dpm.setStatusBarDisabled(admin, false) }
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
    private fun suspend(soft: Boolean, allowed: List<String>) {
        val pm = context.packageManager
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val plan = KioskHardening.suspendPlan(soft, launchable, settingsPackages(), allowed, protectedPackages())
        val before = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

        // Lift what this kiosk no longer wants closed (an app the policy now allows).
        val lift = before - plan
        if (lift.isNotEmpty()) runCatching { dpm.setPackagesSuspended(admin, lift.toTypedArray(), false) }

        // Suspend only what is not suspended yet (no package broadcasts in steady state); skip a package a console
        // action is letting through right now (TimedAllow).
        val todo = plan.filter { it !in lifted && !isSuspended(it) }
        val failed = if (todo.isEmpty()) emptyList()
        else runCatching { dpm.setPackagesSuspended(admin, todo.toTypedArray(), true).toList() }.getOrDefault(todo)
        prefs.edit().putStringSet(KEY_SUSPENDED, plan).apply()

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
        private const val KEY_BLIND = "guard_blind"
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
internal object KioskForegroundGuard {
    private const val PERIOD_MS = 350L
    private const val LOOKBACK_MS = 4_000L
    private const val REPORT_EVERY_MS = 60_000L
    private var thread: android.os.HandlerThread? = null

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
        mode == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    @Synchronized
    fun start(context: Context, closed: Set<String>, letThrough: (String) -> Boolean, onBlocked: (String) -> Unit) {
        stop()
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
                    val (front, before) = foreground(usage)
                    if (front == null || front !in closed || letThrough(front)) return@runCatching
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
    fun stop() {
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
