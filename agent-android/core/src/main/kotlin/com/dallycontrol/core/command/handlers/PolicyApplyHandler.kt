package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.policy.PolicyOutcome
import com.dallycontrol.policy.TogglePolicy
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.ProtocolJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `policy.apply` — applies a single toggle policy to a value. Payload (per
 * `proto/registry.md`): `{ "policy": "wifi", "value": false }`.
 *
 * Routing is fully data-driven: the policy key is looked up in [toggles] (built from the
 * device's supported strategies in the capability-abstraction layer). There is no
 * per-policy `when` — adding a policy means registering its strategy, not editing here.
 * Unknown / unsupported keys degrade to `unsupported`, mirroring the open-registry contract.
 */
class PolicyApplyHandler(
    private val toggles: Map<String, TogglePolicy>,
) : CommandHandler {

    override val type: String = "policy.apply"

    @Serializable
    private data class Payload(
        val policy: String,
        @SerialName("value") val enabled: Boolean,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "policy.apply requires a payload")

        val parsed = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        val toggle = toggles[parsed.policy]
            ?: return CommandResults.unsupported(command, "policy not supported: ${parsed.policy}")

        return when (val outcome = toggle.setEnabled(parsed.enabled)) {
            PolicyOutcome.Applied -> CommandResults.done(command)
            PolicyOutcome.Unsupported -> CommandResults.unsupported(command)
            is PolicyOutcome.Failed -> CommandResults.failed(command, outcome.reason)
        }
    }
}
