package com.dallycontrol.core.install

import org.junit.Assert.assertEquals
import org.junit.Test

class SplitSelectorTest {
    private val bundle = listOf(
        "com.waze", "config.arm64_v8a", "config.armeabi_v7a", "config.x86_64",
        "config.hdpi", "config.xhdpi", "config.xxhdpi", "config.xxxhdpi", "config.es", "config.en", "config.fr", "config.de",
    )

    private fun pick(abis: List<String>, dpi: Int, langs: List<String>, parts: List<String> = bundle) =
        SplitSelector.select(parts, { it }, abis, dpi, langs)

    @Test
    fun `an arm64 phone in Spanish gets its processor, the nearest density and its languages`() {
        assertEquals(
            listOf("com.waze", "config.arm64_v8a", "config.xhdpi", "config.es", "config.en"),
            pick(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), 280, listOf("es-CO")),
        )
    }

    @Test
    fun `a 32-bit phone takes the 32-bit split, and a denser screen than any split takes the largest`() {
        assertEquals(
            listOf("com.waze", "config.armeabi_v7a", "config.xxxhdpi", "config.en", "config.fr"),
            pick(listOf("armeabi-v7a", "armeabi"), 800, listOf("fr")),
        )
    }

    @Test
    fun `a language the bundle lacks falls back to English, and unnamed or plain parts are all kept`() {
        assertEquals(listOf("com.waze", "config.arm64_v8a", "config.xxhdpi", "config.en"), pick(listOf("arm64-v8a"), 480, listOf("pt")))
        val plain = listOf("base", "feature_maps")
        assertEquals(plain, pick(listOf("arm64-v8a"), 480, listOf("es"), plain))
        assertEquals(listOf(null, null), SplitSelector.select(listOf<String?>(null, null), { it }, listOf("arm64-v8a"), 480, listOf("es")))
    }

    @Test
    fun `other bundle spellings are understood`() {
        assertEquals("arm64_v8a", SplitSelector.qualifierOf("split_config.arm64_v8a.apk"))
        assertEquals("xxhdpi", SplitSelector.qualifierOf("base-xxhdpi"))
        assertEquals("es", SplitSelector.qualifierOf("feature_x.config.es"))
        assertEquals(null, SplitSelector.qualifierOf("base.apk"))
        assertEquals(null, SplitSelector.qualifierOf("base-master"))
    }
}
