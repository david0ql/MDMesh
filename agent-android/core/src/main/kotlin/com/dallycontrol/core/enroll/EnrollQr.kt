package com.dallycontrol.core.enroll

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the agent needs from the console's enrollment QR when the agent itself scans it: the phone is already Device
 * Owner but the QR's payload never reached the agent (the setup wizard was abandoned, or a maker's wizard did not
 * finish the provisioning). The same QR the console shows is scanned from the agent's own screen.
 */
data class EnrollQr(val token: String, val serverUrl: String?) {

    companion object {
        private const val EXTRAS = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
        private const val TOKEN = "com.dallycontrol.ENROLL_TOKEN"
        private const val SERVER = "com.dallycontrol.SERVER_URL"
        private val CODE = Regex("^[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}$")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Accepts the provisioning QR (token and server inside the admin extras bundle), the bare admin extras (the
         * JSON given to Samsung KME), or a folder code written as text (ABCD-EFGH; no server then). Null for anything
         * else - a Wi-Fi QR, a URL, a QR of another product.
         */
        fun parse(text: String?): EnrollQr? {
            val t = text?.trim().orEmpty()
            return when {
                t.isEmpty() -> null
                t.startsWith("{") -> fromJson(t)
                CODE.matches(t) -> EnrollQr(t, null)
                else -> null
            }
        }

        private fun fromJson(t: String): EnrollQr? = runCatching {
            val root = json.parseToJsonElement(t).jsonObject
            val extras = (root[EXTRAS] as? JsonObject) ?: root
            val token = extras[TOKEN]?.jsonPrimitive?.content?.trim().orEmpty()
            val server = extras[SERVER]?.jsonPrimitive?.content?.trim()?.trimEnd('/')
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            if (token.isEmpty()) null else EnrollQr(token, server)
        }.getOrNull()
    }
}
