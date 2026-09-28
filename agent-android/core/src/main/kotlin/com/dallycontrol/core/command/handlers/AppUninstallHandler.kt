package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.core.install.InstallManager
import com.dallycontrol.core.install.InstallOutcome
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.Serializable

/** `app.uninstall` — silently uninstall a package as Device Owner. Payload: `{ packageName }`. */
class AppUninstallHandler(
    private val installManager: InstallManager,
) : CommandHandler {

    override val type: String = "app.uninstall"

    @Serializable
    private data class Payload(val packageName: String)

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "app.uninstall requires a payload")
        val p = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        return when (val outcome = installManager.uninstall(p.packageName)) {
            InstallOutcome.Success -> CommandResults.done(command)
            is InstallOutcome.Skipped -> CommandResults.done(command, "skipped: ${outcome.reason}")
            is InstallOutcome.Failure -> CommandResults.failed(command, outcome.reason)
        }
    }
}
