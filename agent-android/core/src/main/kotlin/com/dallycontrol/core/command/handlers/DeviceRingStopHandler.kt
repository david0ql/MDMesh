package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.action.RingController
import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction

/** `device.ringStop` — silence an active locate tone. */
class DeviceRingStopHandler(
    private val ring: RingController,
) : CommandHandler {

    override val type: String = DeviceAction.RING_STOP

    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        ring.stop()
        CommandResults.done(command)
    }.getOrElse { CommandResults.failed(command, it.message ?: "ring stop failed") }
}
