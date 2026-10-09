package com.dallycontrol.core.install

/**
 * Frees a kiosk-pinned app so a downgrade can uninstall it.
 *
 * A device in COSU kiosk pins the foreground task (lock task): when the app being downgraded is that task,
 * Android refuses to uninstall it (`DELETE_FAILED_APP_PINNED`) and the downgrade can never complete. The agent
 * brings its own kiosk launcher forward so the launcher — not the target app — becomes the pinned task; the target
 * then uninstalls, the older version installs, and the launcher re-pins on its own.
 *
 * A no-op off kiosk (and on a non-kiosk build). Best-effort: failures here must not abort the install, which simply
 * fails its uninstall step as before.
 */
fun interface KioskUnpin {
    /** Bring the kiosk launcher forward so [packageName] is no longer the pinned task. Returns when it is safe to try. */
    suspend fun releaseForUninstall(packageName: String)

    companion object {
        /** Does nothing — the default when no kiosk is present (tests, non-kiosk builds). */
        val NONE: KioskUnpin = KioskUnpin { }
    }
}
