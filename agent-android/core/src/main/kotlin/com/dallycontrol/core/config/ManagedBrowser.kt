package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigBrowser

/** Pushes the configuration's site lists to the managed browser (Chrome's managed configuration). */
fun interface ManagedBrowser {
    /** @return a ConfigOutcome string. */
    fun apply(browser: ConfigBrowser): String
}
