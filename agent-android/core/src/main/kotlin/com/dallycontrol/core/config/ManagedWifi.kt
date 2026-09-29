package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigWifi

/** Keeps the configuration's Wi-Fi networks saved on the device (and removes the ones it added that were dropped). */
fun interface ManagedWifi {
    /** @return a ConfigOutcome string. */
    fun apply(networks: List<ConfigWifi>): String
}
