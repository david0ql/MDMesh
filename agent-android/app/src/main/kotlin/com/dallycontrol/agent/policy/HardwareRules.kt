package com.dallycontrol.agent.policy

import android.content.Context
import com.dallycontrol.policy.hardware.DpmHardwareBackend
import com.dallycontrol.policy.hardware.HardwareEnforcer
import com.dallycontrol.policy.hardware.OwnedTokens
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigHardware

/**
 * Hardware and ports rules of a policy (camera, USB debugging, USB data, NFC, microphone, …) plus the validated free
 * `DISALLOW_*` list. The decisions live in [HardwareEnforcer]; this class only gives it the real device and remembers
 * what it switched on, so a rule that leaves the policy is undone and nothing else is touched.
 */
class HardwareRules(context: Context, handle: DpmHandle) {
    private val prefs = context.getSharedPreferences("mdm_hardware_rules", Context.MODE_PRIVATE)

    private val enforcer = HardwareEnforcer(
        DpmHardwareBackend(handle),
        object : OwnedTokens {
            override fun load(): Set<String> = prefs.getStringSet(KEY_OWNED, emptySet()).orEmpty().toSet()
            override fun save(tokens: Set<String>) { prefs.edit().putStringSet(KEY_OWNED, tokens).apply() }
        },
    )

    /** @return one outcome per rule: `hardware.<key>` / `restrictions.<key>` → applied | unsupported: why | failed: why. */
    @Synchronized
    fun apply(hardware: ConfigHardware?, held: Set<String>): Map<String, String> =
        enforcer.apply(hardware?.rules.orEmpty(), hardware?.restrictions.orEmpty(), held)

    private companion object {
        const val KEY_OWNED = "owned"
    }
}
