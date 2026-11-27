package com.dallycontrol.core.config

import com.dallycontrol.proto.ConfigWifi
import com.dallycontrol.proto.ConfigWifiEap
import com.dallycontrol.proto.ConfigWifiProxy
import com.dallycontrol.proto.ConfigWifiRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiPolicyLogicTest {
    private fun net(ssid: String = "Corp", security: String = "WPA", eap: ConfigWifiEap? = null, priority: Int? = null, auto: Boolean? = null) =
        ConfigWifi(ssid = ssid, security = security, eap = eap, priority = priority, autoConnect = auto)

    // --- security names and support per Android version ---

    @Test fun `unknown security stays WPA-PSK like older agents`() {
        assertEquals(WifiSecurity.WPA, WifiSecurity.normalize(null))
        assertEquals(WifiSecurity.WPA, WifiSecurity.normalize("wpa2"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.normalize("sae"))
        assertEquals(WifiSecurity.NONE, WifiSecurity.normalize("open"))
        assertEquals(WifiSecurity.EAP, WifiSecurity.normalize(" eap "))
    }

    @Test fun `WPA3 and OWE need Android 10, WPA3-Enterprise Android 12`() {
        assertNotNull(WifiSupport.unsupported(net(security = "WPA3"), sdk = 28))
        assertNull(WifiSupport.unsupported(net(security = "WPA3"), sdk = 29))
        assertNotNull(WifiSupport.unsupported(net(security = "OWE"), sdk = 28))
        assertNull(WifiSupport.unsupported(net(security = "WPA3_TRANSITION"), sdk = 23))
        val eap = ConfigWifiEap(method = "PEAP", phase2 = "MSCHAPV2")
        assertNotNull(WifiSupport.unsupported(net(security = "EAP_WPA3", eap = eap), sdk = 30))
        assertNull(WifiSupport.unsupported(net(security = "EAP_WPA3", eap = eap), sdk = 31))
        assertNull(WifiSupport.unsupported(net(security = "EAP", eap = eap), sdk = 23))
    }

    @Test fun `a chip without WPA3 or OWE is reported, not guessed`() {
        assertNotNull(WifiSupport.unsupported(net(security = "WPA3"), sdk = 34, wpa3 = false))
        assertNull(WifiSupport.unsupported(net(security = "WPA3"), sdk = 34, wpa3 = true))
        assertNotNull(WifiSupport.unsupported(net(security = "OWE"), sdk = 34, owe = false))
    }

    @Test fun `enterprise needs its parameters and TLS its client certificate`() {
        assertNotNull(WifiSupport.unsupported(net(security = "EAP"), sdk = 34))
        assertNotNull(WifiSupport.unsupported(net(security = "EAP", eap = ConfigWifiEap(method = "TLS")), sdk = 34))
        assertNull(WifiSupport.unsupported(net(security = "EAP", eap = ConfigWifiEap(method = "TLS", clientKey = "AAAA", clientChain = listOf("BBBB"))), sdk = 34))
        assertNotNull(WifiSupport.unsupported(net(security = "EAP", eap = ConfigWifiEap(method = "LEAP")), sdk = 34))
    }

    @Test fun `options older versions cannot honour are listed`() {
        val n = ConfigWifi("Corp", proxy = ConfigWifiProxy(host = "p", port = 8080), mac = "random", metered = "metered")
        assertEquals(listOf("proxy", "mac", "metered"), WifiSupport.unsupportedOptions(n, 24))
        assertEquals(listOf("mac"), WifiSupport.unsupportedOptions(n, 28))
        assertEquals(emptyList<String>(), WifiSupport.unsupportedOptions(n, 34))
    }

    // --- scan results ---

    @Test fun `scan capabilities are matched to the configured security`() {
        assertTrue(WifiScanMatch.matches("NONE", "[ESS]"))
        assertFalse(WifiScanMatch.matches("WPA", "[ESS]"))
        assertTrue(WifiScanMatch.matches("WPA", "[WPA2-PSK-CCMP][ESS]"))
        assertFalse(WifiScanMatch.matches("NONE", "[WPA2-PSK-CCMP][ESS]"))
        assertTrue(WifiScanMatch.matches("WPA3", "[RSN-SAE-CCMP][ESS]"))
        assertFalse(WifiScanMatch.matches("WPA3", "[WPA2-PSK-CCMP][ESS]"))
        assertTrue(WifiScanMatch.matches("WPA3_TRANSITION", "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]"))
        assertTrue(WifiScanMatch.matches("EAP", "[WPA2-EAP/SHA1-CCMP][ESS]"))
        assertFalse(WifiScanMatch.matches("EAP", "[WPA2-PSK-CCMP][ESS]"))
        assertTrue(WifiScanMatch.matches("OWE", "[RSN-OWE-CCMP][ESS]"))
        assertFalse(WifiScanMatch.matches("NONE", "[RSN-OWE-CCMP][ESS]"))
    }

    // --- why a network is not connected ---

    private fun facts(
        configured: Boolean = true, connected: Boolean = false, inRange: Boolean? = null, matches: Boolean? = null,
        auth: WifiAuthError? = null, radioOn: Boolean = true, unsupported: Boolean = false, cert: Boolean = false,
    ) = WifiDiagnosis.Facts(configured, connected, unsupported, cert, radioOn, inRange, matches, auth)

    @Test fun `a connected network has no failure`() {
        assertNull(WifiDiagnosis.reason(net(), facts(connected = true, auth = WifiAuthError.WRONG_PASSWORD)))
    }

    @Test fun `wrong key on a personal network is a password problem`() {
        assertEquals(WifiDiagnosis.PASSWORD, WifiDiagnosis.reason(net(), facts(auth = WifiAuthError.WRONG_PASSWORD, inRange = true)))
        assertEquals(WifiDiagnosis.PASSWORD, WifiDiagnosis.reason(net(security = "WPA3"), facts(auth = WifiAuthError.TIMEOUT, inRange = true)))
    }

    @Test fun `an EAP rejection is credentials, or the certificate with TLS`() {
        val peap = net(security = "EAP", eap = ConfigWifiEap(method = "PEAP"))
        val tls = net(security = "EAP", eap = ConfigWifiEap(method = "TLS", clientKey = "AA", clientChain = listOf("BB")))
        assertEquals(WifiDiagnosis.CREDENTIALS, WifiDiagnosis.reason(peap, facts(auth = WifiAuthError.EAP_FAILURE)))
        assertEquals(WifiDiagnosis.CERTIFICATE, WifiDiagnosis.reason(tls, facts(auth = WifiAuthError.EAP_FAILURE)))
        assertEquals(WifiDiagnosis.CREDENTIALS, WifiDiagnosis.reason(peap, facts(auth = WifiAuthError.WRONG_PASSWORD)))
        assertNull("an EAP timeout alone proves nothing", WifiDiagnosis.reason(peap, facts(auth = WifiAuthError.TIMEOUT, inRange = true, matches = true)))
    }

    @Test fun `unsupported, certificate and save failures win over everything else`() {
        assertEquals(WifiDiagnosis.UNSUPPORTED, WifiDiagnosis.reason(net(), facts(configured = false, unsupported = true)))
        assertEquals(WifiDiagnosis.CERTIFICATE, WifiDiagnosis.reason(net(), facts(configured = false, cert = true)))
        assertEquals(WifiDiagnosis.SAVE_FAILED, WifiDiagnosis.reason(net(), facts(configured = false, inRange = false)))
    }

    @Test fun `range, security mismatch, radio and auto-connect`() {
        assertEquals(WifiDiagnosis.OUT_OF_RANGE, WifiDiagnosis.reason(net(), facts(inRange = false)))
        assertEquals(WifiDiagnosis.SECURITY_MISMATCH, WifiDiagnosis.reason(net(), facts(inRange = true, matches = false)))
        assertEquals(WifiDiagnosis.RADIO_OFF, WifiDiagnosis.reason(net(), facts(radioOn = false, inRange = false)))
        assertEquals(WifiDiagnosis.DISABLED, WifiDiagnosis.reason(net(auto = false), facts(inRange = true, matches = true)))
        assertNull("saved, in range, idle", WifiDiagnosis.reason(net(), facts(inRange = true, matches = true)))
        assertNull("hidden network never seen in a scan", WifiDiagnosis.reason(net(), facts()))
    }

    // --- lock-down rules ---

    @Test fun `only rules that can strand a phone are restrictive`() {
        assertFalse(WifiRulesPlan.isRestrictive(null, null))
        assertFalse(WifiRulesPlan.isRestrictive(ConfigWifiRules(lockManaged = true), true))
        assertTrue(WifiRulesPlan.isRestrictive(null, false))
        assertTrue(WifiRulesPlan.isRestrictive(ConfigWifiRules(blockAdd = true), null))
        assertTrue(WifiRulesPlan.isRestrictive(ConfigWifiRules(blockConfig = true), null))
        assertTrue(WifiRulesPlan.isRestrictive(ConfigWifiRules(ssidMode = "allow"), null))
        assertTrue(WifiRulesPlan.isRestrictive(ConfigWifiRules(minSecurity = "enterprise"), null))
    }

    @Test fun `the allow list always contains the managed networks and the deny list never does`() {
        val managed = listOf(net("Corp"), net("Planta"))
        assertEquals(setOf("Invitados", "Corp", "Planta"), WifiRulesPlan.allowedSsids(ConfigWifiRules(ssidMode = "allow", ssids = listOf("Invitados", " Corp ")), managed))
        assertEquals(setOf("Cafe"), WifiRulesPlan.deniedSsids(ConfigWifiRules(ssidMode = "deny", ssids = listOf("Cafe", "Corp")), managed))
    }

    @Test fun `the minimum security never exceeds the strongest managed network`() {
        val rules = ConfigWifiRules(minSecurity = "enterprise")
        assertEquals(1, WifiRulesPlan.effectiveMinSecurity(rules, listOf(net(security = "WPA"), net("Open", "NONE"))))
        assertEquals(2, WifiRulesPlan.effectiveMinSecurity(rules, listOf(net(security = "EAP"))))
        assertEquals(2, WifiRulesPlan.effectiveMinSecurity(rules, emptyList()))
        assertEquals(0, WifiRulesPlan.effectiveMinSecurity(ConfigWifiRules(), listOf(net())))
    }

    @Test fun `safeguard minutes default to ten and are bounded`() {
        assertEquals(10, WifiRulesPlan.safeguardMinutes(null))
        assertEquals(1, WifiRulesPlan.safeguardMinutes(ConfigWifiRules(safeguardMinutes = 0)))
        assertEquals(120, WifiRulesPlan.safeguardMinutes(ConfigWifiRules(safeguardMinutes = 999)))
    }

    // --- safeguard ---

    private class MemStore : WifiSafeguard.Store {
        var s = WifiSafeguard.State()
        override fun load() = s
        override fun save(state: WifiSafeguard.State) { s = state }
    }

    private val min = 60_000L

    @Test fun `no restrictive rule, no watch`() {
        val g = WifiSafeguard(MemStore())
        assertEquals(WifiSafeguard.Action.NONE, g.tick(0, online = false, restrictive = false, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.NONE, g.tick(60 * min, online = false, restrictive = false, graceMs = 5 * min))
    }

    @Test fun `offline for the whole grace period relaxes the lock-down once`() {
        val g = WifiSafeguard(MemStore())
        assertEquals(WifiSafeguard.Action.NONE, g.tick(0, online = false, restrictive = true, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.NONE, g.tick(4 * min, online = false, restrictive = true, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.RELAX, g.tick(5 * min, online = false, restrictive = true, graceMs = 5 * min))
        assertTrue(g.state().relaxed); assertEquals(5 * min, g.state().relaxedAt)
        assertEquals("relaxed only once", WifiSafeguard.Action.NONE, g.tick(6 * min, online = false, restrictive = true, graceMs = 5 * min))
    }

    @Test fun `coming back online restarts the count`() {
        val g = WifiSafeguard(MemStore())
        g.tick(0, online = false, restrictive = true, graceMs = 5 * min)
        assertEquals(WifiSafeguard.Action.NONE, g.tick(3 * min, online = true, restrictive = true, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.NONE, g.tick(6 * min, online = false, restrictive = true, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.NONE, g.tick(10 * min, online = false, restrictive = true, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.RELAX, g.tick(11 * min, online = false, restrictive = true, graceMs = 5 * min))
    }

    @Test fun `the lock-down comes back after the relax period and is watched again`() {
        val g = WifiSafeguard(MemStore())
        g.tick(0, online = false, restrictive = true, graceMs = 5 * min)
        assertEquals(WifiSafeguard.Action.RELAX, g.tick(5 * min, online = false, restrictive = true, graceMs = 5 * min))
        assertEquals("at least 30 minutes", 35 * min, g.state().relaxedUntil)
        assertEquals("stays relaxed even when online again", WifiSafeguard.Action.NONE, g.tick(20 * min, online = true, restrictive = true, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.RESTORE, g.tick(35 * min, online = true, restrictive = true, graceMs = 5 * min))
        assertFalse(g.state().relaxed)
        assertEquals(WifiSafeguard.Action.NONE, g.tick(36 * min, online = false, restrictive = true, graceMs = 5 * min))
        assertEquals(WifiSafeguard.Action.RELAX, g.tick(41 * min, online = false, restrictive = true, graceMs = 5 * min))
    }

    @Test fun `lifting the rules while relaxed restores normal handling and a new policy starts from scratch`() {
        val store = MemStore(); val g = WifiSafeguard(store)
        g.tick(0, online = false, restrictive = true, graceMs = min); g.tick(min, online = false, restrictive = true, graceMs = min)
        assertTrue(g.state().relaxed)
        assertEquals(WifiSafeguard.Action.RESTORE, g.tick(2 * min, online = false, restrictive = false, graceMs = min))
        assertEquals(WifiSafeguard.State(), store.s)
        g.tick(3 * min, online = false, restrictive = true, graceMs = min); g.reset()
        assertEquals(WifiSafeguard.State(), store.s)
    }

    @Test fun `relax period is six times the grace, at least half an hour`() {
        assertEquals(30 * min, WifiSafeguard.relaxFor(min))
        assertEquals(60 * min, WifiSafeguard.relaxFor(10 * min))
    }

    // --- priority ---

    @Test fun `the highest priority managed network in range is preferred`() {
        val managed = listOf(net("Planta", priority = 1), net("Corp", priority = 5), net("Bodega", priority = 5))
        assertEquals("Corp", WifiPriority.preferred(managed, setOf("Planta", "Corp", "Bodega"), "Planta"))
        assertNull("already on the best", WifiPriority.preferred(managed, setOf("Planta", "Corp"), "Corp"))
        assertNull("same priority: stay", WifiPriority.preferred(managed, setOf("Corp", "Bodega"), "Bodega"))
        assertEquals("Planta", WifiPriority.preferred(managed, setOf("Planta"), "CasaDelUsuario"))
        assertNull("nothing in range", WifiPriority.preferred(managed, emptySet(), null))
    }

    @Test fun `without priorities or with auto-connect off nothing is forced`() {
        assertNull(WifiPriority.preferred(listOf(net("A"), net("B")), setOf("A", "B"), null))
        val managed = listOf(net("A", priority = 9, auto = false), net("B", priority = 1))
        assertEquals("B", WifiPriority.preferred(managed, setOf("A", "B"), null))
    }
}
