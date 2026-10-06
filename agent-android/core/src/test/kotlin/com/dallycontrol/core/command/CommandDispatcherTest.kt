package com.dallycontrol.core.command

import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandDispatcherTest {

    private fun envelope(
        type: String,
        ttlSeconds: Int? = null,
        issuedAt: String = "2026-01-01T00:00:00Z",
    ) = CommandEnvelope(
        commandId = "cmd-1",
        issuedAt = issuedAt,
        ttlSeconds = ttlSeconds,
        type = type,
    )

    private class EchoHandler(override val type: String) : CommandHandler {
        override suspend fun handle(command: CommandEnvelope): CommandResult =
            CommandResults.done(command)
    }

    @Test
    fun `unknown command type returns unsupported`() = runTest {
        val dispatcher = CommandDispatcher(handlers = emptyList())

        val result = dispatcher.dispatch(envelope("totally.unknown"))

        assertEquals(CommandStatus.UNSUPPORTED, result.status)
        assertEquals("cmd-1", result.commandId)
    }

    @Test
    fun `known command type is routed to its handler`() = runTest {
        val dispatcher = CommandDispatcher(handlers = listOf(EchoHandler("config.sync")))

        val result = dispatcher.dispatch(envelope("config.sync"))

        assertEquals(CommandStatus.DONE, result.status)
    }

    @Test
    fun `expired command returns expired`() = runTest {
        // issued at epoch 0, ttl 60s, but "now" is far in the future.
        val dispatcher = CommandDispatcher(
            handlers = listOf(EchoHandler("config.sync")),
            nowEpochSeconds = { 10_000_000_000L },
        )

        val result = dispatcher.dispatch(
            envelope("config.sync", ttlSeconds = 60, issuedAt = "1970-01-01T00:00:00Z"),
        )

        assertEquals(CommandStatus.EXPIRED, result.status)
    }

    @Test
    fun `handler exception becomes failed`() = runTest {
        val throwing = object : CommandHandler {
            override val type = "boom"
            override suspend fun handle(command: CommandEnvelope): CommandResult =
                throw IllegalStateException("kaboom")
        }
        val dispatcher = CommandDispatcher(handlers = listOf(throwing))

        val result = dispatcher.dispatch(envelope("boom"))

        assertEquals(CommandStatus.FAILED, result.status)
        assertEquals("kaboom", result.detail)
    }

    @Test
    fun `a cancelled handler is not reported as failed`() = runTest {
        val cancelled = object : CommandHandler {
            override val type = "app.install"
            override suspend fun handle(command: CommandEnvelope): CommandResult =
                throw kotlinx.coroutines.CancellationException("Job was cancelled")
        }
        val dispatcher = CommandDispatcher(handlers = listOf(cancelled))

        val thrown = runCatching { dispatcher.dispatch(envelope("app.install")) }.exceptionOrNull()

        org.junit.Assert.assertTrue(thrown is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `a handler error is still a failed result`() = runTest {
        val broken = object : CommandHandler {
            override val type = "app.install"
            override suspend fun handle(command: CommandEnvelope): CommandResult = error("boom")
        }
        val result = CommandDispatcher(handlers = listOf(broken)).dispatch(envelope("app.install"))

        assertEquals(CommandStatus.FAILED, result.status)
    }

    @Test
    fun `a command that must wait is left unanswered`() = runTest {
        val offline = object : CommandHandler {
            override val type = "app.install"
            override suspend fun handle(command: CommandEnvelope): CommandResult =
                throw RetryLaterException("apk fetch: Unable to resolve host")
        }
        val thrown = runCatching { CommandDispatcher(handlers = listOf(offline)).dispatch(envelope("app.install")) }.exceptionOrNull()

        org.junit.Assert.assertTrue(thrown is RetryLaterException)
    }
}
