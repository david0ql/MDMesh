package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigWifi
import com.dallycontrol.proto.ConfigWifiRules

/**
 * Keeps the configuration's Wi-Fi on the device: its networks saved (and the ones it added that were dropped removed),
 * its lock-down rules in force, and the radio where the policy wants it.
 */
fun interface ManagedWifi {
    /**
     * @param rules lock-down rules; null = none (whatever an earlier policy set is lifted).
     * @param radio true = forced on, false = forced off, null = the user decides.
     * @return a ConfigOutcome string.
     */
    fun apply(networks: List<ConfigWifi>, rules: ConfigWifiRules?, radio: Boolean?): String
}
