package com.dallycontrol.agent.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.dallycontrol.core.config.AppPolicyEnforcer
import com.dallycontrol.core.config.AppPolicyPlanner
import com.dallycontrol.core.kiosk.RoleResolver
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.proto.ConfigAppPolicy
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.EventType
import kotlinx.serialization.json.Json

/**
 * The app policy on the device (open source, no managed Play): apps installed by the user outside the allowed list
 * are SUSPENDED — Android keeps them installed but greys them out and refuses to open them ("paused by your
 * admin") — and unsuspended when allowed later. Re-enforced when an app is installed, so something installed from
 * the Play Store is paused within seconds. `hidePlayStore` hides the store itself.
 */
class AndroidAppPolicyEnforcer(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val roles: RoleResolver,
    private val events: EventSink,
    private val protectedPackages: List<String>,
) : AppPolicyEnforcer {
    private val prefs = context.getSharedPreferences("mdm_app_policy", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

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
        val suspended = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()
        val rolePackages = runCatching { roles.resolve(policy.roles).all }.getOrDefault(emptyList())
        val plan = AppPolicyPlanner.plan(
            policy = policy,
            userInstalled = userInstalled(),
            rolePackages = rolePackages,
            protected = protectedPackages + context.packageName,
            currentlySuspended = suspended,
        )
        val failedSuspend = if (plan.suspend.isEmpty()) emptyList()
        else dpm.setPackagesSuspended(admin, plan.suspend.toTypedArray(), true).toList()
        val failedUnsuspend = if (plan.unsuspend.isEmpty()) emptyList()
        else dpm.setPackagesSuspended(admin, plan.unsuspend.toTypedArray(), false).toList()
        val nowSuspended = (suspended - plan.unsuspend + failedUnsuspend) + (plan.suspend - failedSuspend.toSet())
        prefs.edit().putStringSet(KEY_SUSPENDED, nowSuspended.toSet()).apply()
        (plan.suspend - failedSuspend.toSet()).forEach { events.record(EventType.APP_BLOCKED, it) }

        // --- Structured drift / version reporting for the console ------------------------------------------
        // Anything the policy wanted that did not stick: an app it could not pause (failedSuspend), and an allowed
        // or role app that is missing or has no launcher after everything above ran. Reported as POLICY_DRIFT only
        // when the drift signature changes, so a steady fault is logged once, not every reconcile cycle.
        val pm = context.packageManager
        val wanted = (policy.allowed + rolePackages).toSet() - (protectedPackages + context.packageName).toSet()
        val missing = mutableListOf<String>()
        val noLauncher = mutableListOf<String>()
        val noVersion = mutableListOf<String>()
        for (pkg in wanted) {
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
            if (failedSuspend.isNotEmpty()) add("failedSuspend=${failedSuspend.joinToString(",")}")
            if (missing.isNotEmpty()) add("missing=${missing.joinToString(",")}")
            if (noLauncher.isNotEmpty()) add("noLauncher=${noLauncher.joinToString(",")}")
        }
        emitDriftIfChanged(driftParts)
        emitNoVersion(noVersion)

        val playHidden = runCatching { dpm.setApplicationHidden(admin, PLAY_STORE, policy.hidePlayStore) }
        // Android refuses to pause some apps (e.g. its own "Android Device Policy", com.google.android.apps.work.clouddpc,
        // or a maker's system app): the rest of the policy is in force, so that is a note, not a failure.
        return when {
            playHidden.isFailure && policy.hidePlayStore -> ConfigOutcome.failed("could not hide the Play Store")
            failedSuspend.isNotEmpty() -> "${ConfigOutcome.APPLIED} (Android no deja pausar: ${failedSuspend.joinToString()})"
            else -> ConfigOutcome.APPLIED
        }
    }

    /** Suspended by this policy right now (console / tests). */
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
        const val PLAY_STORE = "com.android.vending"
        /** Last emitted POLICY_DRIFT signature, so an unchanged fault is not re-reported each cycle. */
        const val KEY_DRIFT_SIG = "drift_sig"
        /** Packages already reported as APP_NO_VERSION, cleared when they recover a real version. */
        const val KEY_NOVER_REPORTED = "noversion_reported"
    }
}
