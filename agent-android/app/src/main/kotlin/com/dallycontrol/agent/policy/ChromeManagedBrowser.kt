package com.dallycontrol.agent.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.RestrictionEntry
import android.content.RestrictionsManager
import android.os.Bundle
import com.dallycontrol.core.config.ManagedBrowser
import com.dallycontrol.proto.ConfigBrowser
import com.dallycontrol.proto.ConfigOutcome
import org.json.JSONArray

/**
 * Chrome's managed configuration (the same policies an enterprise console pushes): `URLAllowlist` / `URLBlocklist`.
 * Allowlist mode blocks `*` and allows the list; blocklist mode blocks the list. Chrome declares the type of each
 * key in its manifest restrictions (a string array, or a JSON-encoded string in older builds), so the value is
 * written in whatever form the installed Chrome expects; the pre-2021 key names are set too when declared. The
 * restrictions stay with the package, so they apply as soon as Chrome is installed or updated.
 */
class ChromeManagedBrowser(
    private val context: Context,
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) : ManagedBrowser {

    override fun apply(browser: ConfigBrowser): String {
        val (allow, block) = when (browser.mode) {
            "allowlist" -> browser.allow to listOf("*")
            "blocklist" -> emptyList<String>() to browser.block
            else -> emptyList<String>() to emptyList()
        }
        for (pkg in CHROME_PACKAGES) {
            val types = manifestTypes(pkg)
            val b = Bundle()
            put(b, types, "URLAllowlist", allow); put(b, types, "URLBlocklist", block)
            if ("URLWhitelist" in types) put(b, types, "URLWhitelist", allow)
            if ("URLBlacklist" in types) put(b, types, "URLBlacklist", block)
            // Company page: Chrome cannot redirect a blocked site, so the page becomes its home page and the
            // address bar's search engine (whatever they type, they land there).
            browser.homeUrl?.takeIf { it.isNotBlank() }?.let { home ->
                val search = home + (if ('?' in home) "&" else "?") + "q={searchTerms}"
                putScalar(b, types, "HomepageLocation", home)
                putScalar(b, types, "HomepageIsNewTabPage", false)
                putScalar(b, types, "DefaultSearchProviderEnabled", true)
                putScalar(b, types, "DefaultSearchProviderName", "Empresa")
                putScalar(b, types, "DefaultSearchProviderSearchURL", search)
            }
            runCatching { dpm.setApplicationRestrictions(admin, pkg, b) }
                .onFailure { return ConfigOutcome.failed(it.message ?: "setApplicationRestrictions") }
        }
        return ConfigOutcome.APPLIED
    }

    /** The current restrictions pushed to Chrome (for the console and tests). */
    fun current(): Bundle? = runCatching { dpm.getApplicationRestrictions(admin, CHROME_PACKAGES.first()) }.getOrNull()

    private fun manifestTypes(pkg: String): Map<String, Int> = runCatching {
        val rm = context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        rm.getManifestRestrictions(pkg).orEmpty().associate { it.key to it.type }
    }.getOrDefault(emptyMap())

    private fun putScalar(b: Bundle, types: Map<String, Int>, key: String, value: Any) {
        when {
            value is Boolean && types[key] != RestrictionEntry.TYPE_STRING -> b.putBoolean(key, value)
            else -> b.putString(key, value.toString())
        }
    }

    private fun put(b: Bundle, types: Map<String, Int>, key: String, values: List<String>) {
        if (values.isEmpty()) return
        when (types[key]) {
            RestrictionEntry.TYPE_MULTI_SELECT -> b.putStringArray(key, values.toTypedArray())
            else -> b.putString(key, JSONArray(values).toString())
        }
    }

    companion object {
        val CHROME_PACKAGES = listOf("com.android.chrome")
    }
}
