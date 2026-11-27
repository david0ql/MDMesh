package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.core.lost.LostMode
import com.dallycontrol.core.lost.LostModePayload
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson

/** `device.lostMode` — start or end lost mode (see [LostMode]). A step that fails is named in the result. */
class DeviceLostModeHandler(
    private val lostMode: LostMode,
) : CommandHandler {

    override val type: String = DeviceAction.LOST_MODE

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = runCatching {
            command.payload?.let { ProtocolJson.json.decodeFromJsonElement(LostModePayload.serializer(), it) }
        }.getOrElse { return CommandResults.failed(command, "invalid payload: ${it.message}") } ?: LostModePayload()
        val errors = runCatching { lostMode.apply(payload) }
            .getOrElse { return CommandResults.failed(command, "lost mode: ${it.message ?: it.javaClass.simpleName}") }
        val what = if (payload.enabled) "lost mode on" else "lost mode off"
        return if (errors.isEmpty()) CommandResults.done(command, what)
        else CommandResults.failed(command, "$what, with errors: ${errors.joinToString("; ")}")
    }
}
