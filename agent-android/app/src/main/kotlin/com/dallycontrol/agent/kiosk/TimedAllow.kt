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
 * Open one system screen from inside the kiosk: its package is let through lock task for a short window and locked
 * away again afterwards (the kiosk never keeps the system Settings open).
 */
object TimedAllow {
    private val main = Handler(Looper.getMainLooper())

    /** @return null when the screen opened, else why it could not. */
    fun open(context: Context, intent: Intent, windowMs: Long = 3 * 60_000L): String? {
        val app = context.applicationContext
        val pkg = intent.`package` ?: app.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            ?: return "this phone has no such screen"
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = AdminReceiver.componentName(app)
        val am = app.getSystemService(ActivityManager::class.java)
        val inKiosk = am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (inKiosk && Build.VERSION.SDK_INT >= 26) runCatching {
            val current = dpm.getLockTaskPackages(admin)
            if (pkg !in current) {
                dpm.setLockTaskPackages(admin, current + pkg)
                main.postDelayed({
                    runCatching { dpm.setLockTaskPackages(admin, dpm.getLockTaskPackages(admin).filter { it != pkg }.toTypedArray()) }
                }, windowMs)
            }
        }
        return runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
            .getOrElse { it.message ?: "could not open" }
    }
}
