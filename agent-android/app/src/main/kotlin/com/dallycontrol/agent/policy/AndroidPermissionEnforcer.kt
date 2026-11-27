package com.dallycontrol.agent.policy

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.dallycontrol.agent.kiosk.TimedAllow
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
import com.dallycontrol.proto.SpecialAccessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
 *  - Special accesses (all files, overlay, write settings, unknown sources, battery, unrestricted data, notification
 *    access, accessibility) are app-ops or settings a Device Owner has NO public API to turn on. What exists is done
 *    (see [manageSpecial]); the rest is reported as "needs a tap" and [openSpecial] shows the exact screen.
 *
 * Only what this class fixed is released when a rule goes away.
 */
class AndroidPermissionEnforcer(
    private val context: Context,
    private val handle: DpmHandle,
) : PermissionEnforcer {
    private val prefs = context.getSharedPreferences("mdm_permissions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val fixedSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dpm: DevicePolicyManager get() = handle.dpm
    private val admin: ComponentName get() = handle.admin
    private val pm: PackageManager get() = context.packageManager

    @Synchronized
    override fun apply(policy: ConfigPermissions): String {
        prefs.edit().putString(KEY_POLICY, json.encodeToString(ConfigPermissions.serializer(), policy)).apply()
        return enforce(policy, null)
    }

    @Synchronized
    override fun reenforce(packageName: String?): String = enforce(stored(), packageName)

    /** [reenforce] off the caller's thread (broadcast receivers): `setPermissionGrantState` may block. */
    fun reenforceAsync(packageName: String?) {
        scope.launch { runCatching { reenforce(packageName) }.onFailure { Log.w(TAG, "reenforce", it) } }
    }

    private fun stored(): ConfigPermissions =
        prefs.getString(KEY_POLICY, null)?.let { runCatching { json.decodeFromString(ConfigPermissions.serializer(), it) }.getOrNull() }
            ?: ConfigPermissions()

    private fun isOwner(): Boolean = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** Android 12+: may this admin grant sensor permissions? Older versions have no such limit (null). */
    fun canGrantSensors(): Boolean? =
        if (Build.VERSION.SDK_INT >= 31) runCatching { dpm.canAdminGrantSensorsPermissions() }.getOrDefault(false) else null

    private fun enforce(policy: ConfigPermissions, onlyPackage: String?): String {
        if (Build.VERSION.SDK_INT < 23 || !isOwner()) return remember(ConfigOutcome.UNSUPPORTED, onlyPackage)
        val notes = mutableListOf<String>()
        if (onlyPackage == null) {
            val wanted = when (policy.defaultPolicy) {
                PermissionRule.DENY -> DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
                PermissionRule.PROMPT -> DevicePolicyManager.PERMISSION_POLICY_PROMPT
                else -> DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
            }
            val set = runCatching {
                if (dpm.getPermissionPolicy(admin) != wanted) dpm.setPermissionPolicy(admin, wanted)
            }
            if (set.isFailure) {
                return remember(ConfigOutcome.failed("setPermissionPolicy: ${set.exceptionOrNull()?.message}"), null)
            }
        }
        val canSensors = canGrantSensors() ?: true
        val fixed = loadFixed().toMutableMap()
        val blockedSensorApps = mutableListOf<String>()
        val notFixed = mutableListOf<String>()
        for (app in policy.apps) {
            val pkg = app.packageName
            if (pkg == context.packageName || (onlyPackage != null && pkg != onlyPackage)) continue
            val info = packageInfo(pkg)
            if (info == null) {
                fixed.remove(pkg) // not installed: a later install starts clean
                continue
            }
            val plan = PermissionPlanner.plan(app, runtimePermissions(info), canSensors, fixed[pkg].orEmpty())
            val now = mutableMapOf<String, String>()
            for (step in plan.steps) {
                if (run(pkg, step)) step.rule?.let { now[step.permission] = it }
                else notFixed += "$pkg:${step.permission.substringAfterLast('.')}"
            }
            if (now.isEmpty()) fixed.remove(pkg) else fixed[pkg] = now
            if (plan.blockedSensors.isNotEmpty()) blockedSensorApps += pkg
            runCatching { manageSpecial(app) }.onFailure { Log.w(TAG, "special access of $pkg", it) }
        }
        if (onlyPackage == null) {
            // Apps that left the policy: release what was fixed for them.
            val managed = policy.apps.map { it.packageName }.toSet()
            for (pkg in fixed.keys - managed) {
                fixed.remove(pkg)?.keys?.forEach { permission ->
                    runCatching { dpm.setPermissionGrantState(admin, pkg, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) }
                }
            }
            releaseProtection(policy.apps.filter { PermissionCatalog.BATTERY in it.special }.map { it.packageName }.toSet())
        }
        saveFixed(fixed)
        if (blockedSensorApps.isNotEmpty()) {
            notes += "este equipo no deja al administrador conceder permisos de sensores (ubicación, cámara, micrófono): ${blockedSensorApps.joinToString()}"
        }
        if (notFixed.isNotEmpty()) notes += "Android no dejó fijar: ${notFixed.take(MAX_NOTED).joinToString()}"
        val outcome = if (notes.isEmpty()) ConfigOutcome.APPLIED else "${ConfigOutcome.APPLIED} (${notes.joinToString("; ")})"
        return remember(outcome, onlyPackage)
    }

    /** Keep the outcome of the last FULL enforcement for the report (a single-app pass does not replace it). */
    private fun remember(outcome: String, onlyPackage: String?): String {
        if (onlyPackage == null) prefs.edit().putString(KEY_OUTCOME, outcome).apply()
        return outcome
    }

    private fun run(pkg: String, step: PermissionPlanner.Step): Boolean = runCatching {
        when (step.action) {
            PermissionPlanner.Action.GRANT -> state(pkg, step.permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            PermissionPlanner.Action.DENY -> state(pkg, step.permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            PermissionPlanner.Action.RELEASE -> state(pkg, step.permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
            // "Ask again": a grant of ours is taken back first, then handed to the user.
            PermissionPlanner.Action.REVOKE_AND_RELEASE ->
                state(pkg, step.permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED) &&
                    state(pkg, step.permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT)
        }
    }.getOrElse {
        Log.w(TAG, "$pkg ${step.permission} ${step.action}: ${it.message}")
        false
    }

    /** Idempotent: nothing is written when the permission is already fixed that way. */
    private fun state(pkg: String, permission: String, wanted: Int): Boolean =
        dpm.getPermissionGrantState(admin, pkg, permission) == wanted || dpm.setPermissionGrantState(admin, pkg, permission, wanted)

    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String): PackageInfo? =
        runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()

    /** The runtime ("dangerous") permissions the app declares that this Android version knows. */
    private fun runtimePermissions(info: PackageInfo): List<String> =
        info.requestedPermissions.orEmpty().filter { name ->
            val p = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull() ?: return@filter false
            val level = if (Build.VERSION.SDK_INT >= 28) p.protection else {
                @Suppress("DEPRECATION")
                p.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
            }
            level == PermissionInfo.PROTECTION_DANGEROUS
        }

    // --- Special accesses -------------------------------------------------------------------------------------

    /**
     * What a Device Owner CAN do for a special access with public API:
     *  - battery (Android 11+): `setUserControlDisabledPackages` — the app cannot be force-stopped by the user and
     *    Android exempts it from app-standby restrictions. The Doze allow-list itself ("sin restricciones de
     *    batería") has no public admin API (`setApplicationExemptions` is a hidden system API): one tap.
     *  - unknown sources (Android 7 and older): `setSecureSetting(INSTALL_NON_MARKET_APPS)`. From Android 8 it is a
     *    per-app app-op (`REQUEST_INSTALL_PACKAGES`) only the user can allow.
     *  - accessibility: `setPermittedAccessibilityServices` only restricts; when a restricting list is in force the
     *    app is added to it so the user is able to turn its service on. Turning it on is the user's.
     *  - all files, overlay, write settings, unrestricted data, notification access: app-ops / settings with no admin
     *    API at all — reported, and opened on request.
     */
    private fun manageSpecial(app: ConfigAppPermissions) {
        val pkg = app.packageName
        if (PermissionCatalog.BATTERY in app.special && Build.VERSION.SDK_INT >= 30) {
            val current = dpm.getUserControlDisabledPackages(admin)
            if (pkg !in current) {
                dpm.setUserControlDisabledPackages(admin, current + pkg)
                prefs.edit().putStringSet(KEY_PROTECTED, prefs.getStringSet(KEY_PROTECTED, emptySet()).orEmpty() + pkg).apply()
            }
        }
        if (PermissionCatalog.UNKNOWN_SOURCES in app.special && Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            dpm.setSecureSetting(admin, Settings.Secure.INSTALL_NON_MARKET_APPS, "1")
        }
        if (PermissionCatalog.ACCESSIBILITY in app.special) {
            val permitted = dpm.getPermittedAccessibilityServices(admin)
            if (permitted != null && pkg !in permitted) dpm.setPermittedAccessibilityServices(admin, permitted + pkg)
        }
    }

    /** Lift the protection this class added from apps that no longer ask for it. */
    private fun releaseProtection(stillWanted: Set<String>) {
        if (Build.VERSION.SDK_INT < 30) return
        val mine = prefs.getStringSet(KEY_PROTECTED, emptySet()).orEmpty()
        val drop = mine - stillWanted
        if (drop.isEmpty()) return
        runCatching {
            dpm.setUserControlDisabledPackages(admin, dpm.getUserControlDisabledPackages(admin).filterNot { it in drop })
            prefs.edit().putStringSet(KEY_PROTECTED, mine - drop).apply()
        }
    }

    /** @return null when the screen opened on the phone, else why it could not. */
    fun openSpecial(pkg: String, access: String): String? {
        val info = packageInfo(pkg) ?: return "la app no está instalada"
        val uri = Uri.parse("package:$pkg")
        val intent = when (access) {
            PermissionCatalog.ALL_FILES ->
                if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                else return "no hace falta antes de Android 11: lo cubre el permiso de almacenamiento"
            PermissionCatalog.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
            PermissionCatalog.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, uri)
            PermissionCatalog.UNKNOWN_SOURCES ->
                if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                else return "en Android 7 o anterior el MDM lo activa solo"
            PermissionCatalog.BATTERY ->
                if (REQUEST_IGNORE_BATTERY in info.requestedPermissions.orEmpty()) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
                else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            PermissionCatalog.UNRESTRICTED_DATA ->
                if (Build.VERSION.SDK_INT >= 24) Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, uri)
                else return "no existe antes de Android 7"
            PermissionCatalog.NOTIFICATION_ACCESS -> {
                val listener = service(pkg, NOTIFICATION_LISTENER_ACTION)
                if (Build.VERSION.SDK_INT >= 30 && listener != null) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
                } else Intent(NOTIFICATION_LISTENER_SETTINGS)
            }
            PermissionCatalog.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> return "acceso desconocido: $access"
        }
        // Some phones do not resolve the per-app screen: fall back to the general list of that access.
        val target = if (pm.resolveActivity(intent, 0) != null) intent else Intent(intent.action)
        return TimedAllow.open(context, target, OPEN_WINDOW_MS)?.let { "no se pudo abrir la pantalla en el teléfono ($it)" }
    }

    private fun specialStates(info: PackageInfo, wanted: List<String>): List<SpecialAccessState> {
        val pkg = info.packageName
        val uid = info.applicationInfo?.uid ?: return emptyList()
        val declared = info.requestedPermissions.orEmpty().toSet()
        fun entry(key: String, state: String, managed: String? = null) = SpecialAccessState(key, state, key in wanted, managed)
        fun appOp(key: String, permission: String, op: String): SpecialAccessState {
            if (permission !in declared) return entry(key, SpecialAccessState.NOT_DECLARED)
            val allowed = opAllowed(op, uid, pkg, permission) ?: return entry(key, SpecialAccessState.UNKNOWN)
            return entry(key, if (allowed) SpecialAccessState.ALLOWED else SpecialAccessState.NEEDS_USER)
        }
        val out = mutableListOf<SpecialAccessState>()
        out += if (Build.VERSION.SDK_INT >= 30) appOp(PermissionCatalog.ALL_FILES, MANAGE_EXTERNAL_STORAGE, "android:manage_external_storage")
        else entry(PermissionCatalog.ALL_FILES, SpecialAccessState.NOT_DECLARED, "no aplica antes de Android 11")
        out += appOp(PermissionCatalog.OVERLAY, "android.permission.SYSTEM_ALERT_WINDOW", AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)
        out += appOp(PermissionCatalog.WRITE_SETTINGS, "android.permission.WRITE_SETTINGS", AppOpsManager.OPSTR_WRITE_SETTINGS)
        out += if (Build.VERSION.SDK_INT >= 26) {
            appOp(PermissionCatalog.UNKNOWN_SOURCES, "android.permission.REQUEST_INSTALL_PACKAGES", "android:request_install_packages")
        } else {
            @Suppress("DEPRECATION")
            val on = Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
            entry(PermissionCatalog.UNKNOWN_SOURCES, if (on) SpecialAccessState.ALLOWED else SpecialAccessState.NEEDS_USER, "ajuste global del teléfono")
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = runCatching { power?.isIgnoringBatteryOptimizations(pkg) }.getOrNull()
        val protectedByAdmin = Build.VERSION.SDK_INT >= 30 &&
            runCatching { pkg in dpm.getUserControlDisabledPackages(admin) }.getOrDefault(false)
        out += entry(
            PermissionCatalog.BATTERY,
            when (exempt) { true -> SpecialAccessState.ALLOWED; false -> SpecialAccessState.NEEDS_USER; null -> SpecialAccessState.UNKNOWN },
            if (protectedByAdmin) "protegida por el MDM: no se puede forzar su detención y queda exenta del reposo de apps" else null,
        )
        // Android has no API to read another app's data-saver exemption.
        out += entry(PermissionCatalog.UNRESTRICTED_DATA, SpecialAccessState.UNKNOWN)
        out += componentAccess(PermissionCatalog.NOTIFICATION_ACCESS, pkg, NOTIFICATION_LISTENER_ACTION, "enabled_notification_listeners", wanted)
        out += componentAccess(PermissionCatalog.ACCESSIBILITY, pkg, ACCESSIBILITY_ACTION, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, wanted)
        return out
    }

    private fun componentAccess(key: String, pkg: String, action: String, setting: String, wanted: List<String>): SpecialAccessState {
        if (service(pkg, action) == null) return SpecialAccessState(key, SpecialAccessState.NOT_DECLARED, key in wanted)
        val enabled = runCatching { Settings.Secure.getString(context.contentResolver, setting) }.getOrNull()
            ?: return SpecialAccessState(key, SpecialAccessState.NEEDS_USER, key in wanted)
        val on = enabled.split(':').any { it.substringBefore('/') == pkg }
        return SpecialAccessState(key, if (on) SpecialAccessState.ALLOWED else SpecialAccessState.NEEDS_USER, key in wanted)
    }

    private fun service(pkg: String, action: String): ComponentName? = runCatching {
        @Suppress("DEPRECATION")
        pm.queryIntentServices(Intent(action).setPackage(pkg), 0).firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /** The app-op's answer; "default" falls back to the permission check, as Android itself does. Null = unreadable. */
    private fun opAllowed(op: String, uid: Int, pkg: String, permission: String): Boolean? = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(op, uid, pkg) else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(op, uid, pkg)
        }
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT -> pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }.getOrNull()

    // --- Report -----------------------------------------------------------------------------------------------

    /** The real state right now: effective policy, sensor-grant ability and every managed app's permissions. */
    @Synchronized
    fun report(): PermissionReport {
        val owner = isOwner()
        val policy = stored()
        val effective = if (!owner || Build.VERSION.SDK_INT < 23) "unknown" else when (runCatching { dpm.getPermissionPolicy(admin) }.getOrNull()) {
            DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT -> PermissionRule.GRANT
            DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY -> PermissionRule.DENY
            DevicePolicyManager.PERMISSION_POLICY_PROMPT -> PermissionRule.PROMPT
            else -> "unknown"
        }
        val canSensors = canGrantSensors()
        // The managed apps: every package a rule names, then whatever is installed on top of the system image (what
        // the console installed, or the user where the policy lets them) — so the inventory exists with no rule at all.
        val rules = policy.apps.associateBy { it.packageName }
        val packages = (rules.keys + userInstalled()).distinct().filter { it != context.packageName }.take(MAX_APPS)
        val apps = packages.map { pkg ->
            val app = rules[pkg] ?: ConfigAppPermissions(pkg)
            val info = packageInfo(app.packageName) ?: return@map AppPermissionState(app.packageName, installed = false)
            AppPermissionState(
                packageName = app.packageName,
                installed = true,
                label = runCatching { info.applicationInfo?.let { pm.getApplicationLabel(it).toString() } }.getOrNull(),
                targetSdk = info.applicationInfo?.targetSdkVersion,
                permissions = runtimePermissions(info).map { name ->
                    val wanted = PermissionPlanner.ruleFor(app, name)
                    val fixed = if (!owner) PermissionState.FIXED_NONE else when (runCatching { dpm.getPermissionGrantState(admin, app.packageName, name) }.getOrNull()) {
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED -> PermissionState.FIXED_GRANTED
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED -> PermissionState.FIXED_DENIED
                        else -> PermissionState.FIXED_NONE
                    }
                    PermissionState(
                        group = PermissionCatalog.groupOf(name),
                        name = name,
                        granted = pm.checkPermission(name, app.packageName) == PackageManager.PERMISSION_GRANTED,
                        fixed = fixed,
                        wanted = wanted,
                        note = if (wanted == PermissionRule.GRANT && canSensors == false && name in PermissionCatalog.SENSOR_PERMISSIONS) "sensorsBlocked" else null,
                    )
                },
                special = specialStates(info, app.special),
            )
        }
        return PermissionReport(
            policy = effective,
            deviceOwner = owner,
            sdkInt = Build.VERSION.SDK_INT,
            canGrantSensors = canSensors,
            lastOutcome = prefs.getString(KEY_OUTCOME, null),
            apps = apps,
            at = System.currentTimeMillis(),
        )
    }

    @Suppress("DEPRECATION")
    private fun userInstalled(): List<String> = runCatching {
        pm.getInstalledApplications(0).filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }.map { it.packageName }.sorted()
    }.getOrDefault(emptyList())

    /**
     * The report for this check-in, or null when the server already has it: sent when its content changed, after an
     * enforcement or a special-access screen was opened, and at least every [REPORT_EVERY_MS].
     */
    fun reportIfDue(): PermissionReport? {
        val r = report()
        val signature = json.encodeToString(PermissionReport.serializer(), r.copy(at = 0)).hashCode().toString()
        val due = signature != prefs.getString(KEY_SENT_SIGNATURE, null) ||
            System.currentTimeMillis() - prefs.getLong(KEY_SENT_AT, 0) > REPORT_EVERY_MS
        if (!due) return null
        pending = signature
        return r
    }

    /** The check-in that carried the report reached the server. */
    fun delivered() {
        val signature = pending ?: return
        pending = null
        prefs.edit().putString(KEY_SENT_SIGNATURE, signature).putLong(KEY_SENT_AT, System.currentTimeMillis()).apply()
    }

    /** Forget what was sent, so the next check-in carries a fresh report. */
    fun invalidateReport() {
        prefs.edit().remove(KEY_SENT_SIGNATURE).apply()
    }

    @Volatile private var pending: String? = null

    private fun loadFixed(): Map<String, Map<String, String>> =
        prefs.getString(KEY_FIXED, null)?.let { runCatching { json.decodeFromString(fixedSerializer, it) }.getOrNull() }.orEmpty()

    private fun saveFixed(fixed: Map<String, Map<String, String>>) {
        prefs.edit().putString(KEY_FIXED, json.encodeToString(fixedSerializer, fixed)).apply()
    }

    private companion object {
        const val TAG = "Permissions"
        const val KEY_POLICY = "policy"
        const val KEY_FIXED = "fixed"
        const val KEY_OUTCOME = "outcome"
        const val KEY_PROTECTED = "protected"
        const val KEY_SENT_SIGNATURE = "sent_signature"
        const val KEY_SENT_AT = "sent_at"
        const val MAX_APPS = 150
        const val MAX_NOTED = 8
        const val REPORT_EVERY_MS = 6 * 60 * 60_000L
        const val OPEN_WINDOW_MS = 5 * 60_000L
        const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
        const val REQUEST_IGNORE_BATTERY = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        const val NOTIFICATION_LISTENER_ACTION = "android.service.notification.NotificationListenerService"
        const val NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
        const val ACCESSIBILITY_ACTION = "android.accessibilityservice.AccessibilityService"
    }
}
