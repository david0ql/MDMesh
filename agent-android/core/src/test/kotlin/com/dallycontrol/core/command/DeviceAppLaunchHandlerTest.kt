package com.dallycontrol.core.command

import com.dallycontrol.core.command.handlers.DeviceAppLaunchHandler
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAppLaunchHandlerTest {
    private fun cmd(pkg: String?) = CommandEnvelope(
        commandId = "1", issuedAt = "2026-09-28T00:00:00Z", type = "device.appLaunch",
        payload = pkg?.let { buildJsonObject { put("packageName", JsonPrimitive(it)) } },
    )

    @Test fun `launches the package and reports the reason when it cannot`() = runTest {
        val launched = mutableListOf<String>()
        val h = DeviceAppLaunchHandler { p -> launched += p; if (p == "com.missing") "not installed" else null }
        assertEquals(CommandStatus.DONE, h.handle(cmd(" co.amovil.preventa ")).status)
        assertEquals(listOf("co.amovil.preventa"), launched)
        val r = h.handle(cmd("com.missing"))
        assertEquals(CommandStatus.FAILED, r.status)
        assertEquals("not installed", r.detail)
        assertEquals(CommandStatus.FAILED, h.handle(cmd(null)).status)
    }
}

class DeviceOpenStoreHandlerTest {
    private fun cmd(pkg: String?) = com.dallycontrol.proto.CommandEnvelope(
        commandId = "1", issuedAt = "2026-09-29T00:00:00Z", type = "device.openStore",
        payload = pkg?.let { kotlinx.serialization.json.buildJsonObject { put("packageName", kotlinx.serialization.json.JsonPrimitive(it)) } },
    )

    @org.junit.Test fun `opens a valid package and refuses junk`() = kotlinx.coroutines.test.runTest {
        val opened = mutableListOf<String>()
        val h = com.dallycontrol.core.command.handlers.DeviceOpenStoreHandler { opened += it; null }
        org.junit.Assert.assertEquals(com.dallycontrol.proto.CommandStatus.DONE, h.handle(cmd("com.whatsapp")).status)
        org.junit.Assert.assertEquals(listOf("com.whatsapp"), opened)
        org.junit.Assert.assertEquals(com.dallycontrol.proto.CommandStatus.FAILED, h.handle(cmd("x; rm")).status)
        org.junit.Assert.assertEquals(com.dallycontrol.proto.CommandStatus.FAILED, h.handle(cmd(null)).status)
    }
}
