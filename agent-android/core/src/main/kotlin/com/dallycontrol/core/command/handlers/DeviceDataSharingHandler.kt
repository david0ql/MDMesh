package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `device.dataSharing` — per-device override of the "share data" (tethering / hotspot) restriction, applied on
 * top of whatever the policy says. `{ enabled: true }` lifts the restriction (sharing allowed), `{ enabled: false }`
 * blocks it. The work itself ([apply], supplied by the app layer) delegates to the same DeviceRules that applies the
 * policy, so the kiosk's "share data" icon stays coherent and the restriction tracking is not duplicated. Idempotent.
 */
class DeviceDataSharingHandler(private val apply: (Boolean) -> Unit) : CommandHandler {

    override val type: String = DeviceAction.DATA_SHARING

    @Serializable
    private data class Payload(val enabled: Boolean? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val enabled = command.payload
            ?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?.enabled
            ?: return CommandResults.failed(command, "device.dataSharing requires { enabled }")
        apply(enabled)
        CommandResults.done(command, if (enabled) "compartir datos: permitido" else "compartir datos: bloqueado")
    }.getOrElse { CommandResults.failed(command, it.message ?: "data sharing override failed") }
}
