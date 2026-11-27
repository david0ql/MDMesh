package com.dallycontrol.core.command

import com.dallycontrol.core.command.handlers.DeviceWipeHandler
import com.dallycontrol.core.command.wipe.WipeCall
import com.dallycontrol.core.command.wipe.WipeExecutor
import com.dallycontrol.core.command.wipe.WipeJournal
import com.dallycontrol.core.command.wipe.WipePayload
import com.dallycontrol.core.command.wipe.WipePlanner
import com.dallycontrol.core.command.wipe.WipeRunner
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandResult
import com.dallycontrol.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceWipeHandlerTest {

    private class FakeExecutor(var fail: Throwable? = null) : WipeExecutor {
        val calls = ArrayList<String>()
        override fun wipeDevice(flags: Int) { calls.add("wipeDevice($flags)"); fail?.let { throw it } }
        override fun wipeData(flags: Int) { calls.add("wipeData($flags)"); fail?.let { throw it } }
        override fun wipeData(flags: Int, reason: String) { calls.add("wipeData($flags,$reason)"); fail?.let { throw it } }
    }

    private class Disk {
        var value: String? = null
        fun journal() = WipeJournal({ value }, { value = it })
    }

    private fun command(vararg payload: Pair<String, Any>) = CommandEnvelope(
        commandId = "77",
        issuedAt = "2026-11-27T10:00:00Z",
        type = "device.wipe",
        payload = if (payload.isEmpty()) null else JsonObject(
            payload.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
        ),
    )

    // --- flags per option and Android version ---

    @Test
    fun `no options wipes with no flags, as before`() {
        assertEquals(0, WipePlanner.plan(33, WipePayload()).flags)
        assertEquals(WipeCall.WIPE_DATA, WipePlanner.plan(33, WipePayload()).call)
        assertEquals(WipeCall.WIPE_DEVICE, WipePlanner.plan(34, WipePayload()).call)
    }

    @Test
    fun `the flag values are android's`() {
        assertEquals(1, WipePlanner.WIPE_EXTERNAL_STORAGE)
        assertEquals(2, WipePlanner.WIPE_RESET_PROTECTION_DATA)
        assertEquals(4, WipePlanner.WIPE_EUICC)
        assertEquals(8, WipePlanner.WIPE_SILENTLY)
    }

    @Test
    fun `lost or stolen keeps the reset protection, reassign removes it`() {
        assertEquals(0, WipePlanner.plan(34, WipePayload(intent = "lost")).flags)
        assertEquals(2, WipePlanner.plan(34, WipePayload(intent = "reassign")).flags)
        // An explicit choice wins over the intent.
        assertEquals(2, WipePlanner.plan(34, WipePayload(intent = "lost", keepFrp = false)).flags)
        assertEquals(0, WipePlanner.plan(34, WipePayload(intent = "reassign", keepFrp = true)).flags)
    }

    @Test
    fun `sd card, esim and silent each add their flag`() {
        assertEquals(1, WipePlanner.plan(34, WipePayload(externalStorage = true)).flags)
        assertEquals(4, WipePlanner.plan(34, WipePayload(euicc = true)).flags)
        assertEquals(8, WipePlanner.plan(34, WipePayload(silent = true)).flags)
        assertEquals(15, WipePlanner.plan(36, WipePayload(intent = "reassign", externalStorage = true, euicc = true, silent = true)).flags)
    }

    @Test
    fun `android 14 and later call wipeDevice, which shows no reason`() {
        for (sdk in listOf(34, 35, 36)) {
            val plan = WipePlanner.plan(sdk, WipePayload(reason = "Equipo reportado como perdido"))
            assertEquals(WipeCall.WIPE_DEVICE, plan.call)
            assertNull(plan.reason)
            assertEquals(listOf("reason"), plan.dropped)
        }
    }

    @Test
    fun `android 9 to 13 show the reason unless the wipe is silent`() {
        for (sdk in listOf(28, 29, 30, 33)) {
            val plan = WipePlanner.plan(sdk, WipePayload(reason = "  Equipo perdido  "))
            assertEquals("sdk $sdk", WipeCall.WIPE_DATA_WITH_REASON, plan.call)
            assertEquals("Equipo perdido", plan.reason)
            assertTrue(plan.dropped.isEmpty())
        }
        // The platform rejects a reason together with WIPE_SILENTLY.
        val silent = WipePlanner.plan(30, WipePayload(reason = "Equipo perdido", silent = true))
        assertEquals(WipeCall.WIPE_DATA, silent.call)
        assertEquals(8, silent.flags)
        assertEquals(listOf("reason"), silent.dropped)
    }

    @Test
    fun `older androids drop what they do not have and say so`() {
        val p = WipePayload(externalStorage = true, euicc = true, silent = true, reason = "x", intent = "reassign")
        val api27 = WipePlanner.plan(27, p)
        assertEquals(3, api27.flags) // SD + reset protection data
        assertEquals(WipeCall.WIPE_DATA, api27.call)
        assertEquals(listOf("euicc", "silent", "reason"), api27.dropped)
        // API 28: eSIM and reason exist, silent does not yet.
        val api28 = WipePlanner.plan(28, p)
        assertEquals(7, api28.flags)
        assertEquals(WipeCall.WIPE_DATA_WITH_REASON, api28.call)
        assertEquals(listOf("silent"), api28.dropped)
    }

    // --- the handler: announce, then wipe ---

    @Test
    fun `the wipe is written down and announced before it runs`() = runTest {
        val executor = FakeExecutor()
        val disk = Disk()
        val order = ArrayList<String>()
        var announced: CommandResult? = null
        val handler = DeviceWipeHandler(34, WipeRunner(object : WipeExecutor by executor {
            override fun wipeDevice(flags: Int) { order.add("wipe"); executor.wipeDevice(flags) }
        }, disk.journal()), disk.journal()) {
            order.add(if (disk.value != null) "announce(journaled)" else "announce")
            announced = it
            true
        }
        val result = handler.handle(command("intent" to "reassign", "externalStorage" to true))

        assertEquals(listOf("announce(journaled)", "wipe"), order)
        assertEquals(CommandStatus.ACCEPTED, announced!!.status)
        assertEquals("77", announced!!.commandId)
        assertTrue(announced!!.detail!!.startsWith("wipe:starting flags=3 sd=true frp=removed"))
        assertEquals(listOf("wipeDevice(3)"), executor.calls)
        assertEquals(CommandStatus.DONE, result.status)
    }

    @Test
    fun `no link for the announcement does not stop the wipe`() = runTest {
        val executor = FakeExecutor()
        val disk = Disk()
        val handler = DeviceWipeHandler(30, WipeRunner(executor, disk.journal()), disk.journal()) { false }
        val result = handler.handle(command("reason" to "Equipo perdido"))
        assertEquals(listOf("wipeData(0,Equipo perdido)"), executor.calls)
        assertEquals(CommandStatus.DONE, result.status)
        assertTrue(result.detail!!.contains("not announced"))

        val throwing = DeviceWipeHandler(30, WipeRunner(executor, disk.journal()), disk.journal()) { error("network down") }
        assertEquals(CommandStatus.DONE, throwing.handle(command()).status)
    }

    @Test
    fun `a wipe the platform refuses is reported as failed and forgotten`() = runTest {
        val executor = FakeExecutor(fail = SecurityException("not device owner"))
        val disk = Disk()
        val handler = DeviceWipeHandler(34, WipeRunner(executor, disk.journal()), disk.journal()) { true }
        val result = handler.handle(command())
        assertEquals(CommandStatus.FAILED, result.status)
        assertTrue(result.detail!!.contains("SecurityException: not device owner"))
        assertNull(disk.value)
    }

    @Test
    fun `a payload that cannot be read wipes with the defaults`() = runTest {
        val executor = FakeExecutor()
        val disk = Disk()
        val handler = DeviceWipeHandler(33, WipeRunner(executor, disk.journal()), disk.journal()) { true }
        handler.handle(command("externalStorage" to "not-a-boolean"))
        assertEquals(listOf("wipeData(0)"), executor.calls)
    }

    // --- a wipe that was announced and not run ---

    @Test
    fun `an announced wipe the agent did not get to run runs at the next start`() {
        val executor = FakeExecutor()
        val disk = Disk()
        disk.journal().begin("77", WipePlanner.plan(34, WipePayload(intent = "reassign")))
        assertNotNull(disk.value)
        assertEquals("resumed", WipeRunner(executor, disk.journal()).resume())
        assertEquals(listOf("wipeDevice(2)"), executor.calls)
    }

    @Test
    fun `nothing pending resumes nothing, and a system that never wipes is given up on`() {
        val executor = FakeExecutor()
        val disk = Disk()
        val runner = WipeRunner(executor, disk.journal())
        assertNull(runner.resume())

        disk.journal().begin("77", WipePlanner.plan(34, WipePayload()))
        repeat(WipeJournal.MAX_ATTEMPTS) { assertEquals("resumed", runner.resume()) }
        assertTrue(runner.resume()!!.startsWith("gaveUp"))
        assertNull(disk.value)
        assertEquals(WipeJournal.MAX_ATTEMPTS, executor.calls.size)
    }
}
