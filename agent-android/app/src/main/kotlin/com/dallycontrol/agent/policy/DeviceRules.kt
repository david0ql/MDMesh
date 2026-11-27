package com.dallycontrol.agent.policy

import android.accounts.AccountManager
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.policy.frp.FactoryResetProtection
import com.dallycontrol.policy.frp.FrpPlatform
import com.dallycontrol.policy.frp.FrpPolicyState
import com.dallycontrol.policy.frp.TamperRestrictions
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigDevice
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.EventType
import com.dallycontrol.proto.FrpStatus
import com.dallycontrol.proto.ProtectionStatus

/**
 * Device security rules of a policy (Device Owner):
 *  - sharing data (hotspot / tethering): allowed (the default: the kiosk shows a "share data" icon) or blocked;
 *  - Google accounts: adding them blocked, or only accounts of some domains (others are removed the moment they are
 *    added — see [watchAccounts] — and Chrome's sign-in is limited to those domains too);
 *  - factory reset from Settings, safe mode, USB debugging and bootloader unlocking blocked ([TamperRestrictions]);
 *  - the company Google accounts that may set the phone up again after a reset done from recovery
 *    ([FactoryResetProtection]: Android 11+ by policy, Android 5.1-10 through Google Play services);
 *  - wipe after N wrong unlock attempts.
 * Only what these rules set is undone when a rule goes away. Nothing fails in silence: whatever the system refused
 * makes [apply] answer `failed: …`, is kept for [status] and travels to the server with the next report.
 */
class DeviceRules(private val context: Context, private val handle: DpmHandle, private val events: EventSink) {
    private val prefs = context.getSharedPreferences("mdm_device_rules", Context.MODE_PRIVATE)

    private val frp = FactoryResetProtection(AndroidFrp())

    fun apply(rules: ConfigDevice?): String {
        val dpm = handle.dpm
        val admin = handle.admin
        val errors = ArrayList<String>()
        restriction(UserManager.DISALLOW_CONFIG_TETHERING, rules?.tethering == "block")?.let(errors::add)
        // "block" = user cannot install apps (Play Store, APKs, sideload). MDM silent installs are unaffected.
        val blockInstall = rules?.installApps == "block"
        restriction(UserManager.DISALLOW_INSTALL_APPS, blockInstall)?.let(errors::add)
        restriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, blockInstall)?.let(errors::add)

        // Reset from Settings, safe mode, USB debugging, bootloader unlock.
        val tamper = TamperRestrictions.plan(
            mapOf(
                TamperRestrictions.FACTORY_RESET to (rules?.factoryReset == "block"),
                TamperRestrictions.SAFE_BOOT to (rules?.safeBoot == "block"),
                TamperRestrictions.DEBUGGING to (rules?.debugging == "block"),
                TamperRestrictions.OEM_UNLOCK to (rules?.oemUnlock == "block"),
            ),
            Build.VERSION.SDK_INT,
        )
        for (key in TamperRestrictions.ALL) {
            // The kiosk's anti-theft PIN holds "no safe mode" on its own account: never lift it from here.
            if (key == UserManager.DISALLOW_SAFE_BOOT && key !in tamper.restrictions && antiTheftHoldsSafeBoot()) continue
            restriction(key, key in tamper.restrictions)?.let(errors::add)
        }
        tamper.unsupported.forEach { errors.add("$it: not available on Android ${Build.VERSION.RELEASE}") }

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

        // Company factory-reset protection. "This device cannot" is reported by status(), not as a failed policy.
        val accounts = FactoryResetProtection.clean(rules?.frpAccounts.orEmpty())
        val frpNow = frp.apply(accounts, hadBefore = prefs.getBoolean(KEY_FRP, false))
        if (FactoryResetProtection.isFailure(frpNow)) {
            errors.add("frp: ${frpNow.error ?: "error"}")
        } else {
            prefs.edit().putBoolean(KEY_FRP, frpNow.active).apply()
        }
        prefs.edit().putStringSet(KEY_FRP_WANTED, accounts.toSet()).apply()

        prefs.edit().putInt(KEY_MAX_FAILED_POLICY, (rules?.maxFailedPasswordsForWipe ?: 0).coerceIn(0, MAX_FAILED_CAP)).apply()
        applyMaxFailed()?.let(errors::add)

        prefs.edit().putBoolean(KEY_TETHER_OFFERED, rules?.tethering != "block")
            .putString(KEY_ERRORS, errors.joinToString("\n")).apply()
        if (errors.isNotEmpty()) {
            events.record(EventType.PROTECTION_FAILED, "device: ${errors.joinToString("; ")}".take(MAX_EVENT))
            return ConfigOutcome.failed(errors.joinToString("; ").take(MAX_EVENT))
        }
        return ConfigOutcome.APPLIED
    }

    /**
     * Lost mode asks for a wipe after [attempts] wrong unlocks (0 = no longer asks). The stricter of this and the
     * policy's own value is what the device holds.
     */
    fun setLostMaxFailed(attempts: Int) {
        prefs.edit().putInt(KEY_MAX_FAILED_LOST, attempts.coerceIn(0, MAX_FAILED_CAP)).apply()
        applyMaxFailed()?.let { error(it) }
    }

    /** @return an error text, or null when the device holds the wanted value. */
    private fun applyMaxFailed(): String? {
        val wanted = listOf(prefs.getInt(KEY_MAX_FAILED_POLICY, 0), prefs.getInt(KEY_MAX_FAILED_LOST, 0)).filter { it > 0 }.minOrNull() ?: 0
        if (wanted == 0 && !prefs.getBoolean(KEY_MAX_FAILED_SET, false)) return null
        return runCatching {
            handle.dpm.setMaximumFailedPasswordsForWipe(handle.admin, wanted)
            prefs.edit().putBoolean(KEY_MAX_FAILED_SET, wanted > 0).apply()
            null
        }.getOrElse { "maxFailedPasswordsForWipe: ${it.javaClass.simpleName}: ${it.message}" }
    }

    /** Factory-reset protection and anti-tamper rules as the device holds them now, plus what failed last time. */
    fun status(): ProtectionStatus {
        val dpm = handle.dpm
        val owner = runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
        val wanted = prefs.getStringSet(KEY_FRP_WANTED, emptySet()).orEmpty().toList()
        val frpStatus = if (owner) frp.status(wanted) else FrpStatus(
            FactoryResetProtection.mechanismFor(Build.VERSION.SDK_INT), active = false,
            reason = FactoryResetProtection.REASON_NOT_DEVICE_OWNER,
        )
        val held: Set<String> = runCatching {
            if (owner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val b = dpm.getUserRestrictions(handle.admin)
                b.keySet().filter { b.getBoolean(it) }.toSet()
            } else {
                prefs.getStringSet(KEY_RESTRICTIONS, emptySet()).orEmpty()
            }
        }.getOrDefault(emptySet())
        return ProtectionStatus(
            frp = frpStatus,
            restrictions = TamperRestrictions.effective(held, Build.VERSION.SDK_INT),
            errors = prefs.getString(KEY_ERRORS, null).orEmpty().split('\n').filter { it.isNotBlank() },
            maxFailedPasswordsForWipe = runCatching {
                if (owner) dpm.getMaximumFailedPasswordsForWipe(handle.admin) else 0
            }.getOrDefault(0),
        )
    }

    private fun antiTheftHoldsSafeBoot(): Boolean =
        context.getSharedPreferences("mdm_antitheft", Context.MODE_PRIVATE).getBoolean("restricted", false)

    /** The Android side of factory-reset protection (see [FactoryResetProtection] for the rules by version). */
    private inner class AndroidFrp : FrpPlatform {
        override val sdkInt: Int get() = Build.VERSION.SDK_INT

        // isFactoryResetProtectionPolicySupported() is not public: ask when the system lets us, else assume yes and
        // let the calls below throw UnsupportedOperationException (FactoryResetProtection reads that as "cannot").
        override fun policySupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
            DevicePolicyManager::class.java.getMethod("isFactoryResetProtectionPolicySupported").invoke(handle.dpm) as Boolean
        }.getOrDefault(true)

        override fun setPolicy(accounts: List<String>?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            handle.dpm.setFactoryResetProtectionPolicy(
                handle.admin,
                accounts?.let {
                    FactoryResetProtectionPolicy.Builder()
                        .setFactoryResetProtectionAccounts(it).setFactoryResetProtectionEnabled(true).build()
                },
            )
        }

        override fun readPolicy(): FrpPolicyState? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            return handle.dpm.getFactoryResetProtectionPolicy(handle.admin)
                ?.let { FrpPolicyState(it.factoryResetProtectionAccounts, it.isFactoryResetProtectionEnabled) }
        }

        override fun gmsInstalled(): Boolean = runCatching {
            context.packageManager.getPackageInfo(FactoryResetProtection.GMS_PACKAGE, 0)
            true
        }.getOrDefault(false)

        override fun setGmsAccounts(accounts: List<String>?) {
            val b = handle.dpm.getApplicationRestrictions(handle.admin, FactoryResetProtection.GMS_PACKAGE)
            if (accounts == null) b.remove(FactoryResetProtection.GMS_RESTRICTION)
            else b.putStringArray(FactoryResetProtection.GMS_RESTRICTION, accounts.toTypedArray())
            handle.dpm.setApplicationRestrictions(handle.admin, FactoryResetProtection.GMS_PACKAGE, b)
        }

        override fun readGmsAccounts(): List<String> =
            handle.dpm.getApplicationRestrictions(handle.admin, FactoryResetProtection.GMS_PACKAGE)
                .getStringArray(FactoryResetProtection.GMS_RESTRICTION)?.toList().orEmpty()

        override fun notifyGms() {
            context.sendBroadcast(
                android.content.Intent(FactoryResetProtection.GMS_BROADCAST).setPackage(FactoryResetProtection.GMS_PACKAGE),
            )
        }
    }

    /**
     * Per-device "share data" override (command `device.dataSharing`): apply it right now, on top of the policy.
     * `enabled == true` lifts the tethering restriction (sharing allowed); `false` blocks it. Goes through the same
     * [restriction] tracking and the same `KEY_TETHER_OFFERED` flag the policy uses, so the kiosk's "share data" icon
     * updates at once and a later policy apply does not fight it (the server sends the override value in the policy
     * too). Idempotent.
     */
    fun setDataSharingOverride(enabled: Boolean) {
        restriction(UserManager.DISALLOW_CONFIG_TETHERING, !enabled)
        prefs.edit().putBoolean(KEY_TETHER_OFFERED, enabled).apply()
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

    /** @return an error text when the system refused, null otherwise. */
    private fun restriction(key: String, on: Boolean): String? {
        val mine = prefs.getStringSet(KEY_RESTRICTIONS, emptySet()).orEmpty()
        return runCatching {
            if (on) {
                handle.dpm.addUserRestriction(handle.admin, key)
                prefs.edit().putStringSet(KEY_RESTRICTIONS, mine + key).apply()
            } else if (key in mine) {
                handle.dpm.clearUserRestriction(handle.admin, key)
                prefs.edit().putStringSet(KEY_RESTRICTIONS, mine - key).apply()
            }
            null
        }.getOrElse { "$key: ${it.javaClass.simpleName}: ${it.message}" }
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
        private const val KEY_FRP_WANTED = "frp_wanted"
        private const val KEY_ERRORS = "errors"
        private const val KEY_MAX_FAILED_POLICY = "max_failed_policy"
        private const val KEY_MAX_FAILED_LOST = "max_failed_lost"
        private const val KEY_MAX_FAILED_SET = "max_failed_set"
        private const val MAX_FAILED_CAP = 100
        private const val MAX_EVENT = 900
    }
}
