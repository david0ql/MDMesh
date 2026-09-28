package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigAppPolicy

/**
 * Which user-installed apps the app policy suspends (Android-free, unit-tested). In `allowlist` mode everything
 * installed by the user is suspended unless it is allowed by package, resolved from an allowed function, or
 * protected (the agent itself, remote support). `open` suspends nothing (and lifts earlier suspensions).
 */
object AppPolicyPlanner {
    data class Plan(val suspend: Set<String>, val unsuspend: Set<String>)

    fun plan(
        policy: ConfigAppPolicy,
        userInstalled: Collection<String>,
        rolePackages: Collection<String>,
        protected: Collection<String>,
        currentlySuspended: Collection<String>,
    ): Plan {
        val blocked = if (policy.mode != "allowlist") {
            emptySet()
        } else {
            val ok = (policy.allowed + rolePackages + protected).toSet()
            userInstalled.filterNot { it in ok }.toSet()
        }
        return Plan(
            suspend = blocked - currentlySuspended.toSet(),
            unsuspend = currentlySuspended.toSet() - blocked,
        )
    }
}

/** Applies a [ConfigAppPolicy] on the device; also re-run when an app is installed. */
interface AppPolicyEnforcer {
    /** Persist [policy] and enforce it now. @return a ConfigOutcome string. */
    fun apply(policy: ConfigAppPolicy): String

    /** Enforce the last applied policy again (an app was installed, the agent restarted). */
    fun reenforce()
}
