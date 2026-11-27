package com.dallycontrol.policy.frp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactoryResetProtectionTest {

    private class Fake(
        override val sdkInt: Int,
        var supported: Boolean = true,
        var gms: Boolean = true,
        var failSet: Throwable? = null,
        /** The device accepts the write and then holds nothing (seen on images without a persistent data block). */
        var dropsWrites: Boolean = false,
    ) : FrpPlatform {
        var policy: FrpPolicyState? = null
        var heldGms: List<String> = emptyList()
        var broadcasts = 0
        val calls = ArrayList<String>()

        override fun policySupported() = supported
        override fun setPolicy(accounts: List<String>?) {
            calls.add("setPolicy")
            failSet?.let { throw it }
            if (!dropsWrites) policy = accounts?.let { FrpPolicyState(it, true) }
        }
        override fun readPolicy() = policy
        override fun gmsInstalled() = gms
        override fun setGmsAccounts(accounts: List<String>?) {
            calls.add("setGms")
            failSet?.let { throw it }
            if (!dropsWrites) heldGms = accounts.orEmpty()
        }
        override fun readGmsAccounts() = heldGms
        override fun notifyGms() { broadcasts++ }
    }

    private val ids = listOf("111111111111111111111", "222222222222222222222")

    @Test
    fun `android 11 and later use the policy api and report what the device holds`() {
        for (sdk in listOf(30, 31, 33, 34, 35, 36)) {
            val p = Fake(sdk)
            val s = FactoryResetProtection(p).apply(ids, hadBefore = false)
            assertEquals("sdk $sdk", "policy", s.mechanism)
            assertTrue("sdk $sdk", s.active)
            assertEquals(ids, s.accounts)
            assertNull(s.reason)
            assertEquals(listOf("setPolicy"), p.calls)
            assertEquals(0, p.broadcasts)
        }
    }

    @Test
    fun `android 5_1 to 10 use the google play services restriction and broadcast the change`() {
        for (sdk in listOf(22, 23, 26, 28, 29)) {
            val p = Fake(sdk)
            val s = FactoryResetProtection(p).apply(ids, hadBefore = false)
            assertEquals("sdk $sdk", "gms", s.mechanism)
            assertTrue("sdk $sdk", s.active)
            assertEquals(ids, p.heldGms)
            assertEquals(1, p.broadcasts)
            assertEquals(listOf("setGms"), p.calls)
        }
    }

    @Test
    fun `before android 5_1 there is no frp and asking for it says so`() {
        val s = FactoryResetProtection(Fake(21)).apply(ids, hadBefore = false)
        assertEquals("none", s.mechanism)
        assertFalse(s.active)
        assertEquals("androidTooOld", s.reason)
    }

    @Test
    fun `no accounts and nothing set before touches nothing`() {
        val p = Fake(34)
        val s = FactoryResetProtection(p).apply(emptyList(), hadBefore = false)
        assertFalse(s.active)
        assertEquals("noAccounts", s.reason)
        assertTrue(p.calls.isEmpty())
    }

    @Test
    fun `removing the accounts removes the protection these rules set`() {
        val p = Fake(34)
        val frp = FactoryResetProtection(p)
        frp.apply(ids, hadBefore = false)
        val s = frp.apply(emptyList(), hadBefore = true)
        assertNull(p.policy)
        assertFalse(s.active)
        assertEquals("noAccounts", s.reason)

        val old = Fake(28)
        val legacy = FactoryResetProtection(old)
        legacy.apply(ids, hadBefore = false)
        legacy.apply(emptyList(), hadBefore = true)
        assertTrue(old.heldGms.isEmpty())
        assertEquals(2, old.broadcasts)
    }

    @Test
    fun `a platform error is reported with its message and never as applied`() {
        val p = Fake(34, failSet = SecurityException("not a device owner"))
        val s = FactoryResetProtection(p).apply(ids, hadBefore = false)
        assertFalse(s.active)
        assertEquals("error", s.reason)
        assertEquals("SecurityException: not a device owner", s.error)
        assertTrue(FactoryResetProtection.isFailure(s))

        val old = Fake(26, failSet = IllegalStateException("boom"))
        val legacy = FactoryResetProtection(old).apply(ids, hadBefore = false)
        assertEquals("error", legacy.reason)
        assertEquals("IllegalStateException: boom", legacy.error)
    }

    @Test
    fun `a device that cannot keep an frp policy is unsupported, not a failure`() {
        val p = Fake(33, supported = false)
        val s = FactoryResetProtection(p).apply(ids, hadBefore = false)
        assertFalse(s.active)
        assertEquals("unsupported", s.reason)
        assertFalse(FactoryResetProtection.isFailure(s))
        assertTrue(p.calls.isEmpty())
    }

    @Test
    fun `a platform that throws unsupported is the device's limit, not a failure`() {
        val p = Fake(34, failSet = UnsupportedOperationException("no persistent data block"))
        val s = FactoryResetProtection(p).apply(ids, hadBefore = false)
        assertFalse(s.active)
        assertEquals("unsupported", s.reason)
        assertEquals("no persistent data block", s.error)
        assertFalse(FactoryResetProtection.isFailure(s))
    }

    @Test
    fun `an old android without google play services cannot enforce frp`() {
        val s = FactoryResetProtection(Fake(28, gms = false)).apply(ids, hadBefore = false)
        assertFalse(s.active)
        assertEquals("gmsMissing", s.reason)
        assertFalse(FactoryResetProtection.isFailure(s))
    }

    @Test
    fun `a write the device does not keep is an error`() {
        val s = FactoryResetProtection(Fake(34, dropsWrites = true)).apply(ids, hadBefore = false)
        assertFalse(s.active)
        assertEquals("error", s.reason)
        assertEquals("the device did not keep the accounts", s.error)
    }

    @Test
    fun `status reads back without writing and flags accounts that are not the policy's`() {
        val p = Fake(34)
        p.policy = FrpPolicyState(listOf("999999999999999999999"), true)
        val frp = FactoryResetProtection(p)
        assertTrue(frp.status().active)
        val s = frp.status(ids)
        assertTrue(s.active)
        assertEquals("error", s.reason)
        assertTrue(p.calls.isEmpty())

        p.policy = FrpPolicyState(ids, false)
        assertFalse(frp.status(ids).active)
    }

    @Test
    fun `only numeric google account ids are kept`() {
        assertEquals(
            listOf("123456789012345678901"),
            FactoryResetProtection.clean(listOf(" 123456789012345678901 ", "it@acme.com", "", "123456789012345678901")),
        )
    }

    @Test
    fun `the google play services names are the documented ones`() {
        assertEquals("com.google.android.gms", FactoryResetProtection.GMS_PACKAGE)
        assertEquals("factoryResetProtectionAdmin", FactoryResetProtection.GMS_RESTRICTION)
        assertEquals("com.google.android.gms.auth.FRP_CONFIG_CHANGED", FactoryResetProtection.GMS_BROADCAST)
    }
}
