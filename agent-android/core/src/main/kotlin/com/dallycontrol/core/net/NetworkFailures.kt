package com.dallycontrol.core.net

import com.dallycontrol.proto.FailureKind
import retrofit2.HttpException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

/** A network failure reduced to what an operator needs to tell "our server" from "the customer's network". */
data class ClassifiedFailure(val kind: String, val detail: String?, val httpCode: Int? = null)

object NetworkFailures {
    private const val MAX_DETAIL = 300
    private const val MAX_CAUSES = 6

    /** Answers that come from something in the way (proxy, CDN, firewall page), not from our application. */
    @Suppress("MagicNumber")
    private val GATEWAY_CODES = setOf(403, 407, 451, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 530)

    /**
     * @param httpCode status of the response that came with the failure, if any (a WebSocket handshake answered
     *   with something other than 101, e.g. a proxy's 403).
     * @param webSocket the failure happened on the wake socket.
     */
    fun classify(t: Throwable, httpCode: Int? = null, webSocket: Boolean = false): ClassifiedFailure {
        val chain = generateSequence(t) { it.cause.takeIf { c -> c !== it } }.take(MAX_CAUSES).toList()
        val detail = detailOf(chain)
        val http = chain.filterIsInstance<HttpException>().firstOrNull()
        return when {
            webSocket && httpCode != null -> ClassifiedFailure(FailureKind.WS_UPGRADE, detail, httpCode)
            http != null -> ClassifiedFailure(FailureKind.HTTP, "HTTP ${http.code()}", http.code())
            else -> ClassifiedFailure(kindOf(chain), detail, httpCode)
        }
    }

    private fun kindOf(chain: List<Throwable>): String = when {
        chain.any { it is UnknownHostException } -> FailureKind.DNS
        chain.any(::isTls) -> FailureKind.TLS
        chain.any { it is SocketTimeoutException } -> FailureKind.TIMEOUT
        chain.any { it is ConnectException || it is NoRouteToHostException } -> FailureKind.CONNECT
        chain.any { it is InterruptedIOException } -> FailureKind.TIMEOUT
        chain.any { it is java.io.IOException } -> FailureKind.CONNECT
        else -> FailureKind.OTHER
    }

    private fun isTls(t: Throwable): Boolean =
        t is SSLHandshakeException || t is SSLPeerUnverifiedException || t is SSLProtocolException ||
            t is CertificateException

    /**
     * Whether a failure says "this URL cannot be reached from here" (so a backup URL is worth trying), as opposed
     * to the server answering with its own error.
     */
    fun countsForFailover(failure: ClassifiedFailure): Boolean = when (failure.kind) {
        FailureKind.DNS, FailureKind.TLS, FailureKind.TIMEOUT, FailureKind.CONNECT -> true
        FailureKind.HTTP, FailureKind.WS_UPGRADE -> failure.httpCode in GATEWAY_CODES
        else -> false
    }

    private fun detailOf(chain: List<Throwable>): String =
        chain.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}".trim().trimEnd(':') }
            .take(MAX_DETAIL)
}
