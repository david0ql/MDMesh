package com.dallycontrol.policy.frp

/**
 * The user restrictions that close the ways around a managed device other than the reset from recovery: reset from
 * Settings, safe mode, USB debugging and unlocking the bootloader (which allows flashing another system).
 * Pure: which restriction keys a rule needs on which Android version.
 */
object TamperRestrictions {
    const val FACTORY_RESET = "factoryReset"
    const val SAFE_BOOT = "safeBoot"
    const val DEBUGGING = "debugging"
    const val OEM_UNLOCK = "oemUnlock"

    const val DISALLOW_FACTORY_RESET = "no_factory_reset"
    const val DISALLOW_SAFE_BOOT = "no_safe_boot"
    const val DISALLOW_DEBUGGING_FEATURES = "no_debugging_features"
    const val DISALLOW_OEM_UNLOCK = "no_oem_unlock"

    private const val API_OEM_UNLOCK = 24
    private const val API_OEM_UNLOCK_VIA_RESET = 28

    val RULES: List<String> = listOf(FACTORY_RESET, SAFE_BOOT, DEBUGGING, OEM_UNLOCK)

    /** The restriction of a rule on this Android version, or null when the version has none for it. */
    fun restrictionFor(rule: String, sdkInt: Int): String? = when (rule) {
        FACTORY_RESET -> DISALLOW_FACTORY_RESET
        SAFE_BOOT -> DISALLOW_SAFE_BOOT
        DEBUGGING -> DISALLOW_DEBUGGING_FEATURES
        // Android 7-8.1 have a restriction of their own. From Android 9 it is deprecated: the "OEM unlocking"
        // switch is closed by DISALLOW_FACTORY_RESET instead.
        OEM_UNLOCK -> when {
            sdkInt >= API_OEM_UNLOCK_VIA_RESET -> DISALLOW_FACTORY_RESET
            sdkInt >= API_OEM_UNLOCK -> DISALLOW_OEM_UNLOCK
            else -> null
        }
        else -> null
    }

    /**
     * Every restriction the wanted rules need (rule -> "block" wanted). A rule the version cannot serve is left out;
     * [Plan.unsupported] lists those.
     */
    fun plan(wanted: Map<String, Boolean>, sdkInt: Int): Plan {
        val restrictions = LinkedHashSet<String>()
        val unsupported = ArrayList<String>()
        for ((rule, on) in wanted) {
            if (!on) continue
            val r = restrictionFor(rule, sdkInt)
            if (r == null) unsupported.add(rule) else restrictions.add(r)
        }
        return Plan(restrictions, unsupported)
    }

    /** Which rules are in force given the restrictions the device holds (rule -> blocked). */
    fun effective(held: Set<String>, sdkInt: Int): Map<String, Boolean> =
        RULES.associateWith { rule -> restrictionFor(rule, sdkInt)?.let { it in held } ?: false }

    data class Plan(val restrictions: Set<String>, val unsupported: List<String>)

    /** All the restriction keys these rules may ever set (what is cleared when a rule goes away). */
    val ALL: Set<String> = setOf(DISALLOW_FACTORY_RESET, DISALLOW_SAFE_BOOT, DISALLOW_DEBUGGING_FEATURES, DISALLOW_OEM_UNLOCK)
}
