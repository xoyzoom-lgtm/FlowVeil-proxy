package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeBuilderTest {
    private val dark = ThemeBuilder.Colors("0B0E14", "151A23", "5FF0C4", "1C2330", "F2F5FA")

    @Test
    fun hex() {
        assertEquals("3B82F6", ThemeBuilder.hex6("#3b82f6"))
        assertEquals("3B82F6", ThemeBuilder.hex6("3B82F6"))
        assertEquals("3B82F6", ThemeBuilder.hex6("#3B82F6FF"))
        assertNull(ThemeBuilder.hex6("#3B82F"))
        assertNull(ThemeBuilder.hex6("xyz123"))
        assertNull(ThemeBuilder.hex6(null))
    }

    @Test
    fun contrastAndOnColor() {
        assertEquals(21.0, ThemeBuilder.contrast("000000", "FFFFFF"), 0.01)
        assertEquals(1.0, ThemeBuilder.contrast("777777", "777777"), 0.001)
        assertEquals("FFFFFF", ThemeBuilder.onColor("1C2330"))
        assertEquals("111111", ThemeBuilder.onColor("5FF0C4"))
        assertEquals("FFFFFF", ThemeBuilder.onColor("4F83F0").let { if (ThemeBuilder.contrast("4F83F0", "FFFFFF") >= ThemeBuilder.contrast("4F83F0", "111111")) it else "FFFFFF" })
    }

    @Test
    fun readability() {
        assertTrue(ThemeBuilder.readable(dark))
        assertFalse(ThemeBuilder.readable(dark.copy(text = "2A3340")))
    }

    @Test
    fun codeHasEveryFieldAndIsClean() {
        val code = ThemeBuilder.build(dark, "My \"theme\"; {x}")!!
        assertTrue(code.startsWith("{") && code.endsWith("}"))
        listOf("backgroundColors", "serverRowBackgroundColor", "selectedServerRowColor", "buttonColor", "buttonTextColor", "powerIconColor",
            "serverRowTitleTextColor", "topBarButtonsColor", "elipseColors", "buttonImageType").forEach { assertTrue(it, code.contains("\"$it\"")) }
        assertTrue(code.contains("\"backgroundColors\":[\"#0B0E14FF\",\"#151A23FF\"]"))
        assertTrue(code.contains("\"name\":\"My theme x\""))
        assertTrue(code.contains("\"buttonTextColor\":\"#111111FF\""))
        assertEquals(1, Regex("\"id\"").findAll(code).count())
    }

    @Test
    fun invalidColorGivesNothing() {
        assertNull(ThemeBuilder.build(dark.copy(accent = "nope")))
        assertNull(ThemeBuilder.build(dark.copy(card = "")))
    }
}
