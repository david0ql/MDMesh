package com.dallycontrol.agent.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.dallycontrol.agent.admin.AdminReceiver

/**
 * Open one system screen from inside the kiosk: its package is let through (lock-task allowlist and the kiosk's
 * suspension) for a short window and locked away again afterwards — when the window ends, when the caller says so
 * ([revoke]) or the moment the person is back on the kiosk home ([revokeAll]). The kiosk never keeps the system
 * Settings open.
 */
object TimedAllow {
    /** A screen opened by the person in the kiosk (sharing data): short, and gone as soon as they leave it. */
    const val CONFINED_WINDOW_MS = 2 * 60_000L

    private val main = Handler(Looper.getMainLooper())

    private class Grant(val addedToLockTask: Boolean, val expire: Runnable)

    private val grants = mutableMapOf<String, Grant>()

    /** Let [pkg] run in the kiosk for [windowMs] (no-op outside a kiosk). */
    @Synchronized
    fun allow(context: Context, pkg: String, windowMs: Long) {
        val app = context.applicationContext
        grants.remove(pkg)?.let { main.removeCallbacks(it.expire); letBack(app, pkg, it) }
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.componentName(app)
        val am = app.getSystemService(ActivityManager::class.java)
        var added = false
        if (am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE && Build.VERSION.SDK_INT >= 26) runCatching {
            val current = dpm.getLockTaskPackages(admin)
            if (pkg !in current) {
                dpm.setLockTaskPackages(admin, current + pkg)
                added = true
            }
        }
        AndroidKioskLockdown.lift(app, pkg)
        val expire = Runnable {
            revoke(app, pkg)
            // The window ended with the screen still up: back to the kiosk home.
            if (AndroidKioskLockdown.engaged(app)) runCatching {
                app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        grants[pkg] = Grant(added, expire)
        main.postDelayed(expire, windowMs)
    }

    /** Close [pkg] again now. */
    @Synchronized
    fun revoke(context: Context, pkg: String) {
        val grant = grants.remove(pkg) ?: return
        main.removeCallbacks(grant.expire)
        letBack(context.applicationContext, pkg, grant)
    }

    /** Close everything that was let through (the person is back on the kiosk home). */
    @Synchronized
    fun revokeAll(context: Context) {
        grants.keys.toList().forEach { revoke(context, it) }
    }

    private fun letBack(app: Context, pkg: String, grant: Grant) {
        if (grant.addedToLockTask && Build.VERSION.SDK_INT >= 26) runCatching {
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = AdminReceiver.componentName(app)
            dpm.setLockTaskPackages(admin, dpm.getLockTaskPackages(admin).filter { it != pkg }.toTypedArray())
        }
        AndroidKioskLockdown.restore(app, pkg)
    }

    /**
     * @param confined the screen opens as the only one of its task (Back leaves it to the kiosk; there is no way
     *   "up" into the rest of that app) and stays out of Recents.
     * @return null when the screen opened, else why it could not.
     */
    fun open(context: Context, intent: Intent, windowMs: Long = 3 * 60_000L, confined: Boolean = false): String? {
        val app = context.applicationContext
        val pkg = intent.`package` ?: app.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            ?: return "this phone has no such screen"
        allow(app, pkg, windowMs)
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (confined) flags = flags or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        return runCatching { app.startActivity(intent.addFlags(flags)); null }
            .getOrElse { revoke(app, pkg); it.message ?: "could not open" }
    }
}
