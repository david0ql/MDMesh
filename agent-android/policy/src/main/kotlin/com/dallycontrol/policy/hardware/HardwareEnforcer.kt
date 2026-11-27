package com.dallycontrol.policy.hardware

import com.dallycontrol.policy.hardware.HardwareCatalog.ALLOW
import com.dallycontrol.policy.hardware.HardwareCatalog.BLOCK
import com.dallycontrol.policy.hardware.HardwareCatalog.TOKEN_AUTO_TIME
import com.dallycontrol.policy.hardware.HardwareCatalog.TOKEN_CAMERA
import com.dallycontrol.policy.hardware.HardwareCatalog.TOKEN_USB_DATA

/** The device side of the hardware rules. Implemented over `DevicePolicyManager`; faked in tests. */
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
 *  - a restriction another section of the document keeps on (`held`) is never cleared here.
 *
 * Outcome keys: `hardware.<rule>` and `restrictions.<no_xxx>`.
 */
class HardwareEnforcer(private val backend: HardwareBackend, private val owned: OwnedTokens) {

    private class Wanted(val tokens: List<String>, val blocked: Boolean)

    fun apply(rules: Map<String, String>, restrictions: List<String>, held: Set<String>): Map<String, String> {
        val outcomes = linkedMapOf<String, String>()
        if (!backend.isDeviceOwner()) {
            rules.keys.forEach { outcomes["hardware.$it"] = unsupported(WHY_NOT_OWNER) }
            restrictions.forEach { outcomes["restrictions.$it"] = unsupported(WHY_NOT_OWNER) }
            return outcomes
        }
        val sdk = backend.sdk
        val block = linkedSetOf<String>()
        val allow = linkedSetOf<String>()
        val pending = linkedMapOf<String, Wanted>()

        for ((key, value) in rules) {
            val name = "hardware.$key"
            val rule = HardwareCatalog.rule(key)
            val blocked = value == BLOCK
            when {
                rule == null || (value != BLOCK && value != ALLOW) -> outcomes[name] = unsupported(WHY_UNKNOWN)
                // Nothing to lift on an Android that never had the rule: "allow" already holds.
                sdk < rule.minSdk -> outcomes[name] = if (blocked) unsupported("sdk-${rule.minSdk}") else APPLIED
                else -> {
                    val tokens = HardwareCatalog.tokensFor(rule, sdk)
                    if (blocked) block += tokens else allow += tokens
                    pending[name] = Wanted(tokens, blocked)
                    outcomes[name] = "" // keeps the document's order; filled in below
                }
            }
        }
        for (key in restrictions) {
            val name = "restrictions.$key"
            val min = HardwareCatalog.FREE_RESTRICTIONS[key]
            when {
                min == null -> outcomes[name] = unsupported(WHY_UNKNOWN)
                sdk < min -> outcomes[name] = unsupported("sdk-$min")
                else -> {
                    block += key
                    pending[name] = Wanted(listOf(key), true)
                    outcomes[name] = ""
                }
            }
        }
        allow -= block

        val mine = owned.load().toMutableSet()
        val errors = hashMapOf<String, String>()
        for (t in block) {
            val e = switch(t, true)
            if (e == null) mine += t else errors[t] = e
        }
        for (t in (mine + allow) - block) {
            if (t in held) {
                mine -= t
                errors[t] = unsupported(WHY_HELD)
                continue
            }
            val e = switch(t, false)
            if (e == null) mine -= t else if (t in allow) errors[t] = e
        }
        owned.save(mine)

        for ((name, w) in pending) {
            val errs = w.tokens.mapNotNull { errors[it] }
            outcomes[name] = errs.firstOrNull { it.startsWith("failed") } ?: errs.firstOrNull()
                ?: if (w.tokens.all { isOn(it) != !w.blocked }) APPLIED else "failed: $WHY_NOT_EFFECTIVE"
        }
        return outcomes
    }

    /** @return null when done, or the outcome that explains why not. */
    private fun switch(token: String, on: Boolean): String? {
        // A device whose USB HAL cannot cut data never had it cut: blocking is impossible, allowing is already true.
        if (token == TOKEN_USB_DATA && !backend.canDisableUsbData()) return if (on) unsupported(WHY_DEVICE) else null
        return try {
            when (token) {
                TOKEN_CAMERA -> backend.setCameraDisabled(on)
                TOKEN_AUTO_TIME -> backend.setAutoTimeRequired(on)
                TOKEN_USB_DATA -> backend.setUsbDataEnabled(!on)
                else -> backend.setRestriction(token, on)
            }
            null
        } catch (e: Exception) {
            "failed: ${e.message ?: e.javaClass.simpleName}"
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
        const val APPLIED = "applied"
        const val WHY_NOT_OWNER = "not-device-owner"
        const val WHY_UNKNOWN = "unknown"
        const val WHY_DEVICE = "device"
        const val WHY_HELD = "held"
        const val WHY_NOT_EFFECTIVE = "not-effective"
        fun unsupported(why: String): String = "unsupported: $why"
    }
}
