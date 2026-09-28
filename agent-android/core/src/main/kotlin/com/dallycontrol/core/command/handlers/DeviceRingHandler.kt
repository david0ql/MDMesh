package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.action.RingController
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `device.ring` — play a loud locate tone for `durationMs` (default 30s). */
class DeviceRingHandler(
    private val ring: RingController,
) : CommandHandler {

    override val type: String = DeviceAction.RING

    @Serializable
    private data class Payload(val durationMs: Long? = null)

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val dur = command.payload
            ?.let { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }
            ?.durationMs ?: 30_000L
        ring.start(dur)
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "ring failed") }
}
