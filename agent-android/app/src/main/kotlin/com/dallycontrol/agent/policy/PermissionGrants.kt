package com.dallycontrol.agent.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.Log
import com.dallycontrol.core.config.PermissionPlanner
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.PermissionRule
import com.dallycontrol.proto.PermissionState

/**
 * The Device-Owner calls behind app permissions, one per method and none of them throwing:
 * `setPermissionPolicy` / `getPermissionPolicy`, `setPermissionGrantState` / `getPermissionGrantState` and
 * `canAdminGrantSensorsPermissions`, plus what the package manager says an app declares and holds.
 */
@Suppress("TooManyFunctions") // one Device-Owner or package-manager call per method
internal class PermissionGrants(private val context: Context, private val handle: DpmHandle) {
    private val dpm: DevicePolicyManager get() = handle.dpm
    private val pm: PackageManager get() = context.packageManager

    /** Fixing permissions needs Device Owner (runtime permissions exist on every Android this agent runs on). */
    fun usable(): Boolean = isOwner()

    fun isOwner(): Boolean = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)

    /** Android 12+: may this admin grant sensor permissions? Older versions have no such limit (null). */
    fun canGrantSensors(): Boolean? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) null
        else runCatching { dpm.canAdminGrantSensorsPermissions() }.getOrDefault(false)

    /** Sets the default answer to runtime-permission requests. @return null when in force, else the error. */
    fun setDefaultPolicy(rule: String): String? = runCatching {
        val wanted = when (rule) {
            PermissionRule.DENY -> DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY
            PermissionRule.PROMPT -> DevicePolicyManager.PERMISSION_POLICY_PROMPT
            else -> DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT
        }
        if (dpm.getPermissionPolicy(handle.admin) != wanted) dpm.setPermissionPolicy(handle.admin, wanted)
        null
    }.getOrElse { "setPermissionPolicy: ${it.message}" }

    /** The default policy really in force, as a [PermissionRule] value (`unknown` when not Device Owner). */
    fun defaultPolicy(): String {
        val policy = if (usable()) runCatching { dpm.getPermissionPolicy(handle.admin) }.getOrNull() else null
        return when (policy) {
            DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT -> PermissionRule.GRANT
            DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY -> PermissionRule.DENY
            DevicePolicyManager.PERMISSION_POLICY_PROMPT -> PermissionRule.PROMPT
            else -> UNKNOWN
        }
    }

    /** Runs one planned step. @return false when Android refused it. */
    fun run(pkg: String, step: PermissionPlanner.Step): Boolean = runCatching {
        when (step.action) {
            PermissionPlanner.Action.GRANT -> set(pkg, step.permission, GRANTED)
            PermissionPlanner.Action.DENY -> set(pkg, step.permission, DENIED)
            PermissionPlanner.Action.RELEASE -> release(pkg, step.permission)
            // "Ask again": a grant of ours is taken back first, then handed to the user.
            PermissionPlanner.Action.REVOKE_AND_RELEASE ->
                set(pkg, step.permission, DENIED) && release(pkg, step.permission)
        }
    }.getOrElse {
        Log.w(TAG, "$pkg ${step.permission} ${step.action}: ${it.message}")
        false
    }

    /** Hands the permission back to the user (whatever it is now stays, but is no longer fixed). */
    fun release(pkg: String, permission: String): Boolean =
        runCatching { set(pkg, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) }.getOrDefault(false)

    /** Idempotent: nothing is written when the permission is already fixed that way. */
    private fun set(pkg: String, permission: String, wanted: Int): Boolean =
        dpm.getPermissionGrantState(handle.admin, pkg, permission) == wanted ||
            dpm.setPermissionGrantState(handle.admin, pkg, permission, wanted)

    /** [PermissionState] `fixed` value for a permission of an app. */
    fun fixedState(pkg: String, permission: String): String {
        val state = if (!isOwner()) null
        else runCatching { dpm.getPermissionGrantState(handle.admin, pkg, permission) }.getOrNull()
        return when (state) {
            GRANTED -> PermissionState.FIXED_GRANTED
            DENIED -> PermissionState.FIXED_DENIED
            else -> PermissionState.FIXED_NONE
        }
    }

    fun isGranted(pkg: String, permission: String): Boolean =
        pm.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED

    fun packageInfo(pkg: String): PackageInfo? =
        runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }.getOrNull()

    /** The runtime ("dangerous") permissions the app declares that this Android version knows. */
    fun runtimePermissions(info: PackageInfo): List<String> = info.requestedPermissions.orEmpty().filter(::isRuntime)

    private fun isRuntime(name: String): Boolean {
        val permission = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
        val level = when {
            permission == null -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> permission.protection
            else -> {
                @Suppress("DEPRECATION")
                permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
            }
        }
        return level == PermissionInfo.PROTECTION_DANGEROUS
    }

    /** Apps installed on top of the system image: what the console installed, or the user where allowed. */
    fun userInstalled(): List<String> = runCatching {
        pm.getInstalledApplications(0).filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }.sorted()
    }.getOrDefault(emptyList())

    fun label(info: PackageInfo): String? =
        runCatching { info.applicationInfo?.let { pm.getApplicationLabel(it).toString() } }.getOrNull()

    private companion object {
        const val TAG = "Permissions"
        const val UNKNOWN = "unknown"
        const val GRANTED = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        const val DENIED = DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
    }
}
