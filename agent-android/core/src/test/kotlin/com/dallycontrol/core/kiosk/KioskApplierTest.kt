package com.dallycontrol.core.kiosk

import android.content.ComponentName
import com.dallycontrol.core.store.InMemoryKioskStateStore
import com.dallycontrol.kiosk.KioskController
import com.dallycontrol.kiosk.KioskResult
import com.dallycontrol.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KioskApplierTest {
    private class FakeController(private val enterResult: KioskResult, private val exitResult: KioskResult = KioskResult.Ok) : KioskController {
        var entered: List<String>? = null; var exited = false
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult { entered = allowedPackages; return enterResult }
        override fun exit(): KioskResult { exited = true; return exitResult }
        override fun isLocked(context: android.content.Context): Boolean = false
        override fun allowedPackages(): List<String> = entered ?: emptyList()
    }
    private class FakeHome : KioskHomeSwitch {
        val log = mutableListOf<String>()
        override fun setClaimEnabled(enabled: Boolean) { log += "claim:$enabled" }
        override fun showLauncher() { log += "launcher" }
        override fun showOemHome() { log += "oem" }
    }
    private val home = ComponentName("com.dallycontrol.agent", "com.dallycontrol.agent.KioskHomeAlias")

    @Test fun `enter ok persists payload and shows launcher`() = runTest {
        val c = FakeController(KioskResult.Ok); val h = FakeHome(); val store = InMemoryKioskStateStore()
        val r = KioskApplier(c, store, h, home).enter(KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a")))
        assertEquals(KioskResult.Ok, r)
        assertEquals(listOf("com.a"), c.entered)
        assertEquals("com.a", store.load()?.pinPackage)
        assertEquals(listOf("claim:true", "launcher"), h.log)
    }
    @Test fun `enter unsupported reverts the home claim and persists nothing`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore()
        val r = KioskApplier(FakeController(KioskResult.Unsupported), store, h, home).enter(KioskApplyPayload())
        assertEquals(KioskResult.Unsupported, r)
        assertNull(store.load())
        assertEquals(listOf("claim:true", "claim:false"), h.log)
    }
    @Test fun `exit clears store and returns to oem home`() = runTest {
        val h = FakeHome(); val store = InMemoryKioskStateStore(); store.save(KioskApplyPayload())
        val a = KioskApplier(FakeController(KioskResult.Ok), store, h, home)
        assertTrue(a.isPersisted())
        assertEquals(KioskResult.Ok, a.exit())
        assertFalse(a.isPersisted())
        assertEquals(listOf("claim:false", "oem"), h.log)
    }

    @Test fun `functions resolve to device packages - openable ones join the home, supporting ones only run`() = runTest {
        val c = FakeController(KioskResult.Ok); val store = InMemoryKioskStateStore()
        val roles = RoleResolver { r ->
            assertEquals(listOf("phone"), r)
            ResolvedRoles(launchable = listOf("com.google.android.dialer"), support = listOf("com.android.server.telecom"))
        }
        KioskApplier(c, store, FakeHome(), home, roles).enter(
            KioskApplyPayload(mode = "launcher", pinPackage = "com.a", allowedPackages = listOf("com.a"), roles = listOf("phone")),
        )
        assertEquals(listOf("com.a", "com.google.android.dialer", "com.android.server.telecom"), c.entered)
        assertEquals("the home shows the dialer, not the in-call service",
            listOf("com.a", "com.google.android.dialer"), store.load()?.allowedPackages)
    }

    private class FakeLockdown : KioskLockdown {
        val log = mutableListOf<String>()
        override fun engage(payload: KioskApplyPayload, allowed: List<String>) { log += "engage:${payload.mode}:${allowed.sorted().joinToString(",")}" }
        override fun release() { log += "release" }
    }

    @Test fun `single-app kiosk engages the lock-down with its one app and releases it on exit`() = runTest {
        val lock = FakeLockdown(); val store = InMemoryKioskStateStore()
        val a = KioskApplier(FakeController(KioskResult.Ok), store, FakeHome(), home, lockdown = lock)
        a.enter(KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a")))
        assertEquals(listOf("engage:single:com.a"), lock.log)
        a.exit()
        assertEquals(listOf("engage:single:com.a", "release"), lock.log)
    }

    @Test fun `multi-app kiosk engages the lock-down with its apps and the packages of its functions`() = runTest {
        val lock = FakeLockdown()
        val roles = RoleResolver { ResolvedRoles(launchable = listOf("com.dialer"), support = listOf("com.telecom")) }
        KioskApplier(FakeController(KioskResult.Ok), InMemoryKioskStateStore(), FakeHome(), home, roles, lock).enter(
            KioskApplyPayload(mode = "launcher", allowedPackages = listOf("com.a", "com.b"), roles = listOf("phone")),
        )
        assertEquals(listOf("engage:launcher:com.a,com.b,com.dialer,com.telecom"), lock.log)
    }

    @Test fun `a kiosk that could not be entered locks nothing down`() = runTest {
        val lock = FakeLockdown()
        KioskApplier(FakeController(KioskResult.Unsupported), InMemoryKioskStateStore(), FakeHome(), home, lockdown = lock)
            .enter(KioskApplyPayload())
        assertTrue(lock.log.isEmpty())
    }

    @Test fun `the periodic self-heal re-engages the lock-down even on a soft kiosk, and does nothing outside kiosk`() = runTest {
        val lock = FakeLockdown(); val store = InMemoryKioskStateStore()
        val a = KioskApplier(FakeController(KioskResult.Ok), store, FakeHome(), home, lockdown = lock)
        a.reassert() // not in kiosk
        assertTrue(lock.log.isEmpty())
        store.save(KioskApplyPayload(mode = "launcher", allowedPackages = listOf("com.a")))
        a.reassert() // FakeController.currentFeatures() = -1, as on a soft kiosk
        assertEquals(listOf("engage:launcher:com.a"), lock.log)
    }
}
