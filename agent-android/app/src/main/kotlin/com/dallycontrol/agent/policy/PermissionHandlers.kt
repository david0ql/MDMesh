package com.dallycontrol.agent.policy

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.PermissionCatalog
import com.dallycontrol.proto.PermissionReport
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `device.permissionScreen` — `{ packageName, access }`: opens on the phone the exact Android screen where the
 * person turns on a special access for that app (Android gives a Device Owner no API to turn these on). In kiosk
 * the system screen is let through for a few minutes.
 */
class PermissionScreenHandler(private val permissions: AndroidPermissionEnforcer) : CommandHandler {
    override val type: String = DeviceAction.PERMISSION_SCREEN

    @Serializable
    private data class Payload(val packageName: String = "", val access: String = "")

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val p = command.payload
            ?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
        val error = if (p == null || p.packageName.isBlank() || p.access !in PermissionCatalog.SPECIAL) {
            "invalid payload"
        } else {
            runCatching { permissions.openSpecial(p.packageName.trim(), p.access) }
                .getOrElse { it.message ?: "could not open" }
        }
        permissions.invalidateReport()
        return if (error == null) CommandResults.done(command, "pantalla abierta en el teléfono")
        else CommandResults.failed(command, error)
    }
}

/**
 * `device.permissionScan` — enforce the permission rules again and answer with the real state (also sent with the
 * next check-in, where the console reads it).
 */
class PermissionScanHandler(private val permissions: AndroidPermissionEnforcer) : CommandHandler {
    override val type: String = DeviceAction.PERMISSION_SCAN

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        runCatching { permissions.reenforce(null) }
        permissions.invalidateReport()
        val report = permissions.report()
        return CommandResults.done(command, ProtocolJson.json.encodeToString(PermissionReport.serializer(), report))
    }
}
