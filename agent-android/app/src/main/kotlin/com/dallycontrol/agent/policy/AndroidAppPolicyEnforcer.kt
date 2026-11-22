package com.dallycontrol.agent.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.dallycontrol.core.config.AppPolicyEnforcer
import com.dallycontrol.core.config.AppPolicyPlanner
import com.dallycontrol.core.install.InstallManager
import com.dallycontrol.core.install.InstallOutcome
import com.dallycontrol.core.kiosk.RoleResolver
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.proto.ConfigAppPolicy
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The app policy on the device (open source, no managed Play): apps outside the allowed list are HIDDEN — they
 * vanish from the launcher and the app drawer (setApplicationHidden), re-hidden every cycle so a reappearing or
 * self-reinstalling one (e.g. Facebook via its system installer) disappears again — and, where the app can be
 * removed (a third-party install), also UNINSTALLED for good. Re-enforced when an app is installed, so something
 * from the Play Store is hidden within seconds. `hidePlayStore` hides the store itself.
 */
class AndroidAppPolicyEnforcer(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val roles: RoleResolver,
    private val events: EventSink,
    private val protectedPackages: List<String>,
    private val installManager: InstallManager,
) : AppPolicyEnforcer {
    private val prefs = context.getSharedPreferences("mdm_app_policy", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Synchronized
    override fun apply(policy: ConfigAppPolicy): String {
        prefs.edit().putString(KEY_POLICY, json.encodeToString(ConfigAppPolicy.serializer(), policy)).apply()
        return enforce(policy)
    }

    @Synchronized
    override fun reenforce() {
        val p = prefs.getString(KEY_POLICY, null)
            ?.let { runCatching { json.decodeFromString(ConfigAppPolicy.serializer(), it) }.getOrNull() } ?: return
        enforce(p)
    }

    private fun enforce(policy: ConfigAppPolicy): String {
        if (Build.VERSION.SDK_INT < 24) return ConfigOutcome.UNSUPPORTED
        val hiddenBefore = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        val rolePackages = runCatching { roles.resolve(policy.roles).all }.getOrDefault(emptyList())
        val plan = AppPolicyPlanner.plan(
            policy = policy,
            userInstalled = userInstalled(),
            rolePackages = rolePackages,
            protected = protectedPackages + context.packageName,
            currentlySuspended = hiddenBefore,
        )
        // Every installed app outside the allowed list.
        val blocked = plan.uninstall

        // Un-hide apps that are no longer blocked (newly allowed), so they come back.
        (hiddenBefore - blocked).forEach { runCatching { dpm.setApplicationHidden(admin, it, false) } }

        // Hide the blocked apps: they disappear from the launcher and the app drawer (not merely greyed out /
        // "paused by admin" as a suspension would). Re-hide every cycle so an app that reappears — a reinstalled
        // or preloaded one that cannot be uninstalled, e.g. Facebook via its system installer — is hidden again.
        val failedHide = mutableSetOf<String>()
        for (pkg in blocked) {
            val isHidden = runCatching { dpm.isApplicationHidden(admin, pkg) }.getOrDefault(false)
            if (!isHidden && !runCatching { dpm.setApplicationHidden(admin, pkg, true) }.getOrDefault(false)) {
                failedHide.add(pkg)
            }
        }
        prefs.edit().putStringSet(KEY_SUSPENDED, blocked).apply()
        (blocked - hiddenBefore).forEach { events.record(EventType.APP_BLOCKED, it) }

        // Self-heal allowed apps that a maker left installed-but-DISABLED. Some preloads (e.g. the Microsoft apps on
        // Samsung) sit installed as a disabled system package with NO launcher entry, so they never show in the kiosk
        // or the launcher even though the policy allows them. For each allowed/role app that is installed yet not
        // launchable, re-enable it as Device Owner (no ADB needed). All calls are idempotent and skipped once the app
        // is launchable, so steady state costs nothing.
        val pm = context.packageManager
        val wanted = (policy.allowed + rolePackages).toSet()
        // Un-suspend any allowed app a previous policy/agent left suspended — a suspended app still shows in the
        // launcher but throws the system "action not allowed, contact your IT admin" dialog when opened. Idempotent.
        if (wanted.isNotEmpty()) runCatching { dpm.setPackagesSuspended(admin, wanted.toTypedArray(), false) }
        for (pkg in wanted) {
            val present = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            if (present && runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() == null) {
                runCatching { dpm.enableSystemApp(admin, pkg) }
                runCatching { dpm.setApplicationHidden(admin, pkg, false) }
                runCatching { pm.setApplicationEnabledSetting(pkg, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0) }
            }
        }

        // Also remove the blocked apps for good where possible (third-party ones): a Device Owner uninstalls them
        // silently. BUT only ever ATTEMPT each package ONCE. Several Samsung preloads (Facebook, Instagram, TikTok,
        // YouTube Music, Samsung Free / FM Radio...) are reinstalled by a maker service the instant they are
        // removed, so uninstalling them every cycle was an endless PackageManager churn (hundreds of thousands of
        // ops/day across the fleet) that kept the CPU awake and drained the battery while achieving nothing — the
        // hide step above already makes them vanish and stops them running. So we try once; if a package comes back
        // we leave it installed-but-hidden instead of fighting the reinstaller forever. A package that is later
        // allowed (no longer blocked) is cleared below, so re-blocking it attempts a fresh uninstall.
        val triedBefore = prefs.getStringSet(KEY_UNINST_TRIED, emptySet()).orEmpty()
        // Forget packages that are no longer blocked (newly allowed) so a future re-block can uninstall again.
        val triedStill = triedBefore intersect blocked
        if (triedStill.size != triedBefore.size) {
            prefs.edit().putStringSet(KEY_UNINST_TRIED, triedStill).apply()
        }
        val toRemove = blocked.filterNot { it in triedStill }
        if (toRemove.isNotEmpty()) {
            scope.launch {
                for (pkg in toRemove) {
                    // Mark as tried BEFORE the attempt: a reappearance is then never uninstalled again, even if
                    // this attempt reports failure. This single-attempt rule is what breaks the battery-killing loop.
                    synchronized(this@AndroidAppPolicyEnforcer) {
                        val t = prefs.getStringSet(KEY_UNINST_TRIED, emptySet()).orEmpty() + pkg
                        prefs.edit().putStringSet(KEY_UNINST_TRIED, t).apply()
                    }
                    if (installManager.uninstall(pkg) is InstallOutcome.Success) {
                        events.record(EventType.APP_UNINSTALLED, pkg)
                        synchronized(this@AndroidAppPolicyEnforcer) {
                            val s = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty() - pkg
                            prefs.edit().putStringSet(KEY_SUSPENDED, s).apply()
                        }
                    }
                }
            }
        }

        // --- Structured drift / version reporting for the console ------------------------------------------
        // Anything the policy wanted that did not stick: an app it could not pause (failedSuspend), and an allowed
        // or role app that is missing or has no launcher after everything above ran. Reported as POLICY_DRIFT only
        // when the drift signature changes, so a steady fault is logged once, not every reconcile cycle.
        val driftWanted = (policy.allowed + rolePackages).toSet() - (protectedPackages + context.packageName).toSet()
        val missing = mutableListOf<String>()
        val noLauncher = mutableListOf<String>()
        val noVersion = mutableListOf<String>()
        for (pkg in driftWanted) {
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            if (info == null) {
                missing += pkg
                continue
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            // Installed but with no real version (code 0) = a broken/stub install the console should re-push.
            if (code <= 0L) noVersion += pkg
            if (runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() == null) noLauncher += pkg
        }
        val driftParts = buildList {
            if (failedHide.isNotEmpty()) add("failedHide=${failedHide.joinToString(",")}")
            if (missing.isNotEmpty()) add("missing=${missing.joinToString(",")}")
            if (noLauncher.isNotEmpty()) add("noLauncher=${noLauncher.joinToString(",")}")
        }
        emitDriftIfChanged(driftParts)
        emitNoVersion(noVersion)

        val playHidden = runCatching { dpm.setApplicationHidden(admin, PLAY_STORE, policy.hidePlayStore) }
        // Android refuses to hide some apps (e.g. its own "Android Device Policy", or a maker's core system app):
        // the rest of the policy is in force, so that is a note, not a failure.
        return when {
            playHidden.isFailure && policy.hidePlayStore -> ConfigOutcome.failed("could not hide the Play Store")
            failedHide.isNotEmpty() -> "${ConfigOutcome.APPLIED} (Android no deja ocultar: ${failedHide.joinToString()})"
            else -> ConfigOutcome.APPLIED
        }
    }

    /** Hidden by this policy right now (console / tests). */
    fun suspended(): Set<String> = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

    /**
     * Emit [EventType.POLICY_DRIFT] only when the set of problems changes, so a persistent fault is reported once
     * (not on every reconcile). An empty signature (drift cleared) is stored but not emitted.
     */
    private fun emitDriftIfChanged(parts: List<String>) {
        val signature = parts.sorted().joinToString(";")
        if (signature == prefs.getString(KEY_DRIFT_SIG, "").orEmpty()) return
        prefs.edit().putString(KEY_DRIFT_SIG, signature).apply()
        if (signature.isNotEmpty()) events.record(EventType.POLICY_DRIFT, signature)
    }

    /**
     * Emit [EventType.APP_NO_VERSION] once per package that is installed with version code 0, until it recovers a
     * real version. We remember what we already reported so a lingering stub is not re-emitted every cycle, and a
     * recovered package is cleared so a future regression reports again. No install is attempted here — the console
     * decides whether to re-push (e.g. via `device.appInstallForce`).
     */
    private fun emitNoVersion(current: List<String>) {
        val reported = prefs.getStringSet(KEY_NOVER_REPORTED, emptySet()).orEmpty()
        val cur = current.toSet()
        (cur - reported).forEach { events.record(EventType.APP_NO_VERSION, it) }
        if (cur != reported) prefs.edit().putStringSet(KEY_NOVER_REPORTED, cur).apply()
    }

    @Suppress("DEPRECATION")
    private fun userInstalled(): List<String> =
        context.packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }

    private companion object {
        const val KEY_POLICY = "policy"
        const val KEY_SUSPENDED = "suspended"
        const val KEY_UNINST_TRIED = "uninstall_tried"
        const val PLAY_STORE = "com.android.vending"
        /** Last emitted POLICY_DRIFT signature, so an unchanged fault is not re-reported each cycle. */
        const val KEY_DRIFT_SIG = "drift_sig"
        /** Packages already reported as APP_NO_VERSION, cleared when they recover a real version. */
        const val KEY_NOVER_REPORTED = "noversion_reported"
    }
}
