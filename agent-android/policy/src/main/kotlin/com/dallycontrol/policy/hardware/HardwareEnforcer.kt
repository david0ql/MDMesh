package com.dallycontrol.policy.hardware

import com.dallycontrol.policy.hardware.HardwareCatalog.ALLOW
import com.dallycontrol.policy.hardware.HardwareCatalog.BLOCK
import com.dallycontrol.policy.hardware.HardwareCatalog.TOKEN_AUTO_TIME
import com.dallycontrol.policy.hardware.HardwareCatalog.TOKEN_CAMERA
import com.dallycontrol.policy.hardware.HardwareCatalog.TOKEN_USB_DATA

/** The device side of the hardware rules. Implemented over `DevicePolicyManager`; faked in tests. */
@Suppress("TooManyFunctions") // one small function per device switch
interface HardwareBackend {
    val sdk: Int
    fun isDeviceOwner(): Boolean

    /** Add / clear a user restriction. Throws when the platform refuses. */
    fun setRestriction(key: String, on: Boolean)

    /** Whether the restriction is in force for this admin (null = the platform cannot tell). */
    fun hasRestriction(key: String): Boolean?
    fun setCameraDisabled(disabled: Boolean)
    fun isCameraDisabled(): Boolean

    /** Android 12+: whether this device's USB HAL can switch data signaling off. */
    fun canDisableUsbData(): Boolean
    fun setUsbDataEnabled(enabled: Boolean)
    fun isUsbDataEnabled(): Boolean
    fun setAutoTimeRequired(required: Boolean)
    fun isAutoTimeRequired(): Boolean

    /** The USB debugging switch. Blocking debugging turns it off; Android never turns it back on. */
    fun isAdbEnabled(): Boolean
    fun enableAdb()
}

/** What this section switched on, so that only its own work is undone when a rule goes away. */
interface OwnedTokens {
    fun load(): Set<String>
    fun save(tokens: Set<String>)
}

/**
 * Applies the `hardware` section of a `config.apply` document and reports, per rule, what really happened:
 * `applied` (read back from the device), `unsupported: <why>` (final) or `failed: <why>`.
 *
 *  - `block` switches the rule on; `allow` switches it off even if something else had set it;
 *  - a rule that is no longer in the document is switched off only if this section had switched it on;
 *  - a restriction another section of the document keeps on (`held`) is never cleared here;
 *  - lifting the debugging block gives USB debugging back if it was on before, so a technician's cable works again
 *    without touching the phone.
 *
 * Outcome keys: `hardware.<rule>` and `restrictions.<no_xxx>`.
 */
class HardwareEnforcer(private val backend: HardwareBackend, private val owned: OwnedTokens) {

    private class Wanted(val tokens: List<String>, val blocked: Boolean)

    /** What a document asks of this device: tokens to switch on / off, and the outcomes already known. */
    private class Plan {
        val outcomes = linkedMapOf<String, String>()
        val block = linkedSetOf<String>()
        val allow = linkedSetOf<String>()
        val pending = linkedMapOf<String, Wanted>()

        fun want(name: String, tokens: List<String>, blocked: Boolean) {
            if (blocked) block += tokens else allow += tokens
            pending[name] = Wanted(tokens, blocked)
            outcomes[name] = "" // keeps the document's order; filled in once applied
        }
    }

    fun apply(rules: Map<String, String>, restrictions: List<String>, held: Set<String>): Map<String, String> {
        if (!backend.isDeviceOwner()) {
            return (rules.keys.map { "hardware.$it" } + restrictions.map { "restrictions.$it" })
                .associateWith { unsupported(WHY_NOT_OWNER) }
        }
        val plan = plan(rules, restrictions)
        val errors = execute(plan, held)
        for ((name, w) in plan.pending) {
            val errs = w.tokens.mapNotNull { errors[it] }
            plan.outcomes[name] = errs.firstOrNull { it.startsWith("failed") } ?: errs.firstOrNull()
                ?: if (w.tokens.all { isOn(it) != !w.blocked }) APPLIED else "failed: $WHY_NOT_EFFECTIVE"
        }
        return plan.outcomes
    }

    private fun plan(rules: Map<String, String>, restrictions: List<String>): Plan {
        val sdk = backend.sdk
        val plan = Plan()
        for ((key, value) in rules) {
            val name = "hardware.$key"
            val rule = HardwareCatalog.rule(key)
            val blocked = value == BLOCK
            when {
                rule == null || (value != BLOCK && value != ALLOW) -> plan.outcomes[name] = unsupported(WHY_UNKNOWN)
                // Nothing to lift on an Android that never had the rule: "allow" already holds.
                sdk < rule.minSdk -> plan.outcomes[name] = if (blocked) unsupported("sdk-${rule.minSdk}") else APPLIED
                else -> plan.want(name, HardwareCatalog.tokensFor(rule, sdk), blocked)
            }
        }
        for (key in restrictions) {
            val name = "restrictions.$key"
            val min = HardwareCatalog.FREE_RESTRICTIONS[key]
            when {
                min == null -> plan.outcomes[name] = unsupported(WHY_UNKNOWN)
                sdk < min -> plan.outcomes[name] = unsupported("sdk-$min")
                else -> plan.want(name, listOf(key), true)
            }
        }
        plan.allow -= plan.block
        return plan
    }

    /** Switches the tokens and keeps the owned set; @return token → why it could not be switched. */
    private fun execute(plan: Plan, held: Set<String>): Map<String, String> {
        val mine = owned.load().toMutableSet()
        val errors = hashMapOf<String, String>()
        for (t in plan.block) {
            if (t == DEBUGGING && t !in mine) rememberAdb(mine)
            val e = switch(t, true)
            if (e == null) mine += t else errors[t] = e
        }
        for (t in (mine + plan.allow) - plan.block - ADB_WAS_ON) {
            if (t in held) {
                // It stays on for the other section, and it is no longer this one's to undo.
                mine -= t
                errors[t] = unsupported(WHY_HELD)
                continue
            }
            val e = switch(t, false)
            if (e == null) {
                mine -= t
                if (t == DEBUGGING && mine.remove(ADB_WAS_ON)) runCatching { backend.enableAdb() }
            } else if (t in plan.allow) {
                errors[t] = e
            }
        }
        owned.save(mine)
        return errors
    }

    /** Blocking debugging switches USB debugging off for good: remember it was on, to give it back on revert. */
    private fun rememberAdb(mine: MutableSet<String>) {
        if (runCatching { backend.isAdbEnabled() }.getOrDefault(false)) mine += ADB_WAS_ON else mine -= ADB_WAS_ON
    }

    /** @return null when done, or the outcome that explains why not. */
    private fun switch(token: String, on: Boolean): String? {
        // A device whose USB HAL cannot cut data never had it cut: blocking is impossible, allowing is already true.
        if (token == TOKEN_USB_DATA && !backend.canDisableUsbData()) return if (on) unsupported(WHY_DEVICE) else null
        return runCatching {
            when (token) {
                TOKEN_CAMERA -> backend.setCameraDisabled(on)
                TOKEN_AUTO_TIME -> backend.setAutoTimeRequired(on)
                TOKEN_USB_DATA -> backend.setUsbDataEnabled(!on)
                else -> backend.setRestriction(token, on)
            }
        }.exceptionOrNull()?.let { e ->
            // Some Android builds know a restriction but do not let an admin set it ("Unsupported user restriction").
            if (e.message.orEmpty().startsWith(PLATFORM_REFUSAL)) unsupported(WHY_PLATFORM)
            else "failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun isOn(token: String): Boolean? = runCatching {
        when (token) {
            TOKEN_CAMERA -> backend.isCameraDisabled()
            TOKEN_AUTO_TIME -> backend.isAutoTimeRequired()
            TOKEN_USB_DATA -> !backend.isUsbDataEnabled()
            else -> backend.hasRestriction(token)
        }
    }.getOrNull()

    companion object {
        private const val DEBUGGING = "no_debugging_features"

        /** Marker kept with the owned tokens: USB debugging was on when this section blocked it. */
        const val ADB_WAS_ON = "@adbWasOn"
        const val APPLIED = "applied"
        const val WHY_NOT_OWNER = "not-device-owner"
        const val WHY_UNKNOWN = "unknown"
        const val WHY_DEVICE = "device"
        const val WHY_PLATFORM = "platform"
        private const val PLATFORM_REFUSAL = "Unsupported user restriction"
        const val WHY_HELD = "held"
        const val WHY_NOT_EFFECTIVE = "not-effective"
        fun unsupported(why: String): String = "unsupported: $why"
    }
}
