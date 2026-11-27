package com.dallycontrol.agent.policy

import android.content.Context
import com.dallycontrol.proto.ConfigPermissions
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What the permission enforcer remembers between runs: the rules last applied, what it fixed per app (so only that
 * is released when a rule goes away), the apps it protected, the last outcome and the report the server already has.
 */
internal class PermissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("mdm_permissions", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val fixedSerializer =
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

    /** The rules last applied; the baseline (grant on request, no per-app rule) before any policy arrived. */
    var policy: ConfigPermissions
        get() = prefs.getString(KEY_POLICY, null)
            ?.let { runCatching { json.decodeFromString(ConfigPermissions.serializer(), it) }.getOrNull() }
            ?: ConfigPermissions()
        set(value) =
            prefs.edit().putString(KEY_POLICY, json.encodeToString(ConfigPermissions.serializer(), value)).apply()

    /** package to (permission to rule) this agent fixed. */
    var fixed: Map<String, Map<String, String>>
        get() = prefs.getString(KEY_FIXED, null)
            ?.let { runCatching { json.decodeFromString(fixedSerializer, it) }.getOrNull() }.orEmpty()
        set(value) = prefs.edit().putString(KEY_FIXED, json.encodeToString(fixedSerializer, value)).apply()

    /** Packages this agent added to the user-control-disabled list (battery protection). */
    var protectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PROTECTED, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_PROTECTED, value).apply()

    /** Outcome of the last full enforcement (a ConfigOutcome string). */
    var outcome: String?
        get() = prefs.getString(KEY_OUTCOME, null)
        set(value) = prefs.edit().putString(KEY_OUTCOME, value).apply()

    val sentSignature: String? get() = prefs.getString(KEY_SENT_SIGNATURE, null)
    val sentAt: Long get() = prefs.getLong(KEY_SENT_AT, 0)

    fun markSent(signature: String, at: Long) {
        prefs.edit().putString(KEY_SENT_SIGNATURE, signature).putLong(KEY_SENT_AT, at).apply()
    }

    fun forgetSent() {
        prefs.edit().remove(KEY_SENT_SIGNATURE).apply()
    }

    private companion object {
        const val KEY_POLICY = "policy"
        const val KEY_FIXED = "fixed"
        const val KEY_OUTCOME = "outcome"
        const val KEY_PROTECTED = "protected"
        const val KEY_SENT_SIGNATURE = "sent_signature"
        const val KEY_SENT_AT = "sent_at"
    }
}
