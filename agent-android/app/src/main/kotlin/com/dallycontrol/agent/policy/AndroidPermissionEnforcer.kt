package com.dallycontrol.agent.policy

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import com.dallycontrol.core.config.PermissionEnforcer
import com.dallycontrol.core.config.PermissionPlanner
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.AppPermissionState
import com.dallycontrol.proto.ConfigAppPermissions
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.ConfigPermissions
import com.dallycontrol.proto.PermissionCatalog
import com.dallycontrol.proto.PermissionReport
import com.dallycontrol.proto.PermissionRule
import com.dallycontrol.proto.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * App permissions without the user (Device Owner).
 *
 *  - Default policy: `DevicePolicyManager.setPermissionPolicy` (grant / prompt / deny what any app asks for from now
 *    on), re-asserted on every policy apply and on every agent start.
 *  - Per app and permission: `setPermissionGrantState`, which also covers apps already installed and permissions
 *    already answered, and fixes the answer (the user cannot change it). Run again the moment an app is installed.
 *  - Android 12+: sensor permissions (location, camera, microphone, body sensors, activity) can only be GRANTED when
 *    `canAdminGrantSensorsPermissions()` — false when the phone was provisioned with
 *    `EXTRA_PROVISIONING_SENSORS_PERMISSION_GRANT_OPT_OUT`; those grants are skipped and reported.
 *  - Special accesses (all files, overlay, unknown sources…): see [SpecialAccess].
 *
 * Only what this class fixed is released when a rule goes away.
 */
@Suppress("TooManyFunctions") // one small step each: enforce, report, deliver
class AndroidPermissionEnforcer(private val context: Context, handle: DpmHandle) : PermissionEnforcer {
    private val store = PermissionStore(context)
    private val grants = PermissionGrants(context, handle)
    private val special = SpecialAccess(context, handle, store)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var pendingSignature: String? = null

    /** Something may have changed since the last report was computed (an enforcement, a screen opened…). */
    @Volatile private var dirty = true
    @Volatile private var computedAt = 0L

    @Synchronized
    override fun apply(policy: ConfigPermissions): String {
        store.policy = policy
        return enforce(policy, null)
    }

    @Synchronized
    override fun reenforce(packageName: String?): String = enforce(store.policy, packageName)

    /** [reenforce] off the caller's thread (broadcast receivers): `setPermissionGrantState` may block. */
    fun reenforceAsync(packageName: String?) {
        scope.launch { runCatching { reenforce(packageName) }.onFailure { Log.w(TAG, "reenforce", it) } }
    }

    /** @param onlyPackage one app (just installed), leaving the default policy and the other apps alone */
    private fun enforce(policy: ConfigPermissions, onlyPackage: String?): String {
        val failure = when {
            !grants.usable() -> ConfigOutcome.UNSUPPORTED
            onlyPackage != null -> null
            else -> grants.setDefaultPolicy(policy.defaultPolicy)?.let(ConfigOutcome::failed)
        }
        val outcome = failure ?: enforceApps(policy, onlyPackage)
        // A single-app pass does not replace the outcome of the last full enforcement.
        if (onlyPackage == null) store.outcome = outcome
        dirty = true
        return outcome
    }

    private fun enforceApps(policy: ConfigPermissions, onlyPackage: String?): String {
        val pass = Pass(grants.canGrantSensors() ?: true)
        val fixed = store.fixed.toMutableMap()
        val rules = policy.apps.associateBy { it.packageName }
        // Every app with a rule, and every app something was fixed for: one that left the rules is released.
        val targets = (rules.keys + fixed.keys).filter { it != context.packageName }
            .filter { onlyPackage == null || it == onlyPackage }
        for (pkg in targets) {
            // Not installed: the record is KEPT. Android 14+ remembers a fixed permission across an uninstall and
            // applies it again when the app comes back, so it must still be released then if its rule is gone.
            val info = grants.packageInfo(pkg) ?: continue
            val now = enforceApp(rules[pkg] ?: ConfigAppPermissions(pkg), info, fixed[pkg].orEmpty(), pass)
            if (now.isEmpty()) fixed.remove(pkg) else fixed[pkg] = now
        }
        if (onlyPackage == null) {
            val protect = policy.apps.filter { PermissionCatalog.BATTERY in it.special }.map { it.packageName }
            special.releaseProtection(protect.toSet())
        }
        store.fixed = fixed
        return pass.outcome()
    }

    /** @return what is fixed for the app after this pass (permission to rule). */
    private fun enforceApp(
        app: ConfigAppPermissions,
        info: PackageInfo,
        fixedBefore: Map<String, String>,
        pass: Pass,
    ): Map<String, String> {
        val plan = PermissionPlanner.plan(app, grants.runtimePermissions(info), pass.canGrantSensors, fixedBefore)
        val now = mutableMapOf<String, String>()
        for (step in plan.steps) {
            val done = grants.run(app.packageName, step)
            val rule = step.rule
            if (!done) pass.refused += "${app.packageName}:${step.permission.substringAfterLast('.')}"
            if (done && rule != null) now[step.permission] = rule
        }
        if (plan.blockedSensors.isNotEmpty()) pass.blockedSensors += app.packageName
        runCatching { special.manage(app) }.onFailure { Log.w(TAG, "special access of ${app.packageName}", it) }
        return now
    }

    /** What one enforcement pass could not do; never a failure, always told. */
    private class Pass(val canGrantSensors: Boolean) {
        val blockedSensors = mutableListOf<String>()
        val refused = mutableListOf<String>()

        fun outcome(): String {
            val notes = buildList {
                if (blockedSensors.isNotEmpty()) add("$SENSORS_BLOCKED: ${blockedSensors.joinToString()}")
                if (refused.isNotEmpty()) add("Android no dejó fijar: ${refused.take(MAX_NOTED).joinToString()}")
            }
            val detail = notes.joinToString("; ")
            return if (notes.isEmpty()) ConfigOutcome.APPLIED else "${ConfigOutcome.APPLIED} ($detail)"
        }
    }

    /** @return null when the special-access screen opened on the phone, else why it could not. */
    fun openSpecial(pkg: String, access: String): String? {
        val info = grants.packageInfo(pkg)
        return if (info == null) "la app no está instalada" else special.open(info, access)
    }

    /** The real state right now: effective policy, sensor-grant ability and every managed app's permissions. */
    @Synchronized
    fun report(): PermissionReport {
        // The managed apps: every package a rule names, then whatever is installed on top of the system image — so
        // the inventory exists with no rule at all.
        val rules = store.policy.apps.associateBy { it.packageName }
        val packages = (rules.keys + grants.userInstalled()).distinct().filter { it != context.packageName }
            .take(MAX_APPS)
        val canSensors = grants.canGrantSensors()
        return PermissionReport(
            policy = grants.defaultPolicy(),
            deviceOwner = grants.isOwner(),
            sdkInt = Build.VERSION.SDK_INT,
            canGrantSensors = canSensors,
            lastOutcome = store.outcome,
            apps = packages.map { appState(rules[it] ?: ConfigAppPermissions(it), canSensors) },
            at = System.currentTimeMillis(),
        )
    }

    private fun appState(app: ConfigAppPermissions, canSensors: Boolean?): AppPermissionState {
        val pkg = app.packageName
        val info = grants.packageInfo(pkg)
        return if (info == null) AppPermissionState(pkg, installed = false) else AppPermissionState(
            packageName = pkg,
            installed = true,
            label = grants.label(info),
            targetSdk = info.applicationInfo?.targetSdkVersion,
            permissions = grants.runtimePermissions(info).map { name ->
                val wanted = PermissionPlanner.ruleFor(app, name)
                val blocked = wanted == PermissionRule.GRANT && canSensors == false &&
                    name in PermissionCatalog.SENSOR_PERMISSIONS
                PermissionState(
                    group = PermissionCatalog.groupOf(name),
                    name = name,
                    granted = grants.isGranted(pkg, name),
                    fixed = grants.fixedState(pkg, name),
                    wanted = wanted,
                    note = if (blocked) NOTE_SENSORS_BLOCKED else null,
                )
            },
            special = special.states(info, app.special),
        )
    }

    /**
     * The report for this check-in, or null when the server already has it: sent when its content changed (a rule was
     * enforced, the user answered a dialog or turned a special access on) and at least every [REPORT_EVERY_MS]. Reading
     * the state costs a few system calls per app, so between enforcements it is read at most every [RECHECK_EVERY_MS].
     */
    fun reportIfDue(): PermissionReport? {
        val now = System.currentTimeMillis()
        if (!dirty && now - computedAt < RECHECK_EVERY_MS) return null
        val report = report()
        dirty = false
        computedAt = now
        val signature = json.encodeToString(PermissionReport.serializer(), report.copy(at = 0)).hashCode().toString()
        val due = signature != store.sentSignature || now - store.sentAt > REPORT_EVERY_MS
        if (due) pendingSignature = signature
        return report.takeIf { due }
    }

    /** The check-in that carried the report reached the server. */
    fun delivered() {
        pendingSignature?.let { store.markSent(it, System.currentTimeMillis()) }
        pendingSignature = null
    }

    /** Forget what was sent, so the next check-in carries a fresh report. */
    fun invalidateReport() {
        store.forgetSent()
        dirty = true
    }

    private companion object {
        const val TAG = "Permissions"
        const val MAX_APPS = 150
        const val MAX_NOTED = 8
        const val REPORT_EVERY_MS = 6 * 60 * 60_000L
        const val RECHECK_EVERY_MS = 5 * 60_000L
        const val NOTE_SENSORS_BLOCKED = "sensorsBlocked"
        const val SENSORS_BLOCKED =
            "este equipo no deja al administrador conceder permisos de sensores (ubicación, cámara, micrófono)"
    }
}
