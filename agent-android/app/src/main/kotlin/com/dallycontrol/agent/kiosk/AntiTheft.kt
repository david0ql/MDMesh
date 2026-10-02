package com.dallycontrol.agent.kiosk

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import com.dallycontrol.agent.admin.AdminReceiver

/**
 * Anti-theft for a kiosk: the power menu is closed (the kiosk's lock-task features leave out GLOBAL_ACTIONS), so the
 * phone cannot be switched off from it; switching it off or restarting it asks for the policy's PIN in the kiosk's
 * quick settings. Safe mode and factory reset from Settings are disallowed too. What Android does not let anyone
 * block is the hardware long-press that forces a power cut.
 */
object AntiTheft {
    private const val PREFS = "mdm_antitheft"
    private const val KEY_PIN = "pin"
    private const val KEY_FEATURES = "features"
    private const val KEY_RESTRICTED = "restricted"
    private const val UNLOCK_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())

    /** Remember the PIN and the kiosk's lock-task feature mask (to restore after a timed unlock); null PIN = off. */
    fun apply(context: Context, pin: String?, lockTaskFeatures: Int) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.componentName(context)
        val on = !pin.isNullOrBlank()
        prefs.edit().apply { if (on) putString(KEY_PIN, pin) else remove(KEY_PIN) }.putInt(KEY_FEATURES, lockTaskFeatures).apply()
        runCatching {
            // (Factory reset is blocked by the policy's device rules, which the server turns on with the PIN.)
            val restrictions = listOf(UserManager.DISALLOW_SAFE_BOOT)
            if (on) {
                restrictions.forEach { dpm.addUserRestriction(admin, it) }
                prefs.edit().putBoolean(KEY_RESTRICTED, true).apply()
            } else if (prefs.getBoolean(KEY_RESTRICTED, false)) {
                restrictions.forEach { dpm.clearUserRestriction(admin, it) } // only what this feature set
                prefs.edit().remove(KEY_RESTRICTED).apply()
            }
        }
    }

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_PIN)

    fun pinMatches(context: Context, typed: String): Boolean {
        val pin = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PIN, null) ?: return false
        return java.security.MessageDigest.isEqual(pin.toByteArray(), typed.trim().toByteArray())
    }

    /** After the PIN: open the power menu for [UNLOCK_MS], then close it again. */
    fun allowPowerMenu(context: Context) {
        if (Build.VERSION.SDK_INT < 28) return
        val app = context.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.componentName(app)
        val base = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_FEATURES, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        runCatching { dpm.setLockTaskFeatures(admin, base or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS) }
        main.postDelayed({
            // Still anti-theft and unchanged since: close the power menu again.
            val now = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (now.contains(KEY_PIN)) runCatching { dpm.setLockTaskFeatures(admin, now.getInt(KEY_FEATURES, base)) }
        }, UNLOCK_MS)
    }

    fun reboot(context: Context) {
        if (Build.VERSION.SDK_INT < 24) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        runCatching { dpm.reboot(AdminReceiver.componentName(context)) }
    }
}
