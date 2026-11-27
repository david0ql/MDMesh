package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigAppPermissions
import com.dallycontrol.proto.ConfigPermissions
import com.dallycontrol.proto.PermissionCatalog
import com.dallycontrol.proto.PermissionRule

/** Applies a [ConfigPermissions] on the device; also re-run when an app is installed and when the agent starts. */
interface PermissionEnforcer {
    /** Persist [policy] and enforce it now. @return a ConfigOutcome string. */
    fun apply(policy: ConfigPermissions): String

    /**
     * Enforce the last applied rules again. [packageName] = only that app (it was just installed), leaving the
     * default policy alone; null = everything.
     */
    fun reenforce(packageName: String? = null): String
}

/** Called by the installer the moment an app is installed, before it is launched. */
fun interface PostInstall {
    fun installed(packageName: String)

    companion object {
        val NONE: PostInstall = PostInstall { }
    }
}

/**
 * Pure planning of one app's runtime permissions: which `setPermissionGrantState` calls to make, and in which order.
 *
 *  - A group rule wins over the app's "all" preset; a permission with no rule is left to the default policy.
 *  - Background location goes LAST: Android only honours it once the app holds a foreground location permission.
 *  - On Android 12+ a Device Owner that may not grant sensor permissions skips those grants (reported, not failed);
 *    denying them is always possible.
 *  - "prompt" after a grant this agent fixed is a revoke + release (the app has to ask again); otherwise it only
 *    releases the permission, so an answer the user gave is never taken away on every re-apply.
 *  - A permission this agent fixed that no longer has a rule is released (the user manages it again).
 */
object PermissionPlanner {
    enum class Action { GRANT, DENY, RELEASE, REVOKE_AND_RELEASE }

    data class Step(val permission: String, val action: Action, val rule: String?)

    data class Plan(
        val steps: List<Step>,
        /** Sensor permissions that should be granted but this device does not let the admin grant. */
        val blockedSensors: List<String>,
    )

    /**
     * @param declared the runtime ("dangerous") permissions the installed app declares
     * @param fixedBefore what this agent fixed for the app last time: permission to rule
     */
    fun plan(
        rules: ConfigAppPermissions?,
        declared: List<String>,
        canGrantSensors: Boolean,
        fixedBefore: Map<String, String>,
    ): Plan {
        val steps = mutableListOf<Step>()
        val blocked = mutableListOf<String>()
        for (permission in declared.distinct()) {
            val rule = ruleFor(rules, permission)
            val before = fixedBefore[permission]
            when (rule) {
                PermissionRule.GRANT ->
                    if (!canGrantSensors && permission in PermissionCatalog.SENSOR_PERMISSIONS) blocked += permission
                    else steps += Step(permission, Action.GRANT, rule)
                PermissionRule.DENY -> steps += Step(permission, Action.DENY, rule)
                PermissionRule.PROMPT ->
                    steps += Step(permission, if (before == PermissionRule.GRANT) Action.REVOKE_AND_RELEASE else Action.RELEASE, rule)
                else -> if (before != null) steps += Step(permission, Action.RELEASE, null)
            }
        }
        // Foreground permissions first, background location last (stable otherwise).
        return Plan(steps.sortedBy { if (it.permission == PermissionCatalog.BACKGROUND_LOCATION) 1 else 0 }, blocked)
    }

    /** The rule in force for [permission]: its group's rule, else the "all" preset, else null. Unknown values = none. */
    fun ruleFor(rules: ConfigAppPermissions?, permission: String): String? {
        if (rules == null) return null
        val own = rules.permissions[PermissionCatalog.groupOf(permission)]
        return (own ?: rules.all)?.takeIf { it in PermissionRule.ALL }
    }
}
