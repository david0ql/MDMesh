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
 * (Android 7–8) or is not honoured by the maker. It connects to no server: it only captures the apps that may not use
 * mobile data and lets their packets die in the tunnel. Apps that are allowed never enter it, so their traffic — and
 * the agent's own — is untouched.
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
        if (want.isEmpty()) {
            close()
            stopSelf()
            return
        }
        if (tun != null && want == captured) return
        val builder = Builder().setSession(SESSION).addAddress("10.215.173.1", 32).addRoute("0.0.0.0", 0)
        runCatching { builder.addAddress("fd00:215:173::1", 128).addRoute("::", 0) }
        // Only the listed apps enter the tunnel. With no app added a VPN captures EVERY app, so never establish then.
        val added = want.filter { pkg -> runCatching { builder.addAllowedApplication(pkg) }.isSuccess }.toSet()
        if (added.isEmpty()) {
            close()
            stopSelf()
            return
        }
        val fresh = runCatching { builder.establish() }.onFailure { Log.w(TAG, "establish", it) }.getOrNull()
        if (fresh == null) {
            // Not authorised (the Device Owner's always-on setting was removed) or another VPN owns the slot.
            Log.w(TAG, "VPN not established: not prepared")
            MobileDataVpn.up = false
            return
        }
        val old = tun
        tun = fresh
        captured = added
        MobileDataVpn.up = true
        MobileDataVpn.blocking = added == MobileDataVpn.blockedPackages(this)
        runCatching { old?.close() }
        Log.i(TAG, "tunnel up, capturing ${added.size} app(s), blocking=${MobileDataVpn.blocking}")
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BLOCKED, emptySet()).orEmpty().toSet()

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
        if (blocked.isEmpty()) return emptySet()
        if (onMobile(context)) return blocked
        return prefs.getString(KEY_IDLE, null)?.let { setOf(it) } ?: emptySet()
    }

    /**
     * True when the network the apps would use is mobile, or there is none yet (so an app is already captured the
     * moment the mobile network comes up). The agent is never inside the tunnel, so its own active network is the
     * real one underneath.
     */
    fun onMobile(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return true
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Ask the service to re-read what it must capture (starting it when something has to be captured). */
    fun sync(context: Context) {
        val intent = Intent(context, MobileDataVpnService::class.java)
        if (capture(context).isEmpty() && !up) return
        runCatching { context.startService(intent) }.onFailure {
            // Background start refused (the agent normally holds a foreground service): the always-on setting makes
            // Android start the service itself on the next connectivity change or boot.
            Log.w("MobileDataVpn", "could not start the VPN service: ${it.message}")
        }
    }
}
