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
 *  - sharing data (hotspot / tethering): allowed (the default: the kiosk shows a "share data" icon) or blocked;
 *  - Google accounts: adding them blocked, or only accounts of some domains (others are removed the moment they are
 *    added — see [watchAccounts] — and Chrome's sign-in is limited to those domains too);
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

        val domains = (rules?.accountDomains ?: listOfNotNull(rules?.accountDomain))
            .map { it.trim().lowercase().substringAfterLast('@') }.filter { it.isNotEmpty() }.distinct()
        prefs.edit().remove(KEY_DOMAIN).apply {
            if (domains.isNotEmpty()) putStringSet(KEY_DOMAINS, domains.toSet()) else remove(KEY_DOMAINS)
        }.apply()
        chromeSignIn(domains)
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
        prefs.edit().putBoolean(KEY_TETHER_OFFERED, rules?.tethering != "block").apply()
        return ConfigOutcome.APPLIED
    }

    /** The kiosk offers "share data" unless the policy blocks it. */
    fun tetheringOffered(): Boolean = tetheringOffered(context)

    private fun domains(): Set<String> =
        prefs.getStringSet(KEY_DOMAINS, null) ?: prefs.getString(KEY_DOMAIN, null)?.let { setOf(it) }.orEmpty()

    /**
     * Remove Google accounts that are not of the allowed domains (a Device Owner sees every account and may remove it).
     * Called when the rules are applied, the moment accounts change ([watchAccounts]), and on check-ins.
     */
    fun enforceAccounts() {
        val domains = domains()
        if (domains.isEmpty()) return
        runCatching {
            val am = AccountManager.get(context)
            am.getAccountsByType(GOOGLE).filterNot { a -> domains.any { a.name.lowercase().endsWith("@$it") } }.forEach { account ->
                am.removeAccount(account, null, null, null)
                events.record(EventType.ACCOUNT_REMOVED, "${account.name.substringBefore('@').take(2)}***@${account.name.substringAfter('@', "")}")
            }
        }
    }

    private var watching: android.accounts.OnAccountsUpdateListener? = null

    /** While the agent runs: an account of another domain is removed as soon as it is added (in Gmail or Settings). */
    @Synchronized
    fun watchAccounts() {
        if (watching != null) return
        val l = android.accounts.OnAccountsUpdateListener { enforceAccounts() }
        runCatching {
            val am = AccountManager.get(context)
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            if (Build.VERSION.SDK_INT >= 26) am.addOnAccountsUpdatedListener(l, main, false, arrayOf(GOOGLE))
            else am.addOnAccountsUpdatedListener(l, main, false)
            watching = l
        }
    }

    @Synchronized
    fun unwatchAccounts() {
        val l = watching ?: return
        runCatching { AccountManager.get(context).removeOnAccountsUpdatedListener(l) }
        watching = null
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

    /** Chrome: only accounts of the domains may sign in (merged into whatever the browser policy already set). */
    private fun chromeSignIn(domains: List<String>) {
        val had = prefs.getBoolean(KEY_CHROME, false)
        if (domains.isEmpty() && !had) return
        runCatching {
            val b = handle.dpm.getApplicationRestrictions(handle.admin, CHROME)
            if (domains.isNotEmpty()) b.putString("RestrictSigninToPattern", signInPattern(domains)) else b.remove("RestrictSigninToPattern")
            handle.dpm.setApplicationRestrictions(handle.admin, CHROME, b)
            prefs.edit().putBoolean(KEY_CHROME, domains.isNotEmpty()).apply()
        }
    }

    companion object {
        /** Chrome's sign-in pattern for these domains, e.g. `.*@(amovil\.co|alpina\.com)`. */
        fun signInPattern(domains: List<String>): String =
            ".*@(" + domains.joinToString("|") { it.replace(".", "\\.") } + ")"

        /** Read by the kiosk: "share data" is offered unless the policy blocks it (true before any policy too). */
        fun tetheringOffered(context: Context): Boolean =
            context.getSharedPreferences("mdm_device_rules", Context.MODE_PRIVATE).getBoolean(KEY_TETHER_OFFERED, true)

        private const val GOOGLE = "com.google"
        private const val CHROME = "com.android.chrome"
        private const val KEY_RESTRICTIONS = "restrictions"
        private const val KEY_GOOGLE_BLOCKED = "google_blocked"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_DOMAINS = "domains"
        private const val KEY_FRP = "frp"
        private const val KEY_CHROME = "chrome"
        private const val KEY_TETHER_OFFERED = "tether_offered"
    }
}
