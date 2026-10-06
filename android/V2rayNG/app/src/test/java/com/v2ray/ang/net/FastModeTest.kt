package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastModeTest {
    @Test
    fun logLevel() {
        assertEquals("warning", FastMode.logLevel(true, "debug"))
        assertEquals("warning", FastMode.logLevel(true, "info"))
        assertEquals("error", FastMode.logLevel(true, "error"))
        assertEquals("debug", FastMode.logLevel(false, "debug"))
        assertEquals("warning", FastMode.logLevel(false, null))
        assertEquals("warning", FastMode.logLevel(true, ""))
    }

    @Test
    fun checkInterval() {
        assertEquals(40_000L, FastMode.checkInterval(20_000L, true, false))
        assertEquals(20_000L, FastMode.checkInterval(20_000L, false, false))
        assertEquals(10_000L, FastMode.checkInterval(10_000L, true, true))
    }

    @Test
    fun connectPicksBest() {
        assertTrue(FastMode.connectPicksBest(false, 5))
        assertTrue(FastMode.connectPicksBest(false, 1))
        assertFalse(FastMode.connectPicksBest(true, 5))
        assertFalse(FastMode.connectPicksBest(false, 0))
    }
}
