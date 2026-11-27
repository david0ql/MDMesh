package com.dallycontrol.core.command

import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.CommandStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CommandLedgerTest {

    private var disk: String? = null
    private fun ledger(capacity: Int = 200) = StoredCommandLedger({ disk }, { disk = it }, capacity)

    private class Counting(override val type: String, val result: (CommandEnvelope) -> CommandResult) : CommandHandler {
        val runs = AtomicInteger(0)
        override suspend fun handle(command: CommandEnvelope): CommandResult {
            runs.incrementAndGet()
            return result(command)
        }
    }

    private fun cmd(id: String, type: String) =
        CommandEnvelope(commandId = id, issuedAt = "2026-01-01T00:00:00Z", type = type)

    @Test
    fun aCommandRepeatedAfterTheAgentRestartedIsAnsweredWithoutRunningAgain() = runBlocking {
        val handler = Counting("device.alert") { CommandResults.done(it, "shown") }
        val first = CommandDispatcher(listOf(handler), ledger = ledger()).dispatch(cmd("7", "device.alert"))

        // A new process: nothing in memory, only what the ledger kept.
        val again = CommandDispatcher(listOf(handler), ledger = ledger()).dispatch(cmd("7", "device.alert"))

        assertEquals(1, handler.runs.get())
        assertEquals(CommandStatus.DONE, again.status)
        assertEquals(first.detail, again.detail)
    }

    @Test
    fun aFailureIsRememberedTooSoTheRepeatReportsItInsteadOfRetrying() = runBlocking {
        val handler = Counting("device.passcodeReset") { CommandResults.failed(it, "rejected") }
        CommandDispatcher(listOf(handler), ledger = ledger()).dispatch(cmd("8", "device.passcodeReset"))
        val again = CommandDispatcher(listOf(handler), ledger = ledger()).dispatch(cmd("8", "device.passcodeReset"))
        assertEquals(1, handler.runs.get())
        assertEquals(CommandStatus.FAILED, again.status)
        assertEquals("rejected", again.detail)
    }

    @Test
    fun aRebootIsWrittenDownBeforeItRunsBecauseItsAnswerNeverLeaves() = runBlocking {
        val handler = object : CommandHandler {
            override val type = "device.reboot"
            var runs = 0
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                runs++
                error("the device went down here")
            }
        }
        // The process dies inside the handler: simulate it by reading the ledger while the handler runs.
        val live = ledger()
        val seenWhileRunning = arrayOfNulls<CommandResult>(1)
        val probing = object : CommandHandler {
            override val type = "device.reboot"
            override suspend fun handle(command: CommandEnvelope): CommandResult {
                seenWhileRunning[0] = ledger().find(command.commandId)
                return CommandResults.done(command, "rebooting")
            }
        }
        CommandDispatcher(listOf(probing), ledger = live).dispatch(cmd("9", "device.reboot"))
        assertEquals(CommandStatus.DONE, seenWhileRunning[0]?.status)

        // After the reboot the server repeats it: answered from the ledger, the device does not reboot again.
        val again = CommandDispatcher(listOf(handler), ledger = ledger()).dispatch(cmd("9", "device.reboot"))
        assertEquals(0, handler.runs)
        assertEquals(CommandStatus.DONE, again.status)
    }

    @Test
    fun aCommandThatMustWaitIsForgottenSoItsRepeatRuns() = runBlocking {
        var online = false
        val handler = Counting("app.install") {
            if (!online) throw RetryLaterException("no internet")
            CommandResults.done(it)
        }
        runCatching { CommandDispatcher(listOf(handler), ledger = ledger()).dispatch(cmd("10", "app.install")) }
        assertNull(ledger().find("10"))
        online = true
        val again = CommandDispatcher(listOf(handler), ledger = ledger()).dispatch(cmd("10", "app.install"))
        assertEquals(2, handler.runs.get())
        assertEquals(CommandStatus.DONE, again.status)
    }

    @Test
    fun keepsOnlyTheNewestResultsAndSurvivesADamagedFile() {
        val small = ledger(capacity = 2)
        listOf("1", "2", "3").forEach { small.record(CommandResults.done(cmd(it, "x"))) }
        assertNull(ledger(2).find("1"))
        assertEquals("3", ledger(2).find("3")?.commandId)

        disk = "{not json"
        assertNull(ledger().find("3"))
        ledger().record(CommandResults.done(cmd("4", "x")))
        assertEquals("4", ledger().find("4")?.commandId)
    }
}
