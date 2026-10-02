package com.dallycontrol.agent.policy

import android.accounts.AccountManager
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigDevice
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.EventType

/**
 * Device security rules of a policy (Device Owner):
 *  - sharing data (hotspot / tethering): allowed or blocked;
 *  - Google accounts: adding them blocked, or only accounts of one domain (others are removed as they appear; Chrome's
 *    sign-in is limited to the domain too);
 *  - factory reset from Settings blocked, and (Android 11+) the Google accounts that may set the phone up again after a
 *    reset done from recovery.
 * Only what these rules set is undone when a rule goes away.
 */
class DeviceRules(private val context: Context, private val handle: DpmHandle, private val events: EventSink) {
    private val prefs = context.getSharedPreferences("mdm_device_rules", Context.MODE_PRIVATE)

    fun apply(rules: ConfigDevice?): String {
        val dpm = handle.dpm
        val admin = handle.admin
        restriction(UserManager.DISALLOW_CONFIG_TETHERING, rules?.tethering == "block")
        restriction(UserManager.DISALLOW_FACTORY_RESET, rules?.factoryReset == "block")

        val blockGoogle = rules?.googleAccounts == "block"
        if (blockGoogle != prefs.getBoolean(KEY_GOOGLE_BLOCKED, false)) {
            runCatching { dpm.setAccountManagementDisabled(admin, GOOGLE, blockGoogle) }
            prefs.edit().putBoolean(KEY_GOOGLE_BLOCKED, blockGoogle).apply()
        }

        val domain = rules?.accountDomain?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply { if (domain != null) putString(KEY_DOMAIN, domain) else remove(KEY_DOMAIN) }.apply()
        chromeSignIn(domain)
        enforceAccounts()

        if (Build.VERSION.SDK_INT >= 30) {
            val accounts = rules?.frpAccounts.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            val had = prefs.getBoolean(KEY_FRP, false)
            if (accounts.isNotEmpty() || had) runCatching {
                dpm.setFactoryResetProtectionPolicy(
                    admin,
                    if (accounts.isEmpty()) null
                    else FactoryResetProtectionPolicy.Builder().setFactoryResetProtectionAccounts(accounts).setFactoryResetProtectionEnabled(true).build(),
                )
                prefs.edit().putBoolean(KEY_FRP, accounts.isNotEmpty()).apply()
            }
        }
        prefs.edit().putBoolean(KEY_TETHER_OFFERED, rules?.tethering == "allow").apply()
        return ConfigOutcome.APPLIED
    }

    /** The kiosk's quick settings offer "share data" only when the policy says so. */
    fun tetheringOffered(): Boolean = prefs.getBoolean(KEY_TETHER_OFFERED, false)

    /**
     * Remove Google accounts that are not of the allowed domain (a Device Owner may list and remove accounts).
     * Called when the rules are applied, when accounts change, and on check-ins.
     */
    fun enforceAccounts() {
        val domain = prefs.getString(KEY_DOMAIN, null) ?: return
        runCatching {
            val am = AccountManager.get(context)
            am.getAccountsByType(GOOGLE).filterNot { it.name.lowercase().endsWith("@$domain") }.forEach { account ->
                am.removeAccount(account, null, null, null)
                events.record(EventType.ACCOUNT_REMOVED, "${account.name.substringBefore('@').take(2)}***@${account.name.substringAfter('@', "")}")
            }
        }
    }

    private fun restriction(key: String, on: Boolean) {
        val mine = prefs.getStringSet(KEY_RESTRICTIONS, emptySet()).orEmpty()
        runCatching {
            if (on) {
                handle.dpm.addUserRestriction(handle.admin, key)
                prefs.edit().putStringSet(KEY_RESTRICTIONS, mine + key).apply()
            } else if (key in mine) {
                handle.dpm.clearUserRestriction(handle.admin, key)
                prefs.edit().putStringSet(KEY_RESTRICTIONS, mine - key).apply()
            }
        }
    }

    /** Chrome: only accounts of the domain may sign in (merged into whatever the browser policy already set). */
    private fun chromeSignIn(domain: String?) {
        val had = prefs.getBoolean(KEY_CHROME, false)
        if (domain == null && !had) return
        runCatching {
            val b = handle.dpm.getApplicationRestrictions(handle.admin, CHROME)
            if (domain != null) b.putString("RestrictSigninToPattern", ".*@" + Regex.escape(domain)) else b.remove("RestrictSigninToPattern")
            handle.dpm.setApplicationRestrictions(handle.admin, CHROME, b)
            prefs.edit().putBoolean(KEY_CHROME, domain != null).apply()
        }
    }

    private companion object {
        const val GOOGLE = "com.google"
        const val CHROME = "com.android.chrome"
        const val KEY_RESTRICTIONS = "restrictions"
        const val KEY_GOOGLE_BLOCKED = "google_blocked"
        const val KEY_DOMAIN = "domain"
        const val KEY_FRP = "frp"
        const val KEY_CHROME = "chrome"
        const val KEY_TETHER_OFFERED = "tether_offered"
    }
}
