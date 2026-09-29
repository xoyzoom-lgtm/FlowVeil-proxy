package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCountryTest {
    @Test
    fun readsTheFlagEmoji() {
        assertEquals("DE", ServerCountry.code("🇩🇪 Germany | Berlin"))
        assertEquals("KZ", ServerCountry.code("Игровой 🇰🇿 50 ms"))
        assertEquals("RU", ServerCountry.code("🇷🇺Москва"))
    }

    @Test
    fun noFlagNoCode() {
        assertNull(ServerCountry.code("Germany"))
        assertNull(ServerCountry.code(""))
        assertNull(ServerCountry.code(null))
        assertNull(ServerCountry.code("🇩 lonely"))
    }

    @Test
    fun russianStillRecognised() {
        assertTrue(ServerCountry.isRussian("🇷🇺 Moscow"))
        assertFalse(ServerCountry.isRussian("🇩🇪 Berlin"))
    }
}
