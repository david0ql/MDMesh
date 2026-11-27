package com.dallycontrol.agent.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * The agent's local "VPN" for the mobile-data policy on phones where the system restriction is not available
 * (Android 7–8) or is not honoured by the maker. It connects to no server: it only captures the apps that may not
 * use mobile data and lets their packets die in the tunnel. Apps that are allowed never enter it, so their traffic —
 * and the agent's own — is untouched.
 *
 *  - On a mobile network (or with no network yet) the tunnel captures the blocked apps.
 *  - On Wi-Fi it captures only [MobileDataVpn.KEY_IDLE], an installed package without the INTERNET permission, so
 *    the blocked apps work again while the tunnel stays "connected" (an always-on VPN that disconnects makes Android
 *    show a permanent warning). Without such a package the tunnel is simply closed on Wi-Fi.
 *
 * Establishing a tunnel replaces the previous one atomically, so switching network never cuts the allowed apps.
 * Nothing reads the tunnel: the kernel drops what the blocked apps send once its small queue is full.
 */
class MobileDataVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var captured: Set<String> = emptySet()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconcile()
        return START_STICKY
    }

    @Synchronized
    private fun reconcile() {
        val want = MobileDataVpn.capture(this)
        if (tun != null && want.isNotEmpty() && want == captured) return
        val builder = Builder().setSession(SESSION).addAddress(ADDRESS_V4, PREFIX_V4).addRoute(ANY_V4, 0)
        runCatching { builder.addAddress(ADDRESS_V6, PREFIX_V6).addRoute(ANY_V6, 0) }
        // Only the listed apps enter the tunnel. With no app added a VPN captures EVERY app, so never establish then.
        val added = want.filterTo(HashSet()) { pkg -> runCatching { builder.addAllowedApplication(pkg) }.isSuccess }
        if (added.isEmpty()) {
            close()
            stopSelf()
            return
        }
        // Null = not authorised (the Device Owner's always-on setting was removed) or another VPN owns the slot.
        val fresh = runCatching { builder.establish() }.onFailure { Log.w(TAG, "establish", it) }.getOrNull()
        val old = tun
        if (fresh != null) {
            tun = fresh
            captured = added
            runCatching { old?.close() }
        }
        MobileDataVpn.up = tun != null
        MobileDataVpn.blocking = tun != null && captured == MobileDataVpn.blockedPackages(this)
        Log.i(TAG, "tunnel up=${MobileDataVpn.up}, ${captured.size} app(s), blocking=${MobileDataVpn.blocking}")
    }

    @Synchronized
    private fun close() {
        runCatching { tun?.close() }
        tun = null
        captured = emptySet()
        MobileDataVpn.up = false
        MobileDataVpn.blocking = false
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by the system")
        close()
        super.onRevoke()
    }

    override fun onDestroy() {
        close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MobileDataVpn"
        const val SESSION = "Datos móviles restringidos"
        const val ADDRESS_V4 = "10.215.173.1"
        const val PREFIX_V4 = 32
        const val ANY_V4 = "0.0.0.0"
        const val ADDRESS_V6 = "fd00:215:173::1"
        const val PREFIX_V6 = 128
        const val ANY_V6 = "::"
    }
}

/** What the tunnel must capture right now, shared by the enforcer (which decides) and the service (which obeys). */
object MobileDataVpn {
    const val PREFS = "mdm_mobile_data_vpn"
    const val KEY_BLOCKED = "blocked"
    const val KEY_IDLE = "idle"

    /** The tunnel is established. */
    @Volatile var up: Boolean = false

    /** The tunnel is capturing the blocked apps (false on Wi-Fi, where it only holds the placeholder). */
    @Volatile var blocking: Boolean = false

    fun blockedPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BLOCKED, null).orEmpty().toSet()

    /** Store the apps to block and the Wi-Fi placeholder, then make the tunnel match. */
    fun set(context: Context, blocked: Set<String>, idle: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_BLOCKED, blocked).putString(KEY_IDLE, idle).apply()
        sync(context)
    }

    /** The packages the tunnel must hold now; empty = no tunnel. */
    fun capture(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val blocked = prefs.getStringSet(KEY_BLOCKED, emptySet()).orEmpty().toSet()
        return when {
            blocked.isEmpty() || onMobile(context) -> blocked
            else -> setOfNotNull(prefs.getString(KEY_IDLE, null))
        }
    }

    /**
     * True when the network the apps would use is mobile, or there is none yet (so an app is already captured the
     * moment the mobile network comes up). The agent is never inside the tunnel, so its own active network is the
     * real one underneath.
     */
    fun onMobile(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        return caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Ask the service to re-read what it must capture (starting it when something has to be captured). */
    fun sync(context: Context) {
        if (capture(context).isEmpty() && !up) return
        // A refused background start (the agent normally holds a foreground service) is not fatal: the always-on
        // setting makes Android start the service itself on the next connectivity change or boot.
        runCatching { context.startService(Intent(context, MobileDataVpnService::class.java)) }
            .onFailure { Log.w("MobileDataVpn", "could not start the VPN service: ${it.message}") }
    }
}
