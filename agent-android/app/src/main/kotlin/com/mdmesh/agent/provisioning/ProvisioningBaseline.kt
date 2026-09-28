package com.mdmesh.agent.provisioning

import android.app.admin.DevicePolicyManager
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.core.action.ResetPasswordTokenStore
import com.mdmesh.policy.PolicyManager
import com.mdmesh.policy.wifi.DpmHandle

/**
 * The Device-Owner baseline every enrollment path applies once: QR/NFC/zero-touch
 * ([AdminPolicyComplianceActivity]) and USB/ADB ([AdbProvisionReceiver]). Without it an
 * ADB-enrolled device had no reset-password token (device.passcodeReset always failed) and
 * prompted for runtime permissions its managed apps should get silently.
 */
object ProvisioningBaseline {
    /** Benign Device-Owner baseline: auto-grant runtime permissions to managed apps so they
     *  never prompt. Restrictive policies are pushed by the admin via commands, not here. */
    fun apply(ctx: android.content.Context) {
        runCatching {
            val dpm = ctx.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val handle = DpmHandle(dpm, AdminReceiver.componentName(ctx))
            PolicyManager(handle).setPermissionAutoGrant()
            // Provision the DO reset-password token once, so device.passcodeReset works later.
            ResetPasswordTokenStore(ctx, handle).ensureToken()
            // Silently grant the telemetry runtime permissions as Device Owner.
            listOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ).forEach { perm ->
                runCatching {
                    dpm.setPermissionGrantState(
                        handle.admin, ctx.packageName, perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }
            }
            // Enable location services (DO) so Wi-Fi SSID telemetry is readable on Android 10+.
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    dpm.setLocationEnabled(handle.admin, true)
                }
            }
        }
    }
}
