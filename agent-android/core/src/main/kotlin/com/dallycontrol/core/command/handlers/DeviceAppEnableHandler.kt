package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `device.appEnable` — re-enable an installed-but-disabled app (a Device-Owner lever that replaces reaching
 * for ADB). Some makers ship apps preloaded as a disabled system package with no launcher entry (e.g. the
 * Microsoft apps on Samsung): they are "installed" yet never show in the kiosk or the launcher. [enable]
 * returns null on success or the reason it could not be made launchable.
 */
class DeviceAppEnableHandler(private val enable: (String) -> String?) : CommandHandler {
    override val type: String = DeviceAction.APP_ENABLE

    @Serializable
    private data class Payload(val packageName: String = "")

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val pkg = command.payload
            ?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?.packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return CommandResults.failed(command, "missing packageName")
        return runCatching { enable(pkg) }.fold(
            onSuccess = { err -> if (err == null) CommandResults.done(command) else CommandResults.failed(command, err) },
            onFailure = { CommandResults.failed(command, it.message ?: "enable failed") },
        )
    }
}
