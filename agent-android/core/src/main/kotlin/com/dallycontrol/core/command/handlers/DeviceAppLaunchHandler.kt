package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `device.appLaunch` — bring an installed app to the foreground (remote support, "scripts"). [launch] returns null
 * on success or the reason it could not start (not installed, no launcher entry, blocked by kiosk).
 */
class DeviceAppLaunchHandler(private val launch: (String) -> String?) : CommandHandler {
    override val type: String = DeviceAction.APP_LAUNCH

    @Serializable
    private data class Payload(val packageName: String = "")

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val pkg = command.payload
            ?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?.packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return CommandResults.failed(command, "missing packageName")
        return runCatching { launch(pkg) }.fold(
            onSuccess = { err -> if (err == null) CommandResults.done(command) else CommandResults.failed(command, err) },
            onFailure = { CommandResults.failed(command, it.message ?: "launch failed") },
        )
    }
}
