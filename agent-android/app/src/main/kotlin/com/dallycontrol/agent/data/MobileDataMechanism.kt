package com.dallycontrol.agent.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.dallycontrol.policy.wifi.DpmHandle

/** A problem that makes the whole mobile-data section fail (reported in the config.apply result, then retried). */
class MobileDataProblem(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The two ways a Device Owner keeps apps off mobile data — Android has no "block mobile data for app X" switch:
 *  - [DPM] — `DevicePolicyManager.setMeteredDataDisabledPackages` (Android 9+): the system refuses metered networks
 *    (mobile data, and Wi-Fi marked as metered) to the listed apps, in the foreground too. No VPN, nothing to see.
 *  - [VPN] — the agent's local VPN ([MobileDataVpnService]) fixed as always-on with `setAlwaysOnVpnPackage` (which
 *    also authorises it, so nobody is asked) and protected with `DISALLOW_CONFIG_VPN`. Android 7–8, or when the
 *    policy forces it because a maker ignores the first one. Lockdown is NOT used: it would cut every app, on
 *    Wi-Fi too.
 * Only what this class set is undone.
 */
class MobileDataMechanism(
    private val context: Context,
    private val handle: DpmHandle,
    private val prefs: SharedPreferences,
) {
    /** What `auto` / `dpm` / `vpn` means on this Android version. */
    fun resolve(asked: String): String = when {
        asked == VPN -> VPN
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> DPM
        else -> VPN // the system restriction does not exist before Android 9
    }

    /**
     * Make [blocked] the apps without mobile data through [mechanism], undoing the other one.
     * @param idle the package the VPN holds on Wi-Fi (see [MobileDataUsage.idlePackage])
     * @return a non-fatal problem to show (some package could not be restricted), or null
     * @throws MobileDataProblem when it cannot be enforced at all
     */
    fun enforce(mechanism: String, blocked: Set<String>, idle: () -> String?): String? {
        if (mechanism == DPM) {
            releaseVpn()
            return setMeteredDisabled(blocked)
        }
        if (prefs.getBoolean(KEY_DPM_SET, false)) setMeteredDisabled(emptySet())
        holdVpn(blocked, idle)
        return null
    }

    /** Undo everything this class set (both mechanisms). */
    fun release() {
        if (prefs.getBoolean(KEY_DPM_SET, false)) runCatching { setMeteredDisabled(emptySet()) }
        runCatching { releaseVpn() }
    }

    private fun setMeteredDisabled(blocked: Set<String>): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val dpm = handle.dpm
        val current = runCatching { dpm.getMeteredDataDisabledPackages(handle.admin).toSet() }.getOrDefault(emptySet())
        var failed = emptyList<String>()
        if (current != blocked) {
            failed = try {
                dpm.setMeteredDataDisabledPackages(handle.admin, blocked.toList())
            } catch (e: SecurityException) {
                throw MobileDataProblem("el sistema rechazó la restricción de datos: ${e.message}", e)
            }
            prefs.edit().putBoolean(KEY_DPM_SET, blocked.isNotEmpty()).apply()
            Log.i(TAG, "metered data disabled for ${blocked.size} app(s), ${failed.size} refused")
        }
        return failed.takeIf { it.isNotEmpty() }
            ?.let { "el sistema no pudo restringir: " + it.take(SHOWN_FAILURES).joinToString(", ") }
    }

    private fun holdVpn(blocked: Set<String>, idle: () -> String?) {
        val own = context.packageName
        val alwaysOn = currentAlwaysOnVpn()
        if (blocked.isEmpty()) {
            releaseVpn()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && alwaysOn != own) {
            try {
                handle.dpm.setAlwaysOnVpnPackage(handle.admin, own, false)
            } catch (e: PackageManager.NameNotFoundException) {
                throw MobileDataProblem("no se pudo fijar la VPN local: ${e.message}", e)
            } catch (e: UnsupportedOperationException) {
                throw MobileDataProblem("no se pudo fijar la VPN local: ${e.message}", e)
            }
            prefs.edit().putBoolean(KEY_VPN_SET, true).apply()
        }
        lockVpnSettings()
        // Unchanged and already up: nothing to write or restart (this runs every minute).
        val changed = blocked != MobileDataVpn.blockedPackages(context) || !MobileDataVpn.up
        if (changed) MobileDataVpn.set(context, blocked, idle())
    }

    /**
     * The always-on VPN set on the device, or null.
     * @throws MobileDataProblem when the local VPN cannot be used here: Android 6, or another always-on VPN
     */
    private fun currentAlwaysOnVpn(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            throw MobileDataProblem("Android 6 no permite fijar la VPN local sin intervención del usuario")
        }
        val alwaysOn = runCatching { handle.dpm.getAlwaysOnVpnPackage(handle.admin) }.getOrNull()
        if (alwaysOn != null && alwaysOn != context.packageName) {
            throw MobileDataProblem("hay otra VPN siempre activa en el equipo ($alwaysOn)")
        }
        return alwaysOn
    }

    /** The user cannot disconnect or replace the VPN. Lifted later only if it was not already there. */
    private fun lockVpnSettings() {
        if (prefs.getBoolean(KEY_VPN_RESTRICTION, false)) return
        val had = runCatching {
            val um = context.getSystemService(Context.USER_SERVICE) as UserManager
            um.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)
        }.getOrDefault(false)
        runCatching { handle.dpm.addUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_VPN) }
        prefs.edit().putBoolean(KEY_VPN_RESTRICTION, true).putBoolean(KEY_VPN_RESTRICTION_OURS, !had).apply()
    }

    private fun releaseVpn() {
        val held = MobileDataVpn.blockedPackages(context).isNotEmpty() || MobileDataVpn.up
        if (held) MobileDataVpn.set(context, emptySet(), null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && prefs.getBoolean(KEY_VPN_SET, false)) {
            runCatching {
                if (handle.dpm.getAlwaysOnVpnPackage(handle.admin) == context.packageName) {
                    handle.dpm.setAlwaysOnVpnPackage(handle.admin, null, false)
                }
            }
            prefs.edit().putBoolean(KEY_VPN_SET, false).apply()
        }
        if (prefs.getBoolean(KEY_VPN_RESTRICTION, false)) {
            if (prefs.getBoolean(KEY_VPN_RESTRICTION_OURS, false)) {
                runCatching { handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_CONFIG_VPN) }
            }
            prefs.edit().putBoolean(KEY_VPN_RESTRICTION, false).apply()
        }
    }

    companion object {
        const val DPM = "dpm"
        const val VPN = "vpn"
        const val NONE = "none"
        private const val TAG = "MobileData"
        private const val SHOWN_FAILURES = 5
        private const val KEY_DPM_SET = "dpm_set"
        private const val KEY_VPN_SET = "vpn_set"
        private const val KEY_VPN_RESTRICTION = "vpn_restriction"
        private const val KEY_VPN_RESTRICTION_OURS = "vpn_restriction_ours"
    }
}
