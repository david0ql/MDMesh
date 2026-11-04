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
        val plan = AppPolicyPlanner.plan(
            policy = policy,
            userInstalled = userInstalled(),
            rolePackages = runCatching { roles.resolve(policy.roles).all }.getOrDefault(emptyList()),
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

        // Also remove the blocked apps for good where possible (third-party ones): a Device Owner uninstalls them
        // silently. The ones it cannot remove (system / self-reinstalling) stay hidden by the step above.
        if (blocked.isNotEmpty()) {
            val toRemove = blocked.toList()
            scope.launch {
                for (pkg in toRemove) {
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
