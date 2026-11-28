package com.dallycontrol.agent.policy

import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import com.dallycontrol.policy.frp.FactoryResetProtection
import com.dallycontrol.policy.frp.FrpPlatform
import com.dallycontrol.policy.frp.FrpPolicyState
import com.dallycontrol.policy.frp.TamperRestrictions
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigDevice
import com.dallycontrol.proto.FrpStatus
import com.dallycontrol.proto.ProtectionStatus

/**
 * User restrictions set by the device rules, remembered so that only what the rules set is ever lifted (a
 * restriction somebody else put there stays).
 */
class TrackedRestrictions(private val prefs: SharedPreferences, private val handle: DpmHandle) {

    /** @return an error text when the system refused, null otherwise. */
    fun set(key: String, on: Boolean): String? {
        val mine = held()
        return runCatching {
            if (on) {
                handle.dpm.addUserRestriction(handle.admin, key)
                prefs.edit().putStringSet(KEY, mine + key).apply()
            } else if (key in mine) {
                handle.dpm.clearUserRestriction(handle.admin, key)
                prefs.edit().putStringSet(KEY, mine - key).apply()
            }
            null
        }.getOrElse { "$key: ${it.javaClass.simpleName}: ${it.message}" }
    }

    fun held(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty()

    private companion object {
        const val KEY = "restrictions"
    }
}

/**
 * Protection of the device against a factory reset and the other ways around the management:
 *  - reset from Settings, safe mode, USB debugging and bootloader unlocking blocked ([TamperRestrictions]);
 *  - the company Google accounts that may set the phone up again after a reset done from recovery
 *    ([FactoryResetProtection]: Android 11+ by policy, Android 5.1-10 through Google Play services);
 *  - wipe after N wrong unlock attempts (the policy's value, or lost mode's when stricter).
 * Nothing fails in silence: [apply] returns what the system refused, and [status] reads back from the system what
 * the device really holds, for the server.
 */
class DeviceProtection(private val context: Context, private val handle: DpmHandle) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val restrictions = TrackedRestrictions(prefs, handle)
    private val frp = FactoryResetProtection(AndroidFrp())

    /** @return what could not be applied (empty = everything is in place). */
    fun apply(rules: ConfigDevice?): List<String> {
        val errors = ArrayList<String>()
        applyTamperRules(rules, errors)
        // Company factory-reset protection. "This device cannot" is reported by status(), not as a failed policy.
        val accounts = FactoryResetProtection.clean(rules?.frpAccounts.orEmpty())
        val frpNow = frp.apply(accounts, hadBefore = prefs.getBoolean(KEY_FRP, false))
        if (FactoryResetProtection.isFailure(frpNow)) {
            errors.add("frp: ${frpNow.error ?: "error"}")
        } else {
            prefs.edit().putBoolean(KEY_FRP, frpNow.active).apply()
        }
        val attempts = (rules?.maxFailedPasswordsForWipe ?: 0).coerceIn(0, MAX_FAILED_CAP)
        prefs.edit().putStringSet(KEY_FRP_WANTED, accounts.toSet()).putInt(KEY_MAX_FAILED_POLICY, attempts).apply()
        applyMaxFailed()?.let(errors::add)
        prefs.edit().putString(KEY_ERRORS, errors.joinToString("\n")).apply()
        return errors
    }

    /** Reset from Settings, safe mode, USB debugging, bootloader unlock. */
    private fun applyTamperRules(rules: ConfigDevice?, errors: MutableList<String>) {
        val plan = TamperRestrictions.plan(
            mapOf(
                TamperRestrictions.FACTORY_RESET to (rules?.factoryReset == BLOCK),
                TamperRestrictions.SAFE_BOOT to (rules?.safeBoot == BLOCK),
                TamperRestrictions.DEBUGGING to (rules?.debugging == BLOCK),
                TamperRestrictions.OEM_UNLOCK to (rules?.oemUnlock == BLOCK),
            ),
            Build.VERSION.SDK_INT,
        )
        for (key in TamperRestrictions.ALL) {
            val wanted = key in plan.restrictions
            // The kiosk's anti-theft PIN holds "no safe mode" on its own account: never lift it from here.
            val keptByAntiTheft = key == UserManager.DISALLOW_SAFE_BOOT && !wanted && antiTheftHoldsSafeBoot()
            if (!keptByAntiTheft) restrictions.set(key, wanted)?.let(errors::add)
        }
        plan.unsupported.forEach { errors.add("$it: not available on Android ${Build.VERSION.RELEASE}") }
    }

    /**
     * Lost mode asks for a wipe after [attempts] wrong unlocks (0 = no longer asks). The stricter of this and the
     * policy's own value is what the device holds. Throws when the system refuses.
     */
    fun setLostMaxFailed(attempts: Int) {
        prefs.edit().putInt(KEY_MAX_FAILED_LOST, attempts.coerceIn(0, MAX_FAILED_CAP)).apply()
        applyMaxFailed()?.let { error(it) }
    }

    /** @return an error text, or null when the device holds the wanted value. */
    private fun applyMaxFailed(): String? {
        val asked = listOf(prefs.getInt(KEY_MAX_FAILED_POLICY, 0), prefs.getInt(KEY_MAX_FAILED_LOST, 0))
        val wanted = asked.filter { it > 0 }.minOrNull() ?: 0
        val nothingToDo = wanted == 0 && !prefs.getBoolean(KEY_MAX_FAILED_SET, false)
        return if (nothingToDo) null else runCatching {
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
            FactoryResetProtection.mechanismFor(Build.VERSION.SDK_INT),
            active = false,
            reason = FactoryResetProtection.REASON_NOT_DEVICE_OWNER,
        )
        val held: Set<String> = runCatching {
            if (owner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val b = dpm.getUserRestrictions(handle.admin)
                b.keySet().filter { b.getBoolean(it) }.toSet()
            } else {
                restrictions.held()
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
            val supported = DevicePolicyManager::class.java.getMethod("isFactoryResetProtectionPolicySupported")
            supported.invoke(handle.dpm) as Boolean
        }.getOrDefault(true)

        override fun setPolicy(accounts: List<String>?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val policy = accounts?.let {
                FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionAccounts(it)
                    .setFactoryResetProtectionEnabled(true)
                    .build()
            }
            handle.dpm.setFactoryResetProtectionPolicy(handle.admin, policy)
        }

        override fun readPolicy(): FrpPolicyState? =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                null
            } else {
                handle.dpm.getFactoryResetProtectionPolicy(handle.admin)
                    ?.let { FrpPolicyState(it.factoryResetProtectionAccounts, it.isFactoryResetProtectionEnabled) }
            }

        override fun gmsInstalled(): Boolean = runCatching {
            context.packageManager.getPackageInfo(FactoryResetProtection.GMS_PACKAGE, 0)
            true
        }.getOrDefault(false)

        override fun setGmsAccounts(accounts: List<String>?) {
            val b = handle.dpm.getApplicationRestrictions(handle.admin, FactoryResetProtection.GMS_PACKAGE)
            if (accounts == null) {
                b.remove(FactoryResetProtection.GMS_RESTRICTION)
            } else {
                b.putStringArray(FactoryResetProtection.GMS_RESTRICTION, accounts.toTypedArray())
            }
            handle.dpm.setApplicationRestrictions(handle.admin, FactoryResetProtection.GMS_PACKAGE, b)
        }

        override fun readGmsAccounts(): List<String> =
            handle.dpm.getApplicationRestrictions(handle.admin, FactoryResetProtection.GMS_PACKAGE)
                .getStringArray(FactoryResetProtection.GMS_RESTRICTION)?.toList().orEmpty()

        override fun notifyGms() {
            val changed = Intent(FactoryResetProtection.GMS_BROADCAST).setPackage(FactoryResetProtection.GMS_PACKAGE)
            context.sendBroadcast(changed)
        }
    }

    private companion object {
        /** Shared with [DeviceRules]: the restrictions it set before this class existed are tracked there too. */
        const val PREFS = "mdm_device_rules"
        const val BLOCK = "block"
        const val KEY_FRP = "frp"
        const val KEY_FRP_WANTED = "frp_wanted"
        const val KEY_ERRORS = "errors"
        const val KEY_MAX_FAILED_POLICY = "max_failed_policy"
        const val KEY_MAX_FAILED_LOST = "max_failed_lost"
        const val KEY_MAX_FAILED_SET = "max_failed_set"
        const val MAX_FAILED_CAP = 100
    }
}
