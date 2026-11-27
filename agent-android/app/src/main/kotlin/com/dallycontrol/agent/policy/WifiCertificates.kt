package com.dallycontrol.agent.policy

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.dallycontrol.policy.wifi.DpmHandle
import com.dallycontrol.proto.ConfigWifi
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** A certificate of an enterprise Wi-Fi network could not be used (unreadable, expired, refused by Android). */
class WifiCertificateException(message: String) : Exception(message)

/**
 * The certificates of the enterprise Wi-Fi networks, installed by the Device Owner:
 *  - the server's CA with `DevicePolicyManager.installCaCert` (it also goes inside the network's own configuration, which
 *    is what the Wi-Fi stack validates against);
 *  - the client's key pair (EAP-TLS) with `installKeyPair`, under a stable alias derived from the certificate, and on
 *    Android 12+ handed to the Wi-Fi stack with `grantKeyPairToWifiAuth` so the key never leaves the keystore.
 *
 * What it installed is remembered, so a certificate no network uses any more is removed ([retainOnly]). Nothing here
 * logs key material.
 */
class WifiCertificates(context: Context, private val handle: DpmHandle) {
    private val prefs = context.getSharedPreferences("mdm_wifi_certs", Context.MODE_PRIVATE)
    private val x509 by lazy { CertificateFactory.getInstance("X.509") }

    class ClientKey(val alias: String, val key: PrivateKey, val chain: List<X509Certificate>, val wifiGranted: Boolean)

    /** The CA chain of a network, installed as trusted. @throws WifiCertificateException */
    fun authorities(encoded: List<String>?, now: Long = System.currentTimeMillis()): List<X509Certificate> =
        encoded.orEmpty().map { b64 ->
            val der = decode(b64, "CA certificate")
            val cert = parse(der, "CA certificate")
            if (cert.notAfter.time < now) throw WifiCertificateException("the CA certificate expired on ${cert.notAfter}")
            install(der)
            cert
        }

    private fun install(der: ByteArray) {
        if (!owner()) return
        val id = fingerprint(der)
        runCatching {
            if (!handle.dpm.hasCaCertInstalled(handle.admin, der) && !handle.dpm.installCaCert(handle.admin, der)) {
                Log.w(TAG, "installCaCert refused $id")
            }
            prefs.edit().putString(CA_PREFIX + id, Base64.encodeToString(der, Base64.NO_WRAP)).apply()
        }.onFailure { Log.w(TAG, "installCaCert $id: ${it.javaClass.simpleName}") }
    }

    /** The EAP-TLS key pair, installed in the keystore. @throws WifiCertificateException */
    fun clientKey(keyB64: String?, algorithm: String?, chainB64: List<String>?, now: Long = System.currentTimeMillis()): ClientKey {
        if (keyB64.isNullOrBlank() || chainB64.isNullOrEmpty()) throw WifiCertificateException("no client certificate")
        val chain = chainB64.map { parse(decode(it, "client certificate"), "client certificate") }
        val leaf = chain.first()
        if (leaf.notAfter.time < now) throw WifiCertificateException("the client certificate expired on ${leaf.notAfter}")
        val key = runCatching {
            KeyFactory.getInstance(algorithm?.takeIf { it.isNotBlank() } ?: leaf.publicKey.algorithm)
                .generatePrivate(PKCS8EncodedKeySpec(decode(keyB64, "client key")))
        }.getOrElse { throw WifiCertificateException("the client key cannot be read") }
        val alias = ALIAS_PREFIX + fingerprint(leaf.encoded).take(ALIAS_HEX)
        var granted = false
        if (owner()) {
            runCatching {
                val installed = if (Build.VERSION.SDK_INT >= 28) {
                    handle.dpm.installKeyPair(handle.admin, key, chain.toTypedArray(), alias, 0)
                } else if (Build.VERSION.SDK_INT >= 24) {
                    handle.dpm.installKeyPair(handle.admin, key, chain.toTypedArray(), alias, false)
                } else {
                    handle.dpm.installKeyPair(handle.admin, key, leaf, alias)
                }
                if (installed) {
                    prefs.edit().putStringSet(KEY_ALIASES, prefs.getStringSet(KEY_ALIASES, emptySet()).orEmpty() + alias).apply()
                    if (Build.VERSION.SDK_INT >= 31) granted = handle.dpm.grantKeyPairToWifiAuth(alias)
                } else {
                    Log.w(TAG, "installKeyPair refused $alias")
                }
            }.onFailure { Log.w(TAG, "installKeyPair $alias: ${it.javaClass.simpleName}") }
        }
        return ClientKey(alias, key, chain, granted)
    }

    /** Remove the certificates this class installed that none of [networks] uses any more. */
    fun retainOnly(networks: List<ConfigWifi>) {
        if (!owner()) return
        val cas = networks.flatMap { it.eap?.caCerts.orEmpty() }.mapNotNull { runCatching { fingerprint(decode(it, "")) }.getOrNull() }.toSet()
        val aliases = networks.mapNotNull { it.eap?.clientChain?.firstOrNull() }
            .mapNotNull { runCatching { ALIAS_PREFIX + fingerprint(decode(it, "")).take(ALIAS_HEX) }.getOrNull() }.toSet()
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(CA_PREFIX) && it.removePrefix(CA_PREFIX) !in cas }.forEach { k ->
            runCatching { handle.dpm.uninstallCaCert(handle.admin, Base64.decode(prefs.getString(k, ""), Base64.DEFAULT)) }
            edit.remove(k)
        }
        val mine = prefs.getStringSet(KEY_ALIASES, emptySet()).orEmpty()
        (mine - aliases).forEach { alias -> if (Build.VERSION.SDK_INT >= 24) runCatching { handle.dpm.removeKeyPair(handle.admin, alias) } }
        edit.putStringSet(KEY_ALIASES, mine intersect aliases).apply()
    }

    private fun owner() = runCatching { handle.dpm.isDeviceOwnerApp(handle.admin.packageName) }.getOrDefault(false)

    private fun decode(b64: String, what: String): ByteArray =
        runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrElse { throw WifiCertificateException("the $what is not valid base64") }

    private fun parse(der: ByteArray, what: String): X509Certificate =
        runCatching { x509.generateCertificate(ByteArrayInputStream(der)) as X509Certificate }
            .getOrElse { throw WifiCertificateException("the $what cannot be read") }

    private fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "WifiCertificates"
        const val CA_PREFIX = "ca:"
        const val KEY_ALIASES = "aliases"
        const val ALIAS_PREFIX = "dc-wifi-"
        const val ALIAS_HEX = 16
    }
}
