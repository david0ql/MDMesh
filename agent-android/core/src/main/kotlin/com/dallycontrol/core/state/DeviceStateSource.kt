package com.dallycontrol.core.state

import com.dallycontrol.proto.AgentDeviceStateDto

/**
 * Supplies the device-state snapshot piggybacked on each check-in. Abstracted behind an interface
 * so the Android-free sync logic (and its tests) don't depend on platform APIs. Implemented by
 * [DeviceStateCollector].
 */
fun interface DeviceStateSource {
    fun snapshot(): AgentDeviceStateDto?
}
