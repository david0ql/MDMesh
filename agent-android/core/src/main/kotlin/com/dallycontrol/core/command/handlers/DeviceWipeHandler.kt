package com.dallycontrol.core.command.handlers

import com.dallycontrol.core.command.CommandHandler
import com.dallycontrol.core.command.CommandResults
import com.dallycontrol.core.command.wipe.WipeJournal
import com.dallycontrol.core.command.wipe.WipePayload
import com.dallycontrol.core.command.wipe.WipePlanner
import com.dallycontrol.core.command.wipe.WipeRunner
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.DeviceAction
import com.dallycontrol.proto.ProtocolJson

/**
 * `device.wipe` — factory-reset the whole device. Irreversible. Gated by the `device.wipe` capability token AND the
 * console's type-to-confirm.
 *
 * The options of the payload ([WipePayload]: SD card, factory-reset protection kept or removed, eSIM, reason shown,
 * silent) become platform flags per Android version ([WipePlanner]).
 *
 * Evidence: a wiped phone cannot answer, so the agent tells the server BEFORE wiping. The order is written to disk
 * ([WipeJournal]), announced ([announce]: an `accepted` result whose detail starts with [STARTING], sent on its own
 * check-in with a short retry) and only then run. The wipe never waits for the network beyond that retry: a phone
 * being wiped because it was stolen must wipe even if the link drops. If the platform refuses, the order is
 * forgotten and the result is `failed` with the reason.
 */
class DeviceWipeHandler(
    private val sdkInt: Int,
    private val runner: WipeRunner,
    private val journal: WipeJournal,
    private val announce: suspend (CommandResult) -> Boolean = { false },
) : CommandHandler {

    override val type: String = DeviceAction.WIPE

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = runCatching {
            command.payload?.let { ProtocolJson.json.decodeFromJsonElement(WipePayload.serializer(), it) }
        }.getOrNull() ?: WipePayload()
        val plan = WipePlanner.plan(sdkInt, payload)
        val summary = plan.describe()
        runCatching { journal.begin(command.commandId, plan) }
        val told = runCatching { announce(CommandResults.accepted(command, "$STARTING $summary")) }.getOrDefault(false)
        return runCatching {
            runner.run(plan)
            CommandResults.done(command, "wipe requested: $summary${if (told) "" else " (not announced: no link)"}")
        }.getOrElse {
            runCatching { journal.clear() }
            val why = "${it.javaClass.simpleName}: ${it.message ?: "no message"}"
            CommandResults.failed(command, "wipe failed: $why ($summary)")
        }
    }

    companion object {
        /** Prefix of the pre-wipe announcement's detail; the server reads it to mark the device as wiped. */
        const val STARTING = "wipe:starting"
    }
}
