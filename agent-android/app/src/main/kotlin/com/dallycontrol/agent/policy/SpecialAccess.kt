package com.dallycontrol.agent.policy

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.dallycontrol.agent.kiosk.TimedAllow
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigAppPermissions
import com.dallycontrol.proto.PermissionCatalog
import com.dallycontrol.proto.SpecialAccessState

/**
 * Special accesses are NOT runtime permissions: `DevicePolicyManager.setPermissionGrantState` does not cover them and
 * Android gives a Device Owner no public API to turn them on. What exists is done here ([manage]); the rest is read
 * ([states]) so the console can say "needs a tap", and [screen] is the exact Android screen where the person turns it
 * on.
 *
 * | access              | what a Device Owner can do with public API                                              |
 * |---------------------|-----------------------------------------------------------------------------------------|
 * | battery             | `setUserControlDisabledPackages` (API 30): no force-stop, exempt from app standby. The  |
 * |                     | Doze allow-list has no admin API (`setApplicationExemptions` is a hidden system API).   |
 * | unknown sources     | `setSecureSetting(INSTALL_NON_MARKET_APPS)` up to Android 7; a per-app app-op since 8.  |
 * | accessibility       | `setPermittedAccessibilityServices` only restricts: the app is added to a list in force.|
 * | all files, overlay, | app-ops / settings with no admin API at all.                                            |
 * | write settings,     |                                                                                         |
 * | unrestricted data,  |                                                                                         |
 * | notification access |                                                                                         |
 */
@Suppress("TooManyFunctions") // one small function per special access
internal class SpecialAccess(
    private val context: Context,
    private val handle: DpmHandle,
    private val store: PermissionStore,
) {
    private val pm: PackageManager get() = context.packageManager

    /** Does, for the accesses [app] should have, what Android lets a Device Owner do on its own. */
    fun manage(app: ConfigAppPermissions) {
        val pkg = app.packageName
        if (PermissionCatalog.BATTERY in app.special) protect(pkg)
        if (PermissionCatalog.UNKNOWN_SOURCES in app.special && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            handle.dpm.setSecureSetting(handle.admin, Settings.Secure.INSTALL_NON_MARKET_APPS, "1")
        }
        if (PermissionCatalog.ACCESSIBILITY in app.special) {
            val permitted = handle.dpm.getPermittedAccessibilityServices(handle.admin)
            if (permitted != null && pkg !in permitted) {
                handle.dpm.setPermittedAccessibilityServices(handle.admin, permitted + pkg)
            }
        }
    }

    private fun protect(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val current = handle.dpm.getUserControlDisabledPackages(handle.admin)
        // Only what THIS class protects is remembered (and later lifted): the agent and its remote-support app are
        // protected elsewhere and must stay so.
        if (pkg !in current) {
            handle.dpm.setUserControlDisabledPackages(handle.admin, current + pkg)
            store.protectedPackages = store.protectedPackages + pkg
        }
    }

    /** Lifts the protection this class added from apps that no longer ask for it. */
    fun releaseProtection(stillWanted: Set<String>) {
        val drop = store.protectedPackages - stillWanted
        if (drop.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val current = handle.dpm.getUserControlDisabledPackages(handle.admin)
            handle.dpm.setUserControlDisabledPackages(handle.admin, current.filterNot { it in drop })
            store.protectedPackages = store.protectedPackages - drop
        }
    }

    /** @return null when the screen opened on the phone, else why it could not. */
    fun open(info: PackageInfo, access: String): String? {
        val intent = screen(info, access) ?: return unavailable(access)
        // Some phones do not resolve the per-app screen: fall back to the general list of that access.
        val target = if (pm.resolveActivity(intent, 0) != null) intent else Intent(intent.action)
        val error = TimedAllow.open(context, target, OPEN_WINDOW_MS)
        return error?.let { "no se pudo abrir la pantalla en el teléfono ($it)" }
    }

    private fun unavailable(access: String): String = when (access) {
        PermissionCatalog.ALL_FILES -> "no hace falta antes de Android 11: lo cubre el permiso de almacenamiento"
        PermissionCatalog.UNKNOWN_SOURCES -> "en Android 7 o anterior el MDM lo activa solo"
        else -> "este Android no tiene esa pantalla"
    }

    /** The Android screen of [access] for the app, or null when this Android version has none. */
    @SuppressLint("InlinedApi") // the actions are plain strings; each is only used on the versions that have it
    private fun screen(info: PackageInfo, access: String): Intent? {
        val uri = Uri.parse("package:${info.packageName}")
        val sdk = Build.VERSION.SDK_INT
        return when (access) {
            PermissionCatalog.ALL_FILES -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
                .takeIf { sdk >= Build.VERSION_CODES.R }
            PermissionCatalog.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
            PermissionCatalog.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, uri)
            PermissionCatalog.UNKNOWN_SOURCES -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri)
                .takeIf { sdk >= Build.VERSION_CODES.O }
            PermissionCatalog.BATTERY -> batteryScreen(info, uri)
            PermissionCatalog.UNRESTRICTED_DATA -> Intent(DATA_SAVER_SCREEN, uri)
                .takeIf { sdk >= Build.VERSION_CODES.N }
            PermissionCatalog.NOTIFICATION_ACCESS -> notificationScreen(info.packageName)
            PermissionCatalog.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else -> null
        }
    }

    /** Android's one-tap dialog when the app declares the request permission; else the list of apps. */
    @SuppressLint("BatteryLife") // a managed device: the administrator asks for it, for an app of the policy
    private fun batteryScreen(info: PackageInfo, uri: Uri): Intent =
        if (REQUEST_IGNORE_BATTERY in info.requestedPermissions.orEmpty()) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }

    private fun notificationScreen(pkg: String): Intent {
        val listener = service(pkg, NOTIFICATION_LISTENER_ACTION)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && listener != null) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
        } else {
            Intent(NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    /** The real state of every special access of an installed app; [wanted] = the ones the rules ask for. */
    fun states(info: PackageInfo, wanted: List<String>): List<SpecialAccessState> {
        val app = Probe(info, wanted)
        return listOf(
            allFiles(app),
            app.appOp(PermissionCatalog.OVERLAY, SYSTEM_ALERT_WINDOW, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW),
            app.appOp(PermissionCatalog.WRITE_SETTINGS, WRITE_SETTINGS, AppOpsManager.OPSTR_WRITE_SETTINGS),
            unknownSources(app),
            battery(app),
            // Android has no API to read another app's data-saver exemption.
            app.entry(PermissionCatalog.UNRESTRICTED_DATA, SpecialAccessState.UNKNOWN),
            app.component(PermissionCatalog.NOTIFICATION_ACCESS, NOTIFICATION_LISTENER_ACTION, NOTIFICATION_LISTENERS),
            app.component(PermissionCatalog.ACCESSIBILITY, ACCESSIBILITY_ACTION, ACCESSIBILITY_SERVICES),
        )
    }

    private fun allFiles(app: Probe): SpecialAccessState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.appOp(PermissionCatalog.ALL_FILES, MANAGE_EXTERNAL_STORAGE, OP_MANAGE_EXTERNAL_STORAGE)
        } else {
            app.entry(PermissionCatalog.ALL_FILES, SpecialAccessState.NOT_DECLARED, "no aplica antes de Android 11")
        }

    private fun unknownSources(app: Probe): SpecialAccessState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.appOp(PermissionCatalog.UNKNOWN_SOURCES, REQUEST_INSTALL_PACKAGES, OP_REQUEST_INSTALL)
        } else {
            @Suppress("DEPRECATION")
            val on = Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
            app.entry(PermissionCatalog.UNKNOWN_SOURCES, allowedOrTap(on), "ajuste global del teléfono")
        }

    private fun battery(app: Probe): SpecialAccessState {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = runCatching { power?.isIgnoringBatteryOptimizations(app.pkg) }.getOrNull()
        val protectedByAdmin = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            runCatching { app.pkg in handle.dpm.getUserControlDisabledPackages(handle.admin) }.getOrDefault(false)
        val state = if (exempt == null) SpecialAccessState.UNKNOWN else allowedOrTap(exempt)
        return app.entry(PermissionCatalog.BATTERY, state, if (protectedByAdmin) PROTECTED_NOTE else null)
    }

    private fun allowedOrTap(on: Boolean): String =
        if (on) SpecialAccessState.ALLOWED else SpecialAccessState.NEEDS_USER

    private fun service(pkg: String, action: String): ComponentName? = runCatching {
        pm.queryIntentServices(Intent(action).setPackage(pkg), 0).firstOrNull()?.serviceInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /** One app being read: its package, uid, declared permissions and the accesses its rules ask for. */
    private inner class Probe(info: PackageInfo, private val wanted: List<String>) {
        val pkg: String = info.packageName
        private val uid: Int? = info.applicationInfo?.uid
        private val declared: Set<String> = info.requestedPermissions.orEmpty().toSet()

        fun entry(key: String, state: String, managed: String? = null) =
            SpecialAccessState(key, state, key in wanted, managed)

        /** An access behind an app-op: not declared, allowed, or waiting for the user (unknown when unreadable). */
        fun appOp(key: String, permission: String, op: String): SpecialAccessState {
            val allowed = if (permission in declared) opAllowed(op, permission) else null
            val state = when {
                permission !in declared -> SpecialAccessState.NOT_DECLARED
                allowed == null -> SpecialAccessState.UNKNOWN
                else -> allowedOrTap(allowed)
            }
            return entry(key, state)
        }

        /** An access that is a service the user enables (notification listener, accessibility). */
        fun component(key: String, action: String, setting: String): SpecialAccessState {
            val enabled = runCatching { Settings.Secure.getString(context.contentResolver, setting) }.getOrNull()
                .orEmpty()
            val state = if (service(pkg, action) == null) SpecialAccessState.NOT_DECLARED
            else allowedOrTap(enabled.split(':').any { it.substringBefore('/') == pkg })
            return entry(key, state)
        }

        /** The app-op's answer; "default" falls back to the permission check, as Android does. Null = unreadable. */
        private fun opAllowed(op: String, permission: String): Boolean? = runCatching {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val owner = uid ?: error("no uid")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(op, owner, pkg)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(op, owner, pkg)
            }
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_DEFAULT -> pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED
                else -> false
            }
        }.getOrNull()
    }

    private companion object {
        const val OPEN_WINDOW_MS = 5 * 60_000L
        const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
        const val OP_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"
        const val OP_REQUEST_INSTALL = "android:request_install_packages"
        const val REQUEST_IGNORE_BATTERY = "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        const val NOTIFICATION_LISTENER_ACTION = "android.service.notification.NotificationListenerService"
        const val NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
        const val NOTIFICATION_LISTENERS = "enabled_notification_listeners"
        const val ACCESSIBILITY_ACTION = "android.accessibilityservice.AccessibilityService"
        const val PROTECTED_NOTE =
            "protegida por el MDM: no se puede forzar su detención y queda exenta del reposo de apps"
        const val SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
        const val WRITE_SETTINGS = "android.permission.WRITE_SETTINGS"
        const val REQUEST_INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
        const val ACCESSIBILITY_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        const val DATA_SAVER_SCREEN = "android.settings.IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS"
    }
}
