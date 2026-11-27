package com.dallycontrol.core.enroll

import android.content.Context
import com.dallycontrol.proto.EnrollDiagnostics

/**
 * What the agent remembers about its own enrollment until it succeeds: the path it was provisioned through, how many
 * times it has asked, and why the last attempt failed. The status screens read it; [com.dallycontrol.core.sync.EnrollmentManager]
 * sends it with each attempt so a failure the server could not see (no network, wrong clock) still reaches the console.
 */
interface EnrollJournal {
    /** A new enroll call is about to go out; returns what to send with it. */
    fun beginAttempt(): EnrollDiagnostics?
    fun failed(failure: EnrollFailure)
    fun succeeded()

    object None : EnrollJournal {
        override fun beginAttempt(): EnrollDiagnostics? = null
        override fun failed(failure: EnrollFailure) = Unit
        override fun succeeded() = Unit
    }
}

/** Identity only a Device Owner may read (serial, first IMEI): lets the server recognise a phone after a reset. */
fun interface EnrollIdentitySource {
    /** (serial, imei); either may be null. Must not throw. */
    fun get(): Pair<String?, String?>
}

/** SharedPreferences-backed journal (synchronous: provisioning callbacks and the UI read it without a coroutine). */
class EnrollStatusStore(context: Context) : EnrollJournal {

    data class Snapshot(
        val path: String?,
        val attempts: Int,
        val errorCode: String?,
        val errorDetail: String?,
        val errorAt: Long,
        val enrolledAt: Long,
    )

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Called when the failure changes (a hook for repairs such as setting a wrong clock). */
    var onFailure: ((EnrollFailure) -> Unit)? = null

    /** The first path wins: Android 12+ calls the compliance activity, older releases the admin receiver. */
    fun setPath(path: String, replace: Boolean = false) {
        if (replace || prefs.getString(KEY_PATH, null) == null) prefs.edit().putString(KEY_PATH, path).apply()
    }

    fun snapshot(): Snapshot = Snapshot(
        path = prefs.getString(KEY_PATH, null),
        attempts = prefs.getInt(KEY_ATTEMPTS, 0),
        errorCode = prefs.getString(KEY_ERROR, null),
        errorDetail = prefs.getString(KEY_DETAIL, null),
        errorAt = prefs.getLong(KEY_ERROR_AT, 0L),
        enrolledAt = prefs.getLong(KEY_ENROLLED_AT, 0L),
    )

    /** A person asked to try again (new code typed, "retry" tapped): the old failure no longer describes the state. */
    fun clearFailure() {
        prefs.edit().remove(KEY_ERROR).remove(KEY_DETAIL).remove(KEY_ERROR_AT).apply()
    }

    override fun beginAttempt(): EnrollDiagnostics {
        val attempts = prefs.getInt(KEY_ATTEMPTS, 0) + 1
        prefs.edit().putInt(KEY_ATTEMPTS, attempts).apply()
        val s = snapshot()
        return EnrollDiagnostics(
            path = s.path ?: PATH_UNKNOWN,
            attempts = attempts,
            lastError = s.errorCode,
            lastErrorDetail = s.errorDetail,
            lastErrorAt = s.errorAt.takeIf { it > 0 },
        )
    }

    override fun failed(failure: EnrollFailure) {
        prefs.edit().putString(KEY_ERROR, failure.code).putString(KEY_DETAIL, failure.detail)
            .putLong(KEY_ERROR_AT, System.currentTimeMillis()).apply()
        runCatching { onFailure?.invoke(failure) }
    }

    override fun succeeded() {
        prefs.edit().remove(KEY_ERROR).remove(KEY_DETAIL).remove(KEY_ERROR_AT)
            .putLong(KEY_ENROLLED_AT, System.currentTimeMillis()).apply()
    }

    companion object {
        const val FILE = "mdm_enroll_status"
        const val PATH_QR_MODERN = "qr-modern"
        const val PATH_QR_LEGACY = "qr-legacy"
        const val PATH_ADB = "adb"
        const val PATH_CODE = "code"
        const val PATH_UNKNOWN = "unknown"
        private const val KEY_PATH = "path"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_ERROR = "error"
        private const val KEY_DETAIL = "detail"
        private const val KEY_ERROR_AT = "errorAt"
        private const val KEY_ENROLLED_AT = "enrolledAt"
    }
}
