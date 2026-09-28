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
        val plan = AppPolicyPlanner.plan(
            policy = policy,
            userInstalled = userInstalled(),
            rolePackages = runCatching { roles.resolve(policy.roles).all }.getOrDefault(emptyList()),
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

        val playHidden = runCatching { dpm.setApplicationHidden(admin, PLAY_STORE, policy.hidePlayStore) }
        return when {
            failedSuspend.isNotEmpty() -> ConfigOutcome.failed("could not suspend ${failedSuspend.joinToString()}")
            playHidden.isFailure && policy.hidePlayStore -> ConfigOutcome.failed("could not hide the Play Store")
            else -> ConfigOutcome.APPLIED
        }
    }

    /** Suspended by this policy right now (console / tests). */
    fun suspended(): Set<String> = prefs.getStringSet(KEY_SUSPENDED, emptySet()).orEmpty()

    @Suppress("DEPRECATION")
    private fun userInstalled(): List<String> =
        context.packageManager.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }

    private companion object {
        const val KEY_POLICY = "policy"
        const val KEY_SUSPENDED = "suspended"
        const val PLAY_STORE = "com.android.vending"
    }
}
