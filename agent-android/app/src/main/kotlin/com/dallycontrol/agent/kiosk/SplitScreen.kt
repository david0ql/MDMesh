package com.dallycontrol.agent.kiosk

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Android's own "split screen" action ([AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN]), the one the system
 * runs from Recents. Some phones do not offer split screen from Recents inside a kiosk; this service lets the kiosk ask
 * for it directly. It observes nothing: it only performs that one action when the kiosk asks.
 */
class SplitScreenService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile internal var instance: SplitScreenService? = null
    }
}

/** Open two apps side by side from the kiosk. */
object SplitScreen {
    private const val TAG = "SplitScreen"

    private fun component(context: Context) = ComponentName(context, SplitScreenService::class.java).flattenToString()

    fun ready(): Boolean = SplitScreenService.instance != null

    /** The agent can switch its service on by itself (devices enrolled by cable get that permission). */
    fun canEnable(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Switch the service on when the agent may (kept on afterwards); true when it is, or is about to be, on. */
    fun ensureEnabled(context: Context): Boolean {
        if (ready()) return true
        if (!canEnable(context)) return false
        return runCatching {
            val resolver = context.contentResolver
            val me = component(context)
            val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (current?.split(':')?.contains(me) != true) {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, listOfNotNull(current, me).joinToString(":"))
            }
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            true
        }.onFailure { Log.w(TAG, "could not enable the split-screen service", it) }.getOrDefault(false)
    }

    /**
     * [first] on top (or left), [second] below: open the first, ask Android to split, then open the second next to it.
     * Returns false when the service is not on (the caller explains how to switch it on).
     */
    fun open(context: Context, first: String, second: String): Boolean {
        val app = context.applicationContext
        if (!ensureEnabled(app)) return false
        val main = Handler(Looper.getMainLooper())
        val launch = { pkg: String, adjacent: Boolean ->
            app.packageManager.getLaunchIntentForPackage(pkg)?.let { i ->
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (adjacent) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                runCatching { app.startActivity(i) }.onFailure { Log.w(TAG, "launch $pkg", it) }
            }
        }
        // The service may still be binding when it was just switched on: give it a moment.
        val start = if (ready()) 0L else 1_500L
        main.postDelayed({ launch(first, false) }, start)
        main.postDelayed({
            val ok = SplitScreenService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN) == true
            if (!ok) Log.w(TAG, "toggle split screen refused")
        }, start + 900)
        main.postDelayed({ launch(second, true) }, start + 1_900)
        return true
    }
}
