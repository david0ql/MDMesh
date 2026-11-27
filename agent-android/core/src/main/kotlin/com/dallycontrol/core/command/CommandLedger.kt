package com.dallycontrol.core.command

import com.dallycontrol.proto.CommandResult

/**
 * What this agent already ran, kept across restarts. The server hands a command out again when its answer never
 * arrived (the reply was lost, or the agent died before sending it); the ledger lets the agent answer such a
 * repeat with the result it already has instead of running the command a second time.
 */
interface CommandLedger {
    /** The result recorded for [commandId], or null when the command never ran here. */
    fun find(commandId: String): CommandResult?

    /** Remember the result of a command. Must be durable when it returns. */
    fun record(result: CommandResult)

    /** Drop a command that did not run to the end, so a repeat runs it. */
    fun forget(commandId: String)

    /** Remembers nothing (tests, and callers that do not need the guarantee). */
    object None : CommandLedger {
        override fun find(commandId: String): CommandResult? = null
        override fun record(result: CommandResult) = Unit
        override fun forget(commandId: String) = Unit
    }
}

/**
 * A [CommandLedger] kept as one JSON text in whatever storage the caller hands in ([load]/[save]; the app uses
 * SharedPreferences with a synchronous commit). Holds the last [capacity] results, oldest dropped first: the
 * server stops repeating a command long before that many newer ones have run.
 */
class StoredCommandLedger(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val capacity: Int = DEFAULT_CAPACITY,
) : CommandLedger {

    private val entries: LinkedHashMap<String, CommandResult> by lazy { read() }

    @Synchronized
    override fun find(commandId: String): CommandResult? = entries[commandId]

    @Synchronized
    override fun record(result: CommandResult) {
        entries.remove(result.commandId)
        entries[result.commandId] = result
        while (entries.size > capacity) entries.remove(entries.keys.first())
        write()
    }

    @Synchronized
    override fun forget(commandId: String) {
        if (entries.remove(commandId) != null) write()
    }

    private fun read(): LinkedHashMap<String, CommandResult> {
        val out = LinkedHashMap<String, CommandResult>()
        val raw = runCatching { load() }.getOrNull() ?: return out
        // A damaged file must never stop commands from running: start empty.
        runCatching { com.dallycontrol.proto.ProtocolJson.json.decodeFromString(SERIALIZER, raw) }
            .getOrNull()?.forEach { out[it.commandId] = it }
        return out
    }

    private fun write() {
        runCatching {
            save(com.dallycontrol.proto.ProtocolJson.json.encodeToString(SERIALIZER, entries.values.toList()))
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 200
        val SERIALIZER = kotlinx.serialization.builtins.ListSerializer(CommandResult.serializer())
    }
}
