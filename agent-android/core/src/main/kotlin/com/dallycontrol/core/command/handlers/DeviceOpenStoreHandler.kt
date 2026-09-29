package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/**
 * `device.openStore` — open an app's Play Store page on the phone (a Play Store app cannot be installed silently
 * without Google's managed Play; the person taps Install). [open] returns null on success or why it could not.
 */
class DeviceOpenStoreHandler(private val open: (String) -> String?) : CommandHandler {
    override val type: String = DeviceAction.OPEN_STORE

    @Serializable
    private data class Payload(val packageName: String = "")

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val pkg = command.payload
            ?.let { runCatching { ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), it) }.getOrNull() }
            ?.packageName?.trim().orEmpty()
        if (!Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(pkg)) return CommandResults.failed(command, "invalid packageName")
        return runCatching { open(pkg) }.fold(
            onSuccess = { err -> if (err == null) CommandResults.done(command) else CommandResults.failed(command, err) },
            onFailure = { CommandResults.failed(command, it.message ?: "could not open the store") },
        )
    }
}
