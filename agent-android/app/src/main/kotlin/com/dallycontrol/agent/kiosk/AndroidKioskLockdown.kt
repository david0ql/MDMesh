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
 *  - the system Settings app is suspended, so neither the quick-settings gear, nor a notification, nor an allowed
 *    app's intent can open it; on the soft kiosk every app outside the kiosk is suspended too;
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
            runCatching { dpm.setStatusBarDisabled(admin, KioskHardening.statusBarDisabled(payload.features.softStatusBar)) }
        }
        if (sdk >= Build.VERSION_CODES.N) suspend(soft, allowed)
    }

    @Synchronized
    override fun release() {
        prefs.edit().putBoolean(KEY_ENGAGED, false).apply()
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        val mine = prefs.getStringSet(KEY_RESTRICTIONS, emptySet()).orEmpty()
        mine.forEach { runCatching { dpm.clearUserRestriction(admin, it) } }
        val suspended = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        if (suspended.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { dpm.setPackagesSuspended(admin, suspended.toTypedArray(), false) }
        }
        prefs.edit().remove(KEY_RESTRICTIONS).remove(KEY_SUSPENDED).remove(KEY_GAP).apply()
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

    private fun suspend(soft: Boolean, allowed: List<String>) {
        val pm = context.packageManager
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }
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
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        val keyboards = runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).inputMethodList.map { it.packageName }
        }.getOrDefault(emptyList())
        val dialer = runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
        }.getOrNull()
        return (ALWAYS_PROTECTED + context.packageName + supportPackages + homes + keyboards + listOfNotNull(dialer))
            // The agent's own HOME is in `homes`; Settings can also answer HOME on some phones (its fallback home):
            // that must not protect it.
            .filterNot { it in KNOWN_SETTINGS }
            .distinct()
    }

    companion object {
        private const val PREFS = "mdm_kiosk_lockdown"
        private const val KEY_ENGAGED = "engaged"
        private const val KEY_RESTRICTIONS = "restrictions"
        private const val KEY_SUSPENDED = "suspended"
        private const val KEY_GAP = "gap"
        private val KNOWN_SETTINGS = listOf(
            "com.android.settings",
            "com.android.settings.intelligence",
            "com.google.android.settings.intelligence",
        )
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
            pkg in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

        /** Let one package the kiosk keeps suspended open for a moment (see [TimedAllow]). */
        fun lift(context: Context, pkg: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !suspendedByKiosk(context, pkg)) return
            lifted += pkg
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            runCatching { dpm.setPackagesSuspended(AdminReceiver.componentName(context), arrayOf(pkg), false) }
        }

        /** Close it again (a no-op once the kiosk was left or no longer suspends it). */
        fun restore(context: Context, pkg: String) {
            lifted -= pkg
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !engaged(context) || !suspendedByKiosk(context, pkg)) return
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            runCatching { dpm.setPackagesSuspended(AdminReceiver.componentName(context), arrayOf(pkg), true) }
        }
    }
}
