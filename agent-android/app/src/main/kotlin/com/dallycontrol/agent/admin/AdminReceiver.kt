package com.dallycontrol.agent.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.dallycontrol.core.sync.CheckInWorker

/**
 * Device-admin / Device-Owner receiver — the component named when binding the DPC
 * (`dpm set-device-owner com.dallycontrol.agent/.admin.AdminReceiver`).
 *
 * Keep this thin: it reacts to admin lifecycle events and delegates real work to the
 * injected graph. The signing certificate of the app that owns this receiver is what
 * the DO binding is tied to — see the release signing note in `app/build.gradle.kts`.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Admin (and, when provisioned this way, Device Owner) is now active.
        setStableOrganizationId(context)
        grantLocationAccess(context)
        protectAgentProcess(context)
        CheckInWorker.schedule(context)
        // QR / zero-touch on Android 10+: the payload was kept at GET_PROVISIONING_MODE; if the callbacks that
        // normally deliver it do not come, enroll with that copy (see ProvisioningIntake.adoptPending).
        com.dallycontrol.agent.provisioning.ProvisioningIntake.adoptPendingSoon(context)
        com.dallycontrol.agent.provisioning.EnrollPrompt.watch(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        // Fully-managed provisioning finished. Set the org id FIRST so getEnrollmentSpecificId()
        // is populated before the first check-in reports the hardware id (see HardwareIdCollector).
        setStableOrganizationId(context)
        grantLocationAccess(context)
        protectAgentProcess(context)
        // Capture the server URL from the QR bundle BEFORE any check-in, so one prebuilt APK can
        // serve any deployment (it falls back to the baked URL only when absent — dev/ADB).
        // This broadcast is the provisioning callback of Android 11 and below (Android 12+ uses
        // AdminPolicyComplianceActivity). It used to store the token and the server only; the shared intake also
        // applies the Device-Owner baseline (passcode-reset token, silent permission grants, telemetry permissions,
        // remote-support preparation), starts the wake channel and keeps retrying the enrollment.
        // The token is stored asynchronously; keep the receiver alive until it is on disk.
        val pending = goAsync()
        com.dallycontrol.agent.provisioning.ProvisioningIntake.accept(
            context,
            com.dallycontrol.agent.provisioning.ProvisioningIntake.extrasOf(intent),
            com.dallycontrol.core.enroll.EnrollStatusStore.PATH_QR_LEGACY,
        ) { runCatching { pending.finish() } }
    }

    /** Entering kiosk: block other apps/services from drawing toasts/dialogs over the kiosk. */
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        setCreateWindowsRestriction(context, restrict = true)
    }

    /** Leaving kiosk: allow overlays again. */
    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        setCreateWindowsRestriction(context, restrict = false)
    }

    /**
     * Set a fleet-wide, constant organization id so [DevicePolicyManager.getEnrollmentSpecificId]
     * returns a stable per-device id. The enrollment-specific id is derived from this org id + our
     * DPC package + a hardware identifier, and — unlike `ANDROID_ID` — is **stable across factory
     * resets**, which is what lets the server de-duplicate a wiped-and-re-enrolled device. Using one
     * constant id for the whole fleet keeps each physical device's id stable while still differing
     * between devices. Idempotent: it can only be set once per owner session, so a repeat call
     * throws and is swallowed. API 31+.
     */
    private fun setStableOrganizationId(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            dpm.setOrganizationId(ORGANIZATION_ID)
        }
    }

    /**
     * As Device Owner, silently grant location permission (incl. background) and turn location
     * services on, so the agent can report device location for telemetry without any user prompt.
     */
    private fun grantLocationAccess(context: Context) {
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return
            val pkg = context.packageName
            if (!dpm.isDeviceOwnerApp(pkg)) return
            val admin = componentName(context)
            val perms = mutableListOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                perms.add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            perms.forEach { perm ->
                runCatching {
                    dpm.setPermissionGrantState(
                        admin, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }
            }
            runCatching { enableLocationServices(dpm, admin) }
        }
    }

    /**
     * As Device Owner, exempt the agent itself from user/OEM "app control": force-stop, and the
     * background restrictions OEM battery managers (notably Samsung's app sleep) apply to unused
     * apps. A force-stopped app receives NO broadcasts — not even BOOT_COMPLETED — which severs
     * management permanently until someone taps the app. API 30+; idempotent.
     */
    private fun protectAgentProcess(context: Context) = protectProcesses(context)


    private fun setCreateWindowsRestriction(context: Context, restrict: Boolean) {
        runCatching {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = componentName(context)
            if (restrict) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS)
            }
        }
    }

    companion object {
        /** Also applied on every agent start and before remote support (phones enrolled by older agents get it too). */
        fun protectProcesses(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            runCatching {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    ?: return
                if (!dpm.isDeviceOwnerApp(context.packageName)) return
                // The agent and its remote-support app: the person cannot force-stop them, and Android (and makers' battery
                // managers that honour it) leaves them running in the background — remote support dies otherwise on Xiaomi.
                // Added to whatever is already protected (the permission policy protects apps too), never replacing it.
                val admin = componentName(context)
                val wanted = listOf(context.packageName, com.dallycontrol.agent.remote.DroidVncController.PACKAGE)
                val current = runCatching { dpm.getUserControlDisabledPackages(admin) }.getOrDefault(emptyList())
                if (!current.containsAll(wanted)) {
                    dpm.setUserControlDisabledPackages(admin, (current + wanted).distinct())
                }
            }
        }

        /** Key in the QR `PROVISIONING_ADMIN_EXTRAS_BUNDLE` carrying the enroll token. */
        const val EXTRA_ENROLL_TOKEN = "com.dallycontrol.ENROLL_TOKEN"

        /** Key carrying the server base URL, so one prebuilt APK serves any deployment. */
        const val EXTRA_SERVER_URL = "com.dallycontrol.SERVER_URL"

        /** Constant fleet org id feeding the factory-reset-stable enrollment-specific id. */
        const val ORGANIZATION_ID = "dallycontrol-fleet"

        /**
         * Turn device location services on as Device Owner: setLocationEnabled on API 30+, the
         * LOCATION_MODE secure setting (which a DO may write on API 23-29) below that. Without the
         * pre-R branch, Android 6-10 devices whose location was off never reported a position.
         */
        fun enableLocationServices(dpm: DevicePolicyManager, admin: ComponentName) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setLocationEnabled(admin, true)
            } else {
                @Suppress("DEPRECATION")
                dpm.setSecureSetting(
                    admin,
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString(),
                )
            }
        }

        /** This agent's admin component, used wherever a [ComponentName] is needed. */
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, AdminReceiver::class.java)
    }
}
