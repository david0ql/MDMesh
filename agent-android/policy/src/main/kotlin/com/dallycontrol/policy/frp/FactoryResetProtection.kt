package com.dallycontrol.policy.frp

import com.dallycontrol.proto.FrpStatus

/**
 * What the device offers for factory-reset protection (FRP). The Android calls live behind this seam so the rules
 * by Android version ([FactoryResetProtection]) run as a plain JVM test.
 */
interface FrpPlatform {
    val sdkInt: Int

    /**
     * Android 11+: the device has a place to keep the FRP policy. The platform's own check is not a public API, so
     * an implementation that cannot ask answers true and lets [setPolicy] / [readPolicy] throw
     * UnsupportedOperationException, which is read the same way.
     */
    fun policySupported(): Boolean

    /** Android 11+: set the accounts that may unlock the device after a reset from recovery; null removes the policy. */
    fun setPolicy(accounts: List<String>?)

    /** Android 11+: the policy the device holds, or null when it has none. */
    fun readPolicy(): FrpPolicyState?

    /** Google Play services is installed (it is what enforces FRP before Android 11). */
    fun gmsInstalled(): Boolean

    /** Android 5.1-10: write (null = remove) `factoryResetProtectionAdmin` in Google Play services' restrictions. */
    fun setGmsAccounts(accounts: List<String>?)

    /** Android 5.1-10: the accounts Google Play services' restrictions hold now. */
    fun readGmsAccounts(): List<String>

    /** Android 5.1-10: tell Google Play services its FRP configuration changed. */
    fun notifyGms()
}

data class FrpPolicyState(val accounts: List<String>, val enabled: Boolean)

/**
 * Company factory-reset protection: after a reset from recovery (hardware buttons) the phone only finishes its
 * setup with one of the company's Google accounts, so it is of no use to anyone else.
 *
 *  - Android 11+ (API 30): `setFactoryResetProtectionPolicy` with the account ids and protection enabled.
 *  - Android 5.1-10 (API 22-29): the `factoryResetProtectionAdmin` restriction of Google Play services, followed by
 *    the `FRP_CONFIG_CHANGED` broadcast.
 *  - Older: not available.
 *
 * Nothing is taken on trust: after writing, the value is read back and compared; whatever went wrong comes back in
 * the [FrpStatus] (reason + platform message) instead of being swallowed.
 */
class FactoryResetProtection(private val platform: FrpPlatform) {

    /**
     * Make the device hold exactly [wanted] (account ids; empty = no company FRP). [hadBefore]: these rules set FRP
     * on an earlier run, so an empty list must remove it (and only then: FRP nobody here set is left alone).
     */
    fun apply(wanted: List<String>, hadBefore: Boolean): FrpStatus {
        val ids = clean(wanted)
        val mechanism = mechanismFor(platform.sdkInt)
        if (mechanism == MECHANISM_NONE || (ids.isEmpty() && !hadBefore)) return status(ids)
        val written = runCatching { write(mechanism, ids) }.getOrElse { return refused(mechanism, ids, it) }
        if (written != null) return written
        val now = status(ids)
        // Written without an error but the device does not hold it: say so instead of reporting "applied".
        if (ids.isNotEmpty() && !now.active && now.reason == REASON_NO_ACCOUNTS) {
            return now.copy(reason = REASON_ERROR, error = "the device did not keep the accounts")
        }
        return now
    }

    /** @return a final status when nothing could be written (the device cannot), or null once written. */
    private fun write(mechanism: String, ids: List<String>): FrpStatus? {
        if (mechanism == MECHANISM_POLICY) {
            if (!platform.policySupported()) return unavailable(mechanism, ids, REASON_UNSUPPORTED)
            platform.setPolicy(ids.ifEmpty { null })
            return null
        }
        if (!platform.gmsInstalled()) return unavailable(mechanism, ids, REASON_GMS_MISSING)
        platform.setGmsAccounts(ids.ifEmpty { null })
        platform.notifyGms()
        return null
    }

    /**
     * What the device holds now. With [wanted] (non-empty), accounts that differ from it are reported as an error:
     * the protection is there but not the one the policy asks for.
     */
    fun status(wanted: List<String> = emptyList()): FrpStatus {
        val ids = clean(wanted)
        val mechanism = mechanismFor(platform.sdkInt)
        if (mechanism == MECHANISM_NONE) return unavailable(mechanism, ids, REASON_ANDROID_TOO_OLD)
        val held = runCatching { read(mechanism) }.getOrElse { return refused(mechanism, ids, it) }
            ?: return unavailable(mechanism, ids, if (mechanism == MECHANISM_POLICY) REASON_UNSUPPORTED else REASON_GMS_MISSING)
        val accounts = clean(held.accounts)
        return when {
            accounts.isEmpty() -> FrpStatus(mechanism, active = false, reason = REASON_NO_ACCOUNTS)
            !held.enabled -> FrpStatus(
                mechanism, active = false, accounts = accounts, reason = REASON_ERROR, error = "the policy is held but not enabled",
            )
            ids.isNotEmpty() && accounts.toSet() != ids.toSet() -> FrpStatus(
                mechanism, active = true, accounts = accounts, reason = REASON_ERROR,
                error = "the device holds other accounts than the policy's",
            )
            else -> FrpStatus(mechanism, active = true, accounts = accounts)
        }
    }

    /** @return what the device holds (no accounts = nothing), or null when this device has no FRP to read. */
    private fun read(mechanism: String): FrpPolicyState? {
        if (mechanism == MECHANISM_POLICY) {
            if (!platform.policySupported()) return null
            return platform.readPolicy() ?: FrpPolicyState(emptyList(), false)
        }
        if (!platform.gmsInstalled()) return null
        val accounts = platform.readGmsAccounts()
        return FrpPolicyState(accounts, accounts.isNotEmpty())
    }

    private fun unavailable(mechanism: String, ids: List<String>, reason: String): FrpStatus =
        FrpStatus(mechanism, active = false, reason = if (ids.isEmpty()) REASON_NO_ACCOUNTS else reason)

    /**
     * The platform threw. UnsupportedOperationException is how Android says "this device has nowhere to keep an FRP
     * policy" (no persistent data block: some tablets, emulators, AOSP builds) — that is the device, not a failure.
     */
    private fun refused(mechanism: String, ids: List<String>, t: Throwable): FrpStatus =
        if (t is UnsupportedOperationException) unavailable(mechanism, ids, REASON_UNSUPPORTED).copy(error = t.message)
        else failed(mechanism, t)

    private fun failed(mechanism: String, t: Throwable): FrpStatus = FrpStatus(
        mechanism, active = false, reason = REASON_ERROR, error = "${t.javaClass.simpleName}: ${t.message ?: "no message"}",
    )

    companion object {
        const val MECHANISM_POLICY = "policy"
        const val MECHANISM_GMS = "gms"
        const val MECHANISM_NONE = "none"

        const val REASON_NO_ACCOUNTS = "noAccounts"
        const val REASON_UNSUPPORTED = "unsupported"
        const val REASON_GMS_MISSING = "gmsMissing"
        const val REASON_ANDROID_TOO_OLD = "androidTooOld"
        const val REASON_NOT_DEVICE_OWNER = "notDeviceOwner"
        const val REASON_ERROR = "error"

        /** Google Play services: the package that enforces FRP, and its restriction / broadcast before Android 11. */
        const val GMS_PACKAGE = "com.google.android.gms"
        const val GMS_RESTRICTION = "factoryResetProtectionAdmin"
        const val GMS_BROADCAST = "com.google.android.gms.auth.FRP_CONFIG_CHANGED"

        private const val API_POLICY = 30
        private const val API_GMS = 22

        fun mechanismFor(sdkInt: Int): String = when {
            sdkInt >= API_POLICY -> MECHANISM_POLICY
            sdkInt >= API_GMS -> MECHANISM_GMS
            else -> MECHANISM_NONE
        }

        /** Google account ids are digits only; anything else (an e-mail typed by mistake) is dropped. */
        fun clean(accounts: List<String>): List<String> =
            accounts.map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.distinct()

        /** A real failure (as opposed to "nothing to protect" or "this device cannot"): the policy did not apply. */
        fun isFailure(status: FrpStatus): Boolean = status.reason == REASON_ERROR
    }
}
