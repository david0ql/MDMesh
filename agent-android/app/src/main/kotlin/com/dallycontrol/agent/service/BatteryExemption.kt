package com.dallycontrol.agent.service

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Keeps the agent out of Doze / battery optimization so the AlarmManager heartbeat ([WakeKeepAlive]) and the
 * WebSocket keep delivering commands promptly. This build has NO FCM, so power-saver throttling of our background
 * work translates directly into late commands — hence we actively claim an exemption at startup.
 *
 * ## Which API, and its limits
 * There is **no public SDK Device-Owner API** to silently add an app to the battery allowlist. The only managed
 * lever, `DevicePolicyManager.setApplicationExemptions(pkg, {EXEMPT_FROM_POWER_RESTRICTIONS})` (added in API 34,
 * Android 14), is a **`@SystemApi`**: it is hidden from the public `android.jar` and gated by the signature/role
 * permission `MANAGE_DEVICE_POLICY_APP_EXEMPTIONS`, which an ordinary Device Owner does NOT automatically hold. So:
 *
 *  1. **Best-effort silent path (API 34+):** we invoke `setApplicationExemptions` via reflection. On a
 *     platform-signed / privileged build where the agent holds the permission this exempts us with no dialog; on a
 *     plain Device-Owner build it throws (SecurityException / NoSuchMethod) and we fall through. Reflection is used
 *     precisely because the symbol is not in the compile SDK — it is not a public API we can link against.
 *  2. **Public fallback (all versions):** if we are not already exempt, fire
 *     [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] for our own package (requires the declared
 *     `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission). On a provisioned / kiosk device this is commonly a no-op
 *     or auto-handled; off Device Owner it surfaces the system confirmation dialog for the user to accept.
 *
 * Idempotent and crash-safe: returns early when already exempt (so no dialog in steady state), and wraps every
 * platform call so a refusing OEM can never bring down startup. Re-asserted on every startup.
 */
object BatteryExemption {
    private const val TAG = "BatteryExemption"

    /** Claim (or re-assert) a battery-optimization exemption for this app. Safe to call on every startup. */
    fun ensure(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val pkg = context.packageName
        // Already exempt — nothing to do (keeps this idempotent and prompt-free in steady state).
        if (runCatching { pm.isIgnoringBatteryOptimizations(pkg) }.getOrDefault(false)) return

        // 1. Best-effort silent Device-Owner exemption (Android 14+, privileged builds only).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && exemptViaDevicePolicy(context, pkg)) return

        // During the setup wizard (QR / zero-touch provisioning starts this process before the device is set up) the
        // request would put a Settings dialog on top of Android's provisioning screens. Wait until setup is done;
        // this runs again on every start.
        // 2. Otherwise fall back to the public request intent (dialog off-DO; usually auto-handled on a managed
        //    device).
        if (setupComplete(context)) requestIgnore(context, pkg)
    }

    /**
     * API 34+ Device-Owner path via reflection (the symbol is a hidden `@SystemApi`). Returns true only when the
     * exemption call actually succeeded; any missing symbol or permission denial returns false to trigger the
     * fallback.
     */
    private fun exemptViaDevicePolicy(context: Context, pkg: String): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        if (!runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)) return false
        return runCatching {
            val clazz = DevicePolicyManager::class.java
            val exemptPower = clazz.getField("EXEMPT_FROM_POWER_RESTRICTIONS").getInt(null)
            val method = clazz.getMethod("setApplicationExemptions", String::class.java, Set::class.java)
            method.invoke(dpm, pkg, setOf(exemptPower))
            Log.i(TAG, "exempted from power restrictions via DevicePolicyManager.setApplicationExemptions")
            true
        }.getOrElse {
            Log.w(TAG, "setApplicationExemptions unavailable/denied: ${it.message}")
            false
        }
    }

    /** False while the device is still in its setup wizard (unknown reads as "set up": the old behaviour). */
    internal fun setupComplete(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, "user_setup_complete", 1) != 0 &&
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1) != 0
    }.getOrDefault(true)

    /** Public / fallback path: ask the system to ignore battery optimizations for us. */
    private fun requestIgnore(context: Context, pkg: String) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { Log.w(TAG, "battery-optimization request unavailable: ${it.message}") }
    }
}
