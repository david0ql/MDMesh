package com.dallycontrol.core.enroll

import com.dallycontrol.core.sync.EnrollmentException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Why an enrollment attempt failed, as a short stable code: shown (translated) on the phone's status screen and sent
 * to the server with the next attempt, so the console can tell a wrong clock from a captive portal from a spent token
 * without anyone reading logcat.
 */
data class EnrollFailure(val code: String, val detail: String) {

    /** Retrying the same request can never succeed: a person must act (new QR, type a code, fix the server). */
    val isTerminal: Boolean get() = code in TERMINAL

    companion object {
        const val CLOCK = "clock"
        const val NO_NETWORK = "no-network"
        const val TIMEOUT = "timeout"
        const val CAPTIVE_PORTAL = "captive-portal"
        const val TLS = "tls"
        const val SERVER_ERROR = "server-error"
        const val NO_TOKEN = "no-token"
        const val TOKEN_USED = "token.used"
        const val TOKEN_EXPIRED = "token.expired"
        const val TOKEN_INVALID = "token.invalid"
        const val DISABLED = "enrollment.disabled"
        const val REJECTED = "rejected"
        const val UNKNOWN = "unknown"

        private val TERMINAL = setOf(TOKEN_USED, TOKEN_EXPIRED, TOKEN_INVALID, DISABLED)
        private val SERVER_CODES = listOf(TOKEN_USED, TOKEN_EXPIRED, TOKEN_INVALID, DISABLED)
        private const val MAX_DETAIL = 300
        private const val MAX_CAUSES = 8

        /** A clock before this (the agent did not exist yet) is a factory clock, whatever the error says. */
        const val EARLIEST_PLAUSIBLE_NOW = 1_764_547_200_000L // 2025-12-01

        fun classify(t: Throwable, nowMillis: Long = System.currentTimeMillis()): EnrollFailure {
            val chain = generateSequence(t) { it.cause }.take(MAX_CAUSES).toList()
            val detail = chain.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
                .take(MAX_DETAIL)
            return EnrollFailure(code(t, chain, nowMillis), detail)
        }

        private fun code(t: Throwable, chain: List<Throwable>, nowMillis: Long): String = when {
            t is EnrollmentException -> serverCode(t.message.orEmpty())
            wrongClock(chain, nowMillis) -> CLOCK
            chain.any(::unreachable) -> NO_NETWORK
            chain.any { it is SocketTimeoutException } -> TIMEOUT
            chain.any(::answeredBySomethingElse) -> CAPTIVE_PORTAL
            chain.any { it is SSLException } -> TLS
            else -> httpCode(chain) ?: UNKNOWN
        }

        private fun wrongClock(chain: List<Throwable>, nowMillis: Long): Boolean =
            chain.any { it is CertificateNotYetValidException || it is CertificateExpiredException } ||
                (nowMillis < EARLIEST_PLAUSIBLE_NOW && chain.any { it is SSLException })

        private fun unreachable(t: Throwable): Boolean =
            t is UnknownHostException || t is ConnectException || t is NoRouteToHostException

        /** A hotspot's login page answers instead of the server: its certificate is for another name, or what
         *  comes back is a web page and not the server's JSON. */
        private fun answeredBySomethingElse(t: Throwable): Boolean =
            t is SSLPeerUnverifiedException || t.javaClass.name.contains("SerializationException")

        private fun serverCode(message: String): String = when {
            message.contains("no enrollment token") -> NO_TOKEN
            else -> SERVER_CODES.firstOrNull { message.contains(it) } ?: REJECTED
        }

        /** retrofit2.HttpException without depending on Retrofit here: "HTTP 502 Bad Gateway". */
        private fun httpCode(chain: List<Throwable>): String? {
            val http = chain.firstOrNull { it.javaClass.simpleName == "HttpException" } ?: return null
            val status = Regex("HTTP (\\d{3})").find(http.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            return when {
                status == null -> SERVER_ERROR
                status in REDIRECTS || status == NETWORK_AUTH_REQUIRED -> CAPTIVE_PORTAL
                else -> SERVER_ERROR
            }
        }

        private val REDIRECTS = 300..399
        private const val NETWORK_AUTH_REQUIRED = 511
    }
}
