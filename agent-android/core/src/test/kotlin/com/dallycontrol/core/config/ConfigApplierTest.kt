package com.dallycontrol.core.config

import android.content.ComponentName
import com.dallycontrol.core.kiosk.KioskApplier
import com.dallycontrol.core.kiosk.KioskHomeSwitch
import com.dallycontrol.core.store.InMemoryConfigStateStore
import com.dallycontrol.core.store.InMemoryKioskStateStore
import com.dallycontrol.kiosk.KioskController
import com.dallycontrol.kiosk.KioskResult
import com.dallycontrol.policy.PolicyOutcome
import com.dallycontrol.policy.TogglePolicy
import com.dallycontrol.proto.ConfigAppPolicy
import com.dallycontrol.proto.ConfigApplyPayload
import com.dallycontrol.proto.ConfigBrowser
import com.dallycontrol.proto.ConfigTracking
import com.dallycontrol.proto.ConfigLocation
import com.dallycontrol.proto.ConfigOutcome
import com.dallycontrol.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConfigApplierTest {
    private class FakeToggle(override val capabilityKey: String, private val outcome: PolicyOutcome) : TogglePolicy {
        var last: Boolean? = null
        override fun isSupported() = true
        override fun setEnabled(enabled: Boolean): PolicyOutcome { last = enabled; return outcome }
    }
    private class FakeController(private val enterResult: KioskResult = KioskResult.Ok) : KioskController {
        var enters = 0; var exits = 0
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult { enters++; return enterResult }
        override fun exit(): KioskResult { exits++; return KioskResult.Ok }
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }
    private object NoHome : KioskHomeSwitch { override fun setClaimEnabled(enabled: Boolean) {}; override fun showLauncher() {}; override fun showOemHome() {} }

    private fun kiosk(c: KioskController, store: InMemoryKioskStateStore = InMemoryKioskStateStore()) =
        KioskApplier(c, store, NoHome, ComponentName("a", "b"))

    @Test fun `applies present policies only and persists on full success`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Applied); val bt = FakeToggle("bluetooth", PolicyOutcome.Applied)
        val store = InMemoryConfigStateStore(); var loc: String? = null
        val r = ConfigApplier(mapOf("wifi" to wifi, "bluetooth" to bt), kiosk(FakeController()), { loc = it }, store)
            .apply(ConfigApplyPayload(revision = "r1", policies = mapOf("wifi" to false), location = ConfigLocation("active")))
        assertEquals("no kiosk in the doc and none previously applied -> no kiosk key",
            mapOf("policies.wifi" to ConfigOutcome.APPLIED, "location" to ConfigOutcome.APPLIED), r.outcomes)
        assertEquals(false, wifi.last); assertNull("bluetooth not in doc -> untouched", bt.last)
        assertEquals("active", loc)
        assertEquals("r1", store.revision())
        assertTrue(ConfigApplier.succeeded(r))
    }

    @Test fun `unsupported policy still counts as success and persists`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(emptyMap(), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "r2", policies = mapOf("usbStorage" to false)))
        assertEquals(ConfigOutcome.UNSUPPORTED, r.outcomes["policies.usbStorage"])
        assertEquals("r2", store.revision())
    }

    @Test fun `a failed key blocks persistence`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(mapOf("wifi" to FakeToggle("wifi", PolicyOutcome.Failed("dpm"))), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "r3", policies = mapOf("wifi" to true)))
        assertEquals("failed: dpm", r.outcomes["policies.wifi"])
        assertFalse(ConfigApplier.succeeded(r)); assertNull(store.revision())
    }

    @Test fun `kiosk present enters, kiosk absent exits only when the previous config asserted kiosk`() = runTest {
        val c = FakeController(); val kstore = InMemoryKioskStateStore()
        val a = ConfigApplier(emptyMap(), kiosk(c, kstore), {}, InMemoryConfigStateStore())
        val r1 = a.apply(ConfigApplyPayload(revision = "k1", kiosk = KioskApplyPayload(mode = "single", pinPackage = "com.a")))
        assertEquals(1, c.enters); assertNotNull(kstore.load())
        assertEquals(ConfigOutcome.APPLIED, r1.outcomes["kiosk"])
        val r2 = a.apply(ConfigApplyPayload(revision = "k2"))
        assertEquals("admin turned kiosk off -> exit", 1, c.exits)
        assertEquals("exit is reported", ConfigOutcome.APPLIED, r2.outcomes["kiosk"])
        val r3 = a.apply(ConfigApplyPayload(revision = "k3"))
        assertEquals("already out of kiosk -> no second exit", 1, c.exits)
        assertFalse("nothing ran -> kiosk key omitted", r3.outcomes.containsKey("kiosk"))
    }

    @Test fun `a manual kiosk survives a kiosk-off configuration (upgrade safety)`() = runTest {
        val c = FakeController(); val kstore = InMemoryKioskStateStore()
        kstore.save(KioskApplyPayload(mode = "single", pinPackage = "com.manual")) // set by an ad-hoc kiosk.enter
        val a = ConfigApplier(emptyMap(), kiosk(c, kstore), {}, InMemoryConfigStateStore()) // no config ever applied
        val r = a.apply(ConfigApplyPayload(revision = "first"))
        assertEquals(0, c.exits); assertNotNull(kstore.load())
        assertFalse("nothing asserted or exited -> kiosk key omitted", r.outcomes.containsKey("kiosk"))
    }

    @Test fun `kiosk unsupported is reported as unsupported not failed`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(emptyMap(), kiosk(FakeController(KioskResult.Unsupported)), {}, store)
            .apply(ConfigApplyPayload(revision = "k9", kiosk = KioskApplyPayload()))
        assertEquals(ConfigOutcome.UNSUPPORTED, r.outcomes["kiosk"]); assertEquals("k9", store.revision())
    }

    @Test fun `reapplyPersisted replays the stored document`() = runTest {
        val wifi = FakeToggle("wifi", PolicyOutcome.Applied); val store = InMemoryConfigStateStore()
        store.save(ConfigApplyPayload(revision = "p1", policies = mapOf("wifi" to true)))
        val r = ConfigApplier(mapOf("wifi" to wifi), kiosk(FakeController()), {}, store).reapplyPersisted()
        assertEquals("p1", r?.revision); assertEquals(true, wifi.last)
        assertNull(ConfigApplier(emptyMap(), kiosk(FakeController()), {}, InMemoryConfigStateStore()).reapplyPersisted())
    }

    private class FakeBrowser : ManagedBrowser {
        val applied = mutableListOf<ConfigBrowser>()
        override fun apply(browser: ConfigBrowser): String { applied += browser; return ConfigOutcome.APPLIED }
    }
    private class FakeApps : AppPolicyEnforcer {
        val applied = mutableListOf<ConfigAppPolicy>()
        override fun apply(policy: ConfigAppPolicy): String { applied += policy; return ConfigOutcome.APPLIED }
        override fun reenforce() {}
    }

    @Test fun `browser, app policy and tracking are applied and reported`() = runTest {
        val b = FakeBrowser(); val a = FakeApps(); var minutes = -1
        val applier = ConfigApplier(emptyMap(), kiosk(FakeController()), {}, InMemoryConfigStateStore(), b, a) { minutes = it }
        val r = applier.apply(ConfigApplyPayload(revision = "r1",
            browser = ConfigBrowser(mode = "allowlist", allow = listOf("amovil.com.co")),
            apps = ConfigAppPolicy(mode = "allowlist", allowed = listOf("co.amovil.preventa")),
            tracking = ConfigTracking(5)))
        assertEquals(ConfigOutcome.APPLIED, r.outcomes["browser"])
        assertEquals(ConfigOutcome.APPLIED, r.outcomes["apps"])
        assertEquals(ConfigOutcome.APPLIED, r.outcomes["tracking"])
        assertEquals(listOf("amovil.com.co"), b.applied.single().allow)
        assertEquals(5, minutes)
    }

    @Test fun `a section that disappears is undone without reporting it`() = runTest {
        val b = FakeBrowser(); val a = FakeApps(); var minutes = -1; val store = InMemoryConfigStateStore()
        val applier = ConfigApplier(emptyMap(), kiosk(FakeController()), {}, store, b, a) { minutes = it }
        applier.apply(ConfigApplyPayload(revision = "r1", browser = ConfigBrowser(mode = "blocklist", block = listOf("x.com")),
            apps = ConfigAppPolicy(mode = "allowlist"), tracking = ConfigTracking(10)))
        val r = applier.apply(ConfigApplyPayload(revision = "r2"))
        assertEquals("open", b.applied.last().mode)
        assertEquals("open", a.applied.last().mode)
        assertEquals(0, minutes)
        assertFalse(r.outcomes.containsKey("browser")); assertFalse(r.outcomes.containsKey("apps")); assertFalse(r.outcomes.containsKey("tracking"))
        assertEquals("r2", store.revision())
    }

    @Test fun `no enforcer means unsupported, which does not block persistence`() = runTest {
        val store = InMemoryConfigStateStore()
        val r = ConfigApplier(emptyMap(), kiosk(FakeController()), {}, store)
            .apply(ConfigApplyPayload(revision = "r3", browser = ConfigBrowser(mode = "open")))
        assertEquals(ConfigOutcome.UNSUPPORTED, r.outcomes["browser"])
        assertEquals("r3", store.revision())
    }

    @Test fun `wifi networks are applied and cleared when the section disappears`() = runTest {
        val seen = mutableListOf<List<com.dallycontrol.proto.ConfigWifi>>(); val store = InMemoryConfigStateStore()
        val applier = ConfigApplier(emptyMap(), kiosk(FakeController()), {}, store, wifi = ManagedWifi { seen += it; ConfigOutcome.APPLIED })
        val r = applier.apply(ConfigApplyPayload(revision = "w1", wifi = listOf(com.dallycontrol.proto.ConfigWifi("Amovil", "secret123"))))
        assertEquals(ConfigOutcome.APPLIED, r.outcomes["wifi"])
        applier.apply(ConfigApplyPayload(revision = "w2"))
        assertEquals(listOf("Amovil"), seen[0].map { it.ssid })
        assertEquals("removed section -> empty list (remove what was added)", emptyList<com.dallycontrol.proto.ConfigWifi>(), seen[1])
    }
}
