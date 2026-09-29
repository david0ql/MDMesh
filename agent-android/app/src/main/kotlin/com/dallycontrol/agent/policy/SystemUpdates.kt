package com.dallycontrol.agent.policy

import android.app.admin.SystemUpdatePolicy
import android.content.Context
import android.os.Build
import com.dallycontrol.core.telemetry.EventSink
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.ConfigSystemUpdate
import com.dallycontrol.proto.EventType

/**
 * Android system (OTA) updates as the Device Owner: the configuration's policy (install automatically, inside a daily
 * window, or postpone 30 days), what is pending, and the update trail (an event when Android or its security patch
 * changed, and when an update started waiting). Manufacturers apply the policy to their OTA client; most do (Pixel,
 * Motorola, Samsung, Xiaomi on recent Android).
 */
class SystemUpdates(private val context: Context, private val dpm: DpmHandle, private val events: EventSink) {
    private val prefs = context.getSharedPreferences("mdm_system_update", Context.MODE_PRIVATE)

    fun apply(policy: ConfigSystemUpdate?): String {
        val p = when (policy?.type) {
            null -> null
            "automatic" -> SystemUpdatePolicy.createAutomaticInstallPolicy()
            "postpone" -> SystemUpdatePolicy.createPostponeInstallPolicy()
            "windowed" -> {
                val from = policy.fromMinutes ?: return ConfigOutcome.failed("window without start")
                val to = policy.toMinutes ?: return ConfigOutcome.failed("window without end")
                SystemUpdatePolicy.createWindowedInstallPolicy(from, to)
            }
            else -> return ConfigOutcome.UNSUPPORTED
        }
        dpm.dpm.setSystemUpdatePolicy(dpm.admin, p)
        return ConfigOutcome.APPLIED
    }

    /** When a pending system update was first seen (records one event per update). Null = none pending. */
    fun pendingSince(): Long? {
        if (Build.VERSION.SDK_INT < 26) return null
        val info = runCatching { dpm.dpm.getPendingSystemUpdate(dpm.admin) }.getOrNull() ?: run {
            prefs.edit().remove(KEY_PENDING).apply()
            return null
        }
        val at = info.receivedTime
        if (prefs.getLong(KEY_PENDING, 0L) != at) {
            prefs.edit().putLong(KEY_PENDING, at).apply()
            events.record(EventType.SYSTEM_UPDATE_PENDING, if (info.securityPatchState == 1) "security patch" else "system update")
        }
        return at
    }

    /** Record Android / security-patch changes since the last start (the update trail). */
    fun recordIfUpdated() {
        val now = "${Build.VERSION.RELEASE} (${if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "?"})"
        val before = prefs.getString(KEY_VERSION, null)
        if (before != null && before != now) events.record(EventType.SYSTEM_UPDATED, "$before -> $now")
        if (before != now) prefs.edit().putString(KEY_VERSION, now).apply()
    }

    private companion object {
        const val KEY_PENDING = "pending_at"
        const val KEY_VERSION = "version"
    }
}

/**
 * `device.systemUpdate` — set the Android update policy right away (from the console, for one device or a whole
 * folder): `automatic` installs an available update as soon as it is there. `default` clears it. A policy that
 * defines its own system-update setting takes over again on its next apply.
 */
class SystemUpdateHandler(private val updates: SystemUpdates) : com.dallycontrol.core.command.CommandHandler {
    override val type: String = com.dallycontrol.proto.DeviceAction.SYSTEM_UPDATE

    @kotlinx.serialization.Serializable
    private data class Payload(val type: String = "automatic", val fromMinutes: Int? = null, val toMinutes: Int? = null)

    override suspend fun handle(command: com.dallycontrol.proto.CommandEnvelope): com.dallycontrol.proto.CommandResult {
        val p = command.payload?.let { runCatching { com.dallycontrol.proto.ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?: Payload()
        val policy = if (p.type == "default") null else ConfigSystemUpdate(p.type, p.fromMinutes, p.toMinutes)
        val outcome = runCatching { updates.apply(policy) }.getOrElse { return com.dallycontrol.core.command.CommandResults.failed(command, it.message ?: "failed") }
        val pending = runCatching { updates.pendingSince() }.getOrNull()
        return if (outcome == ConfigOutcome.APPLIED) {
            com.dallycontrol.core.command.CommandResults.done(command, if (pending != null) "policy set; an update is waiting and will install" else "policy set; no update pending right now")
        } else {
            com.dallycontrol.core.command.CommandResults.failed(command, outcome)
        }
    }
}
