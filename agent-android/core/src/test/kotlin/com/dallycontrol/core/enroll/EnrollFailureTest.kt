package com.dallycontrol.core.enroll

import com.dallycontrol.core.sync.EnrollmentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class EnrollFailureTest {

    private val now = EnrollFailure.EARLIEST_PLAUSIBLE_NOW + 86_400_000L

    private fun code(t: Throwable, at: Long = now) = EnrollFailure.classify(t, at).code

    @Test
    fun `a certificate that is not valid yet means the phone clock is wrong`() {
        val t = SSLHandshakeException("Chain validation failed").apply {
            initCause(CertificateException(CertPathValidatorException("timestamp check failed",
                CertificateNotYetValidException("NotBefore: Mon Nov 02 2026"))))
        }
        assertEquals(EnrollFailure.CLOCK, code(t))
    }

    @Test
    fun `any TLS failure with a factory clock is blamed on the clock`() {
        assertEquals(EnrollFailure.CLOCK, code(SSLHandshakeException("Chain validation failed"), at = 1_500_000_000_000L))
        assertEquals(EnrollFailure.TLS, code(SSLHandshakeException("Chain validation failed")))
    }

    @Test
    fun `network failures are told apart`() {
        assertEquals(EnrollFailure.NO_NETWORK, code(UnknownHostException("mdm.example.com")))
        assertEquals(EnrollFailure.TIMEOUT, code(SocketTimeoutException("timeout")))
        assertEquals(EnrollFailure.CAPTIVE_PORTAL, code(SSLPeerUnverifiedException("Hostname mdm.example.com not verified")))
    }

    @Test
    fun `a web page instead of the server answer is a captive portal`() {
        class SerializationException(message: String) : RuntimeException(message)
        assertEquals(EnrollFailure.CAPTIVE_PORTAL, code(SerializationException("Unexpected JSON token at offset 0: <html>")))
        class HttpException(message: String) : RuntimeException(message)
        assertEquals(EnrollFailure.CAPTIVE_PORTAL, code(HttpException("HTTP 302 Found")))
        assertEquals(EnrollFailure.CAPTIVE_PORTAL, code(HttpException("HTTP 511 Network Authentication Required")))
        assertEquals(EnrollFailure.SERVER_ERROR, code(HttpException("HTTP 502 Bad Gateway")))
    }

    @Test
    fun `server rejections keep their reason and are terminal`() {
        val used = EnrollFailure.classify(EnrollmentException("error.agent.token.used"), now)
        assertEquals(EnrollFailure.TOKEN_USED, used.code)
        assertTrue(used.isTerminal)
        assertEquals(EnrollFailure.TOKEN_EXPIRED, code(EnrollmentException("error.agent.token.expired")))
        assertEquals(EnrollFailure.TOKEN_INVALID, code(EnrollmentException("error.agent.token.invalid")))
        assertEquals(EnrollFailure.DISABLED, code(EnrollmentException("error.agent.enrollment.disabled")))
        assertEquals(EnrollFailure.REJECTED, code(EnrollmentException("error.internal")))
    }

    @Test
    fun `transient failures are not terminal`() {
        assertFalse(EnrollFailure.classify(UnknownHostException("x"), now).isTerminal)
        assertFalse(EnrollFailure.classify(EnrollmentException("no enrollment token available"), now).isTerminal)
        assertEquals(EnrollFailure.NO_TOKEN, code(EnrollmentException("no enrollment token available")))
        assertEquals(EnrollFailure.UNKNOWN, code(IllegalStateException("boom")))
    }

    @Test
    fun `the detail names the causes and stays short`() {
        val f = EnrollFailure.classify(RuntimeException("x".repeat(1000), UnknownHostException("host")), now)
        assertTrue(f.detail.startsWith("RuntimeException: xxx"))
        assertTrue(f.detail.length <= 300)
    }
}
