package com.dallycontrol.core.command

import android.content.ComponentName
import com.dallycontrol.core.command.handlers.ConfigApplyHandler
import com.dallycontrol.core.config.ConfigApplier
import com.dallycontrol.core.kiosk.KioskApplier
import com.dallycontrol.core.kiosk.KioskHomeSwitch
import com.dallycontrol.core.store.InMemoryConfigStateStore
import com.dallycontrol.core.store.InMemoryKioskStateStore
import com.dallycontrol.kiosk.StubKioskController
import com.dallycontrol.policy.PolicyOutcome
import com.dallycontrol.policy.TogglePolicy
import com.dallycontrol.proto.CommandEnvelope
import com.dallycontrol.proto.CommandStatus
import com.dallycontrol.proto.ConfigApplyResult
import com.dallycontrol.proto.ProtocolJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigApplyHandlerTest {
    private class Toggle(private val o: PolicyOutcome) : TogglePolicy {
        override val capabilityKey = "wifi"; override fun isSupported() = true; override fun setEnabled(enabled: Boolean) = o
    }
    private object NoHome : KioskHomeSwitch { override fun setClaimEnabled(enabled: Boolean) {}; override fun showLauncher() {}; override fun showOemHome() {} }
    private fun handler(o: PolicyOutcome) = ConfigApplyHandler(
        ConfigApplier(mapOf("wifi" to Toggle(o)),
            KioskApplier(StubKioskController(), InMemoryKioskStateStore(), NoHome, ComponentName("a", "b")), {}, InMemoryConfigStateStore()),
    )
    private fun cmd(payload: kotlinx.serialization.json.JsonObject?) = CommandEnvelope(commandId = "9", issuedAt = "2026-01-01T00:00:00Z", type = "config.apply", payload = payload)
    private val doc = buildJsonObject { put("revision", "r1"); put("configurationId", 1); putJsonObject("policies") { put("wifi", true) } }

    @Test fun `done with outcomes json in detail`() = runTest {
        val r = handler(PolicyOutcome.Applied).handle(cmd(doc))
        assertEquals(CommandStatus.DONE, r.status)
        val parsed = ProtocolJson.json.decodeFromString(ConfigApplyResult.serializer(), r.detail!!)
        assertEquals("r1", parsed.revision); assertEquals("applied", parsed.outcomes["policies.wifi"])
    }
    @Test fun `failed when a key fails, detail still carries outcomes`() = runTest {
        val r = handler(PolicyOutcome.Failed("x")).handle(cmd(doc))
        assertEquals(CommandStatus.FAILED, r.status)
        assertEquals("failed: x", ProtocolJson.json.decodeFromString(ConfigApplyResult.serializer(), r.detail!!).outcomes["policies.wifi"])
    }
    @Test fun `missing or bad payload fails`() = runTest {
        assertEquals(CommandStatus.FAILED, handler(PolicyOutcome.Applied).handle(cmd(null)).status)
        assertEquals(CommandStatus.FAILED, handler(PolicyOutcome.Applied).handle(cmd(buildJsonObject { put("revision", 5) })).status)
    }
}
