package com.dallycontrol.core.install

import com.dallycontrol.core.install.VersionPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionPolicyTest {

    @Test
    fun `any-version request installs when not present`() {
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = null, requestedVersionCode = null))
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = null, requestedVersionCode = 0L))
    }

    @Test
    fun `any-version request skips when already present`() {
        val d = VersionPolicy.shouldInstall(installedVersionCode = 5L, requestedVersionCode = null)
        assertTrue(d is Decision.Skip)
        val d0 = VersionPolicy.shouldInstall(installedVersionCode = 5L, requestedVersionCode = 0L)
        assertTrue(d0 is Decision.Skip)
    }

    @Test
    fun `installs requested version when not present`() {
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = null, requestedVersionCode = 10L))
    }

    @Test
    fun `installs when requested is greater than installed`() {
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(installedVersionCode = 9L, requestedVersionCode = 10L))
    }

    @Test
    fun `skips when requested equals installed`() {
        val d = VersionPolicy.shouldInstall(installedVersionCode = 10L, requestedVersionCode = 10L)
        assertTrue(d is Decision.Skip)
    }

    @Test
    fun `blocks downgrade when requested is less than installed`() {
        assertEquals(
            Decision.DowngradeBlocked,
            VersionPolicy.shouldInstall(installedVersionCode = 11L, requestedVersionCode = 10L),
        )
    }

    @Test
    fun `installs the same code again under a new version name`() {
        assertEquals(Decision.Install, VersionPolicy.shouldInstall(10L, 10L, installedVersionName = "9.3.3.3", requestedVersionName = "9.3.3.4"))
    }

    @Test
    fun `skips the same code and name, and a name already tried`() {
        assertTrue(VersionPolicy.shouldInstall(10L, 10L, "9.3.3.4", "9.3.3.4") is Decision.Skip)
        assertTrue(VersionPolicy.shouldInstall(10L, 10L, "9.3.3.3", "9.3.3.4", nameAlreadyTried = true) is Decision.Skip)
        assertTrue(VersionPolicy.shouldInstall(10L, 10L, "9.3.3.3", null) is Decision.Skip)
    }

    @Test
    fun `a version name never turns a downgrade into an install`() {
        assertEquals(Decision.DowngradeBlocked, VersionPolicy.shouldInstall(11L, 10L, "1", "2"))
    }
}
