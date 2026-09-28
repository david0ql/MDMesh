package com.dallycontrol.policy

import com.dallycontrol.policy.bluetooth.BluetoothPolicy
import com.dallycontrol.policy.bluetooth.BluetoothPolicyFactory
import com.dallycontrol.policy.camera.CameraPolicy
import com.dallycontrol.policy.camera.CameraPolicyFactory
import com.dallycontrol.policy.screenshots.ScreenshotsPolicy
import com.dallycontrol.policy.screenshots.ScreenshotsPolicyFactory
import com.dallycontrol.policy.usb.UsbStoragePolicy
import com.dallycontrol.policy.usb.UsbStoragePolicyFactory
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.policy.wifi.WifiPolicy
import com.dallycontrol.policy.wifi.WifiPolicyFactory

/**
 * Reports which policy capability keys (from `proto/registry.md`) *this* device
 * actually supports, by probing each policy's strategy factory.
 *
 * This is the bridge between the capability-abstraction layer and the protocol:
 * [supportedPolicyKeys] feeds directly into the `capabilities.policy` array of the
 * [com.dallycontrol.proto.CapabilityMatrix]. A capability only appears here if a
 * usable strategy exists, which is exactly what guarantees the server never sends
 * a command the device can't honour.
 *
 * As more policy surfaces are added (bluetooth, camera, kiosk, ...) each registers
 * its factory probe here. The `:core` module wraps this together with app-mgmt,
 * remote-control, and OEM probes to build the full matrix.
 */
class CapabilityRegistry(
    private val handle: DpmHandle,
) {

    /**
     * The toggle policies with a working strategy on this device, keyed by capability
     * key. This is the single extension point for on/off policies: add a probe here and
     * both the advertised capabilities ([supportedPolicyKeys]) and the generic
     * `policy.apply` routing pick it up automatically — no switch to update.
     */
    fun togglePolicies(): Map<String, TogglePolicy> = buildMap {
        WifiPolicyFactory.create(handle)?.let { put(WifiPolicy.CAPABILITY_KEY, it) }
        CameraPolicyFactory.create(handle)?.let { put(CameraPolicy.CAPABILITY_KEY, it) }
        ScreenshotsPolicyFactory.create(handle)?.let { put(ScreenshotsPolicy.CAPABILITY_KEY, it) }
        BluetoothPolicyFactory.create(handle)?.let { put(BluetoothPolicy.CAPABILITY_KEY, it) }
        UsbStoragePolicyFactory.create(handle)?.let { put(UsbStoragePolicy.CAPABILITY_KEY, it) }
        // Each factory probe returns null on an unsupported device, so a key only
        // appears here when a usable strategy exists.
        // Absence == "not advertised" == "never commanded".
    }

    /**
     * The set of policy keys with a working strategy on this device — derived from the
     * registered strategies, so it can never drift from what can actually be applied.
     * Each entry corresponds to a row in `proto/registry.md` § policy.
     */
    fun supportedPolicyKeys(): List<String> = togglePolicies().keys.toList()

    /** Convenience: resolve the live [DeviceControl] facade for this device. */
    fun deviceControl(): DeviceControl? {
        val wifi = WifiPolicyFactory.create(handle) ?: return null
        return DefaultDeviceControl(wifi)
    }
}

/** Default [DeviceControl] facade wiring the selected strategies together. */
internal class DefaultDeviceControl(
    override val wifi: WifiPolicy,
) : DeviceControl
