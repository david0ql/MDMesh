package com.dallycontrol.policy.usb

import com.dallycontrol.policy.wifi.DpmHandle

/**
 * Selects the [UsbStoragePolicy] strategy for the current device. Single
 * candidate; factory shape kept for uniformity. Returns `null` when no strategy
 * is supported (e.g. not Device Owner), in which case `usbStorage` is never
 * advertised.
 */
object UsbStoragePolicyFactory {

    fun create(handle: DpmHandle): UsbStoragePolicy? =
        listOf(UsbStorageRestrictionPolicy(handle)).firstOrNull { it.isSupported() }
}
