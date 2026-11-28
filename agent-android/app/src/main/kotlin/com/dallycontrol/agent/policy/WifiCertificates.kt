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
 *  - the server's CA with `DevicePolicyManager.installCaCert` (it also goes inside the network's own configuration,
 *    which is what the Wi-Fi stack validates against);
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
            if (cert.notAfter.time < now) {
                throw WifiCertificateException("the CA certificate expired on ${cert.notAfter}")
            }
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
    fun clientKey(
        keyB64: String?,
        algorithm: String?,
        chainB64: List<String>?,
        now: Long = System.currentTimeMillis(),
    ): ClientKey {
        if (keyB64.isNullOrBlank() || chainB64.isNullOrEmpty()) throw WifiCertificateException("no client certificate")
        val chain = chainB64.map { parse(decode(it, "client certificate"), "client certificate") }
        val leaf = chain.first()
        val key = privateKey(keyB64, algorithm ?: leaf.publicKey.algorithm, leaf, now)
        val alias = ALIAS_PREFIX + fingerprint(leaf.encoded).take(ALIAS_HEX)
        val granted = owner() && runCatching { installKeyPair(key, chain, alias) }
            .onFailure { Log.w(TAG, "installKeyPair $alias: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        return ClientKey(alias, key, chain, granted)
    }

    private fun privateKey(keyB64: String, algorithm: String, leaf: X509Certificate, now: Long): PrivateKey {
        val expired = leaf.notAfter.time < now
        val key = if (expired) {
            null
        } else {
            runCatching {
                KeyFactory.getInstance(algorithm.ifBlank { leaf.publicKey.algorithm })
                    .generatePrivate(PKCS8EncodedKeySpec(decode(keyB64, "client key")))
            }.getOrNull()
        }
        return key ?: throw WifiCertificateException(
            if (expired) "the client certificate expired on ${leaf.notAfter}" else "the client key cannot be read",
        )
    }

    /** @return true when the Wi-Fi stack may use the key by its alias (Android 12+). */
    private fun installKeyPair(key: PrivateKey, chain: List<X509Certificate>, alias: String): Boolean {
        val installed = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                handle.dpm.installKeyPair(handle.admin, key, chain.toTypedArray(), alias, 0)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ->
                handle.dpm.installKeyPair(handle.admin, key, chain.toTypedArray(), alias, false)
            else -> handle.dpm.installKeyPair(handle.admin, key, chain.first(), alias)
        }
        if (!installed) {
            Log.w(TAG, "installKeyPair refused $alias")
            return false
        }
        val mine = prefs.getStringSet(KEY_ALIASES, emptySet()).orEmpty()
        prefs.edit().putStringSet(KEY_ALIASES, mine + alias).apply()
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && handle.dpm.grantKeyPairToWifiAuth(alias)
    }

    /** Remove the certificates this class installed that none of [networks] uses any more. */
    fun retainOnly(networks: List<ConfigWifi>) {
        if (!owner()) return
        fun print(b64: String) = runCatching { fingerprint(decode(b64, "")) }.getOrNull()
        val cas = networks.flatMap { it.eap?.caCerts.orEmpty() }.mapNotNull(::print).toSet()
        val aliases = networks.mapNotNull { it.eap?.clientChain?.firstOrNull() }.mapNotNull(::print)
            .map { ALIAS_PREFIX + it.take(ALIAS_HEX) }.toSet()
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(CA_PREFIX) && it.removePrefix(CA_PREFIX) !in cas }.forEach { k ->
            val der = Base64.decode(prefs.getString(k, ""), Base64.DEFAULT)
            runCatching { handle.dpm.uninstallCaCert(handle.admin, der) }
            edit.remove(k)
        }
        val mine = prefs.getStringSet(KEY_ALIASES, emptySet()).orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (mine - aliases).forEach { alias -> runCatching { handle.dpm.removeKeyPair(handle.admin, alias) } }
        }
        edit.putStringSet(KEY_ALIASES, mine intersect aliases).apply()
    }

    private fun owner() = runCatching { handle.dpm.isDeviceOwnerApp(handle.admin.packageName) }.getOrDefault(false)

    private fun decode(b64: String, what: String): ByteArray =
        runCatching { Base64.decode(b64, Base64.DEFAULT) }
            .getOrElse { throw WifiCertificateException("the $what is not valid base64") }

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
