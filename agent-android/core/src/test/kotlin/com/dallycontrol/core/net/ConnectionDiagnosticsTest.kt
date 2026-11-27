package com.dallycontrol.core.net

import com.dallycontrol.proto.FailureKind
import com.dallycontrol.proto.StopReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkFailuresTest {
    @Test fun `classifies by cause`() {
        assertEquals(FailureKind.DNS, NetworkFailures.classify(UnknownHostException("mdm.example.com")).kind)
        assertEquals(FailureKind.TIMEOUT, NetworkFailures.classify(SocketTimeoutException("timeout")).kind)
        assertEquals(FailureKind.CONNECT, NetworkFailures.classify(java.net.ConnectException("refused")).kind)
        assertEquals(FailureKind.OTHER, NetworkFailures.classify(IllegalStateException("x")).kind)
        val tls = NetworkFailures.classify(
            SSLHandshakeException("handshake").apply {
                initCause(java.security.cert.CertificateException("Trust anchor for certification path not found."))
            },
        )
        assertEquals(FailureKind.TLS, tls.kind)
        assertTrue(tls.detail!!.contains("Trust anchor"))
    }

    @Test fun `a socket handshake answered with a status is a refused upgrade`() {
        val f = NetworkFailures.classify(
            java.net.ProtocolException("Expected HTTP 101 response but was '403 Forbidden'"), 403, webSocket = true,
        )
        assertEquals(FailureKind.WS_UPGRADE, f.kind)
        assertEquals(403, f.httpCode)
        assertTrue(NetworkFailures.countsForFailover(f))
    }

    @Test fun `only path failures count for failover, not the server's own errors`() {
        assertTrue(NetworkFailures.countsForFailover(ClassifiedFailure(FailureKind.TLS, null)))
        assertTrue(NetworkFailures.countsForFailover(ClassifiedFailure(FailureKind.HTTP, null, 502)))
        assertFalse(NetworkFailures.countsForFailover(ClassifiedFailure(FailureKind.HTTP, null, 500)))
        assertFalse(NetworkFailures.countsForFailover(ClassifiedFailure(FailureKind.HTTP, null, 401)))
        assertFalse(NetworkFailures.countsForFailover(ClassifiedFailure(FailureKind.REJECTED, null)))
    }
}

class ConnectionDiagnosticsTest {
    private var now = 1_000_000L
    private var net = NetworkSnapshot("wifi", "Sede72")
    private val store = MemoryKeyValueStore()
    private val probes = mutableListOf<String>()

    private fun diagnostics(endpoints: ServerEndpoints? = null) = ConnectionDiagnostics(
        store = store,
        endpoints = endpoints,
        network = { net },
        clock = { now },
        issuerProbe = { host, _ -> probes += host; "CN=FortiGate CA,O=Fortinet" },
    )

    private fun endpoints() = ServerEndpoints({ "https://mdm.example.com" }, MemoryKeyValueStore(), clock = { now })

    private val minute = 60_000L

    @Test fun `a failure is reported with its kind, network and time, then cleared once delivered`() {
        val d = diagnostics(endpoints())
        d.recordFailure(SocketTimeoutException("timeout"), ConnectionDiagnostics.CHANNEL_CHECKIN)
        now += 1000
        d.recordFailure(SocketTimeoutException("timeout"), ConnectionDiagnostics.CHANNEL_CHECKIN)

        val report = d.pendingReport()
        val f = report.lastFailure!!
        assertEquals(FailureKind.TIMEOUT, f.kind)
        assertEquals("wifi", f.network)
        assertEquals("Sede72", f.ssid)
        assertEquals("mdm.example.com", f.host)
        assertEquals(now, f.at)
        assertEquals(2, f.count)
        assertEquals("mdm.example.com", report.serverHost)
        assertEquals(false, report.usingBackup)

        d.delivered(report)
        assertNull(d.pendingReport().lastFailure)
        assertEquals(now, d.lastSuccessAt)
    }

    @Test fun `a TLS failure carries the issuer the network presented, probed once per streak`() {
        val d = diagnostics(endpoints())
        repeat(3) { d.recordFailure(SSLHandshakeException("bad cert"), ConnectionDiagnostics.CHANNEL_CHECKIN) }
        assertEquals("CN=FortiGate CA,O=Fortinet", d.pendingReport().lastFailure!!.certIssuer)
        assertEquals(listOf("mdm.example.com"), probes)
    }

    @Test fun `it survives a restart`() {
        diagnostics().recordFailure(UnknownHostException("x"), ConnectionDiagnostics.CHANNEL_SOCKET)
        diagnostics().recordReconnect()
        val report = diagnostics().pendingReport()
        assertEquals(FailureKind.DNS, report.lastFailure!!.kind)
        assertEquals(1, report.reconnects)
    }

    @Test fun `reconnections are counted, and only the delivered ones are subtracted`() {
        val d = diagnostics()
        repeat(3) { d.recordReconnect() }
        val report = d.pendingReport()
        d.recordReconnect() // while the report was in flight
        d.delivered(report)
        assertEquals(1, d.pendingReport().reconnects)
    }

    @Test fun `without network a failure becomes a network-lost silence, not a fault`() {
        val d = diagnostics()
        d.delivered(null)
        net = NetworkSnapshot(NetworkSnapshot.NONE)
        d.recordFailure(UnknownHostException("x"), ConnectionDiagnostics.CHANNEL_CHECKIN)
        val lostAt = now
        now += 40 * minute
        net = NetworkSnapshot("cellular")
        val report = d.pendingReport()
        assertNull(report.lastFailure)
        assertEquals(StopReason.NETWORK_LOST, report.lastStop!!.reason)
        assertEquals(lostAt, report.lastStop!!.at)
        assertEquals(now, report.lastStop!!.resumedAt)
    }

    @Test fun `a blip is not reported`() {
        val d = diagnostics()
        d.recordStop(StopReason.NETWORK_LOST)
        now += 30_000
        d.networkBack()
        now += 10 * minute
        assertNull(d.pendingReport().lastStop)

        d.recordStop(StopReason.NETWORK_LOST)
        now += 2 * minute
        val report = d.pendingReport()
        assertNull(report.lastStop)
        d.delivered(report)
        now += 10 * minute
        d.delivered(d.pendingReport())
        now += minute
        assertNull(d.pendingReport().lastStop)
    }

    @Test fun `a shutdown goes out in the goodbye and again, closed, when the device is back`() {
        val d = diagnostics()
        d.onProcessStart(bootCount = 7)
        d.recordStop(StopReason.NETWORK_LOST)
        d.recordStop(StopReason.SHUTDOWN, battery = 12)
        val goodbye = d.pendingReport(goodbye = true)
        assertEquals(StopReason.SHUTDOWN, goodbye.lastStop!!.reason)
        assertEquals(12, goodbye.lastStop!!.battery)
        assertNull(goodbye.lastStop!!.resumedAt)
        d.delivered(goodbye, goodbye = true)
        val offAt = now

        now += 13 * 60 * minute
        val back = diagnostics().also { it.onProcessStart(bootCount = 8) }
        val report = back.pendingReport()
        assertEquals(StopReason.SHUTDOWN, report.lastStop!!.reason)
        assertEquals(offAt, report.lastStop!!.at)
        assertEquals(now, report.lastStop!!.resumedAt)
        back.delivered(report)
        assertNull(back.pendingReport().lastStop)
    }

    @Test fun `a restart the server was told about is closed even when it took a minute`() {
        val d = diagnostics()
        d.recordStop(StopReason.SHUTDOWN)
        d.delivered(d.pendingReport(goodbye = true), goodbye = true)
        now += minute
        val report = diagnostics().pendingReport()
        assertEquals(now, report.lastStop!!.resumedAt)
        // ...and so is one whose goodbye never went out: a shutdown is certain knowledge.
        val quiet = ConnectionDiagnostics(MemoryKeyValueStore(), clock = { now })
        quiet.recordStop(StopReason.SHUTDOWN)
        now += minute
        assertEquals(StopReason.SHUTDOWN, quiet.pendingReport().lastStop!!.reason)
    }

    @Test fun `another boot without a shutdown notice is lost power`() {
        diagnostics().also { it.onProcessStart(7); it.markAlive() }
        val aliveAt = now
        now += 3 * 60 * minute
        val d = diagnostics().also { it.onProcessStart(8) }
        assertEquals(StopReason.POWER_LOST, d.pendingReport().lastStop!!.reason)
        assertEquals(aliveAt, d.pendingReport().lastStop!!.at)
    }

    @Test fun `the same boot after a long hole is a killed process, a quick restart is not`() {
        diagnostics().also { it.onProcessStart(7); it.markAlive() }
        now += 20_000 // self-update restart
        val quick = diagnostics().also { it.onProcessStart(7) }
        assertNull(quick.pendingReport().lastStop)
        now += 45 * minute
        val d = diagnostics().also { it.onProcessStart(7) }
        assertEquals(StopReason.PROCESS_KILLED, d.pendingReport().lastStop!!.reason)
    }

    @Test fun `a long hole between two signs of life is the system keeping the agent asleep`() {
        val d = diagnostics()
        d.markAlive()
        val before = now
        now += 50 * minute
        d.markAlive()
        val stop = d.pendingReport().lastStop!!
        assertEquals(StopReason.SUSPENDED, stop.reason)
        assertEquals(before, stop.at)
    }

    @Test fun `a silence nobody explained is told apart by whether the network was there`() {
        val d = diagnostics()
        d.delivered(null)
        now += 30 * minute
        assertEquals(StopReason.UNKNOWN, d.pendingReport().lastStop!!.reason)
        d.recordFailure(SSLHandshakeException("inspected"), ConnectionDiagnostics.CHANNEL_CHECKIN)
        assertEquals(StopReason.SERVER_UNREACHABLE, d.pendingReport().lastStop!!.reason)
    }

    @Test fun `a refused check-in is recorded as rejected and does not move to a backup`() {
        val e = endpoints().also { it.saveBackups(listOf("https://direct.example.com")) }
        val d = diagnostics(e)
        repeat(5) { d.recordRejected("error.agent.unauthorized"); now += minute }
        assertEquals(FailureKind.REJECTED, d.pendingReport().lastFailure!!.kind)
        assertFalse(e.usingBackup())
    }

    @Test fun `repeated path failures move to the backup, whose use is reported`() {
        val e = endpoints().also { it.saveBackups(listOf("https://direct.example.com")) }
        val d = diagnostics(e)
        repeat(5) {
            d.recordFailure(SSLHandshakeException("inspected"), ConnectionDiagnostics.CHANNEL_CHECKIN)
            now += minute
        }
        val report = d.pendingReport()
        assertEquals(true, report.usingBackup)
        assertEquals("direct.example.com", report.serverHost)
    }
}

class ServerEndpointsTest {
    private var now = 5_000_000L
    private val store = MemoryKeyValueStore()
    private fun endpoints(allowInsecure: Boolean = false) =
        ServerEndpoints({ "https://mdm.example.com/" }, store, allowInsecure, clock = { now })

    @Test fun `the primary is used until it keeps failing`() {
        val e = endpoints()
        e.saveBackups(listOf("https://direct.example.com"))
        assertEquals("https://mdm.example.com", e.activeUrl())
        // Quick failures (a server restart) are not enough.
        repeat(ServerEndpoints.FAILOVER_FAILURES) { assertFalse(e.onUnreachable()) }
        now += ServerEndpoints.FAILOVER_AFTER_MS
        assertTrue(e.onUnreachable())
        assertEquals("https://direct.example.com", e.activeUrl())
        assertTrue(e.usingBackup())
    }

    @Test fun `a success resets the count`() {
        val e = endpoints()
        e.saveBackups(listOf("https://direct.example.com"))
        repeat(2) { e.onUnreachable() }
        e.onReached()
        now += ServerEndpoints.FAILOVER_AFTER_MS
        repeat(2) { assertFalse(e.onUnreachable()) }
        assertFalse(e.usingBackup())
    }

    @Test fun `the choice is persistent and the primary is tried again later`() {
        val e = endpoints()
        e.saveBackups(listOf("https://direct.example.com"))
        repeat(ServerEndpoints.FAILOVER_FAILURES - 1) { e.onUnreachable() }
        now += ServerEndpoints.FAILOVER_AFTER_MS
        e.onUnreachable()
        assertEquals("https://direct.example.com", endpoints().activeUrl())
        now += ServerEndpoints.RETRY_PRIMARY_MS
        assertEquals("https://mdm.example.com", endpoints().activeUrl())
    }

    @Test fun `a failing backup rotates back to the primary`() {
        val e = endpoints()
        e.saveBackups(listOf("https://direct.example.com"))
        repeat(2) {
            repeat(ServerEndpoints.FAILOVER_FAILURES - 1) { e.onUnreachable() }
            now += ServerEndpoints.FAILOVER_AFTER_MS
            e.onUnreachable()
        }
        assertEquals("https://mdm.example.com", e.activeUrl())
    }

    @Test fun `only plain https origins are accepted`() {
        val e = endpoints()
        e.saveBackups(
            listOf(
                "http://insecure.example.com", "https://user:pw@evil.example.com", "javascript:alert(1)",
                "https://with.path.example.com/x", "https://mdm.example.com", " https://direct.example.com/ ",
                "https://direct.example.com", "https://b.example.com:8443",
            ),
        )
        assertEquals(listOf("https://direct.example.com", "https://b.example.com:8443"), e.backups())
        assertEquals(listOf("http://insecure.example.com"), endpoints(allowInsecure = true).also {
            it.saveBackups(listOf("http://insecure.example.com"))
        }.backups())
    }

    @Test fun `re-provisioning to another server forgets the previous server's backups`() {
        var primary = "https://mdm.example.com"
        val e = ServerEndpoints({ primary }, store, clock = { now })
        e.saveBackups(listOf("https://direct.example.com"))
        repeat(ServerEndpoints.FAILOVER_FAILURES - 1) { e.onUnreachable() }
        now += ServerEndpoints.FAILOVER_AFTER_MS
        e.onUnreachable()
        assertTrue(e.usingBackup())
        primary = "https://otro.example.org"
        assertEquals("https://otro.example.org", e.activeUrl())
        assertTrue(e.backups().isEmpty())
    }

    @Test fun `removing the backups returns to the primary`() {
        val e = endpoints()
        e.saveBackups(listOf("https://direct.example.com"))
        repeat(ServerEndpoints.FAILOVER_FAILURES - 1) { e.onUnreachable() }
        now += ServerEndpoints.FAILOVER_AFTER_MS
        e.onUnreachable()
        e.saveBackups(emptyList())
        assertEquals("https://mdm.example.com", e.activeUrl())
        assertFalse(e.onUnreachable())
    }
}
