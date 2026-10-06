package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowVeilPalettesTest {
    @Test
    fun everyReadyThemeIsReadable() {
        FlowVeilPalettes.ALL.forEach { assertTrue(it.id + " " + it.name, ThemeBuilder.readable(it.colors)) }
    }

    @Test
    fun idsUniqueAndBuildable() {
        assertEquals(FlowVeilPalettes.ALL.size, FlowVeilPalettes.ALL.map { it.id }.toSet().size)
        FlowVeilPalettes.ALL.forEach { assertTrue(it.id, ThemeBuilder.build(it.colors, it.name) != null) }
    }

    @Test
    fun accentReadsOnItsButton() {
        // the label on the accent button is white or near-black, whichever is clearer: at least 3:1 for the large bold label
        FlowVeilPalettes.ALL.forEach { assertTrue(it.id, ThemeBuilder.contrast(it.colors.accent, ThemeBuilder.onColor(it.colors.accent)) >= 3.0) }
    }
}
