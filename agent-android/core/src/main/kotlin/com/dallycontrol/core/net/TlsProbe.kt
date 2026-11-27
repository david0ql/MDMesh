package com.dallycontrol.core.net

import android.annotation.SuppressLint
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Reads who issued the certificate a host presents — only to REPORT it. After a TLS failure this tells a network
 * that inspects TLS (the issuer is the firewall's own authority) from a problem on our side. The handshake here
 * accepts any certificate precisely because the regular one was already refused; nothing is sent over it and the
 * socket is closed at once. It never replaces the validating client.
 */
object TlsProbe {
    private const val TIMEOUT_MS = 5_000
    private const val HTTPS_PORT = 443

    fun issuerOf(host: String, port: Int = HTTPS_PORT): String? = runCatching {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(ReportOnlyTrustManager), SecureRandom())
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        plain.soTimeout = TIMEOUT_MS
        (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).use { socket ->
            socket.startHandshake()
            val leaf = socket.session.peerCertificates.firstOrNull() as? X509Certificate
            leaf?.issuerX500Principal?.name
        }
    }.getOrNull()

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object ReportOnlyTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
