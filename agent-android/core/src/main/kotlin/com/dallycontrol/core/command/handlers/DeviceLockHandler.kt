package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult

/** `device.lock` — immediately lock the screen ([android.app.admin.DevicePolicyManager.lockNow]). */
class DeviceLockHandler(
    private val handle: DpmHandle,
) : CommandHandler {

    override val type: String = "device.lock"

    override suspend fun handle(command: CommandEnvelope): CommandResult =
        runCatching {
            handle.dpm.lockNow()
            CommandResults.done(command)
        }.getOrElse { CommandResults.failed(command, it.message ?: "lock failed") }
}
