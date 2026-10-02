package com.dallycontrol.core.install

/**
 * Picks, from an app bundle's parts, the ones this phone needs. A bundle from the store carries configuration splits
 * for every processor type, screen density and language; Android refuses an install that mixes in splits for another
 * processor, so only the matching ones are sent to the installer (as the store itself does).
 *
 * A part is identified by its name inside the bundle ([ApkPart.split]): `config.arm64_v8a`, `config.xxhdpi`,
 * `config.es` (also `split_config.*` / `base-*` spellings). Anything that is not a configuration split — the base APK
 * and feature modules — is always kept. Parts without a name are kept too (older bundles).
 */
object SplitSelector {
    private val ABIS = setOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64")
    private val DENSITIES = linkedMapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)

    /**
     * @param abis the device's supported ABIs, preferred first (Build.SUPPORTED_ABIS)
     * @param densityDpi the screen density
     * @param languages the device's languages, e.g. `es`, `en`
     */
    fun <T> select(parts: List<T>, name: (T) -> String?, abis: List<String>, densityDpi: Int, languages: List<String>): List<T> {
        val qualifier = HashMap<T, String>()
        parts.forEach { p -> qualifierOf(name(p))?.let { qualifier[p] = it } }
        if (qualifier.isEmpty()) return parts

        val abiSplits = qualifier.values.filter { it in ABIS }.toSet()
        val wantedAbi = abis.map { it.lowercase().replace('-', '_') }.firstOrNull { it in abiSplits }
        val densitySplits = qualifier.values.filter { it in DENSITIES }.toSet()
        val wantedDensity = densitySplits.minByOrNull { d ->
            val v = DENSITIES.getValue(d)
            // Prefer the smallest bucket that is at least the screen's; else the largest available.
            if (v >= densityDpi) (v - densityDpi).toLong() else 100_000L + (densityDpi - v)
        }
        val langs = (languages.map { it.lowercase().substringBefore('-').substringBefore('_') } + "en").toSet()
        val langSplits = qualifier.values.filter { it !in ABIS && it !in DENSITIES && it != "nodpi" }.toSet()
        val anyLangMatches = langSplits.any { it.substringBefore('_') in langs }

        return parts.filter { p ->
            val q = qualifier[p] ?: return@filter true // base / feature module / unnamed
            when {
                q in ABIS -> q == wantedAbi
                q in DENSITIES -> q == wantedDensity
                q == "nodpi" -> true
                else -> !anyLangMatches || q.substringBefore('_') in langs
            }
        }
    }

    /** `config.arm64_v8a` / `split_config.es` / `base-xxhdpi` -> the qualifier; null when it is not a config split. */
    internal fun qualifierOf(name: String?): String? {
        val n = name?.lowercase()?.removeSuffix(".apk")?.trim() ?: return null
        val q = when {
            n.startsWith("split_config.") -> n.removePrefix("split_config.")
            n.startsWith("config.") -> n.removePrefix("config.")
            n.contains(".config.") -> n.substringAfterLast(".config.") // feature.config.xxhdpi
            n.startsWith("base-") -> n.removePrefix("base-")           // bundletool: base-arm64_v8a
            else -> return null
        }
        return q.takeIf { it.isNotEmpty() && it != "master" }
    }
}
