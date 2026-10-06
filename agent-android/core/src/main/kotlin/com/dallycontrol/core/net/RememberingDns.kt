package com.dallycontrol.core.net

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * The system resolver, plus the last addresses it gave for each host. Some carriers' DNS (seen on WOM, Colombia)
 * fails now and then while the network itself works: the check-in, on a connection already open, kept going, and every
 * APK download — a new connection — died with "Unable to resolve host". Then the last good answer is used.
 */
class RememberingDns(
    private val store: Store,
    private val system: Dns = Dns.SYSTEM,
) : Dns {

    /** Where the last good answers live (survives restarts). */
    interface Store {
        fun get(host: String): List<String>
        fun put(host: String, addresses: List<String>)
    }

    override fun lookup(hostname: String): List<InetAddress> = try {
        system.lookup(hostname).also { found ->
            runCatching { store.put(hostname, found.mapNotNull { it.hostAddress }) }
        }
    } catch (e: UnknownHostException) {
        val remembered = runCatching { store.get(hostname) }.getOrDefault(emptyList())
            .mapNotNull { runCatching { InetAddress.getByAddress(hostname, InetAddress.getByName(it).address) }.getOrNull() }
        remembered.ifEmpty { throw e }
    }
}
