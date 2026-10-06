package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCardTest {
    @Test
    fun shortNameDropsEmojiAndCollapsesSpaces() {
        assertEquals("Fast Net", ShortName.of("🚀  Fast   Net 🇩🇪"))
        assertEquals("Мой сервис", ShortName.of("Мой ✨ сервис"))
    }

    @Test
    fun shortNameFixesShouting() {
        assertEquals("Supervpn pro", ShortName.of("SUPERVPN PRO"))
        assertEquals("Ab", ShortName.of("AB"))
        assertEquals("X", ShortName.of("X"))
        assertEquals("Net 24", ShortName.of("Net 24"))
    }

    @Test
    fun shortNameCutsLongNames() {
        val s = ShortName.of("Очень длинное название подписки")
        assertEquals(16, s.codePointCount(0, s.length))
        assertTrue(s.endsWith("…"))
        assertEquals("Ровно16символов!", ShortName.of("Ровно16символов!"))
    }

    @Test
    fun shortNameOnlyEmojiKeepsOriginal() {
        assertEquals("🚀🔥", ShortName.of("🚀🔥"))
        assertEquals("F", ShortName.initial("🚀 flow"))
        assertEquals("•", ShortName.initial("🚀"))
    }

    @Test
    fun paletteIsStableAndInRange() {
        val ids = listOf("", "a", "abc", "4dd0918faa9a2cca", "Подписка")
        ids.forEach {
            val p = HomeCard.paletteIndex(it)
            assertTrue(p in 0..2)
            assertEquals(p, HomeCard.paletteIndex(it))
        }
        assertEquals(0, HomeCard.paletteIndex(""))
        assertEquals(97 % 3, HomeCard.paletteIndex("a"))
        assertEquals(1, HomeCard.paletteIndex("4dd0918faa9a2cca"))
    }

    @Test
    fun daysLeftRoundsUp() {
        val day = 24L * 60 * 60 * 1000
        assertNull(HomeCard.daysLeft(null, 0))
        assertNull(HomeCard.daysLeft(0, 0))
        assertEquals(0L, HomeCard.daysLeft(1000, 2000))
        assertEquals(1L, HomeCard.daysLeft(day / 2 + 1, 1))
        assertEquals(3L, HomeCard.daysLeft(3 * day + 1, 1))
        assertEquals(4L, HomeCard.daysLeft(3 * day + 2, 1))
        assertTrue(HomeCard.expired(1000, 1000))
        assertFalse(HomeCard.expired(null, 1000))
    }

    @Test
    fun usedShare() {
        assertNull(HomeCard.usedShare(10, null))
        assertNull(HomeCard.usedShare(10, 0))
        assertEquals(0.5f, HomeCard.usedShare(5, 10)!!, 0.0001f)
        assertEquals(1f, HomeCard.usedShare(50, 10)!!, 0.0001f)
    }

    @Test
    fun pingLevels() {
        assertEquals(HomeCard.Ping.NONE, HomeCard.ping(0))
        assertEquals(HomeCard.Ping.BAD, HomeCard.ping(-1))
        assertEquals(HomeCard.Ping.GOOD, HomeCard.ping(150))
        assertEquals(HomeCard.Ping.MEDIUM, HomeCard.ping(151))
        assertEquals(HomeCard.Ping.MEDIUM, HomeCard.ping(400))
        assertEquals(HomeCard.Ping.BAD, HomeCard.ping(401))
    }

    @Test
    fun clockAndGaming() {
        assertEquals("00:00", HomeCard.clock(-5))
        assertEquals("05:07", HomeCard.clock(307_000))
        assertEquals("1:05:07", HomeCard.clock(3_907_000))
        assertTrue(HomeCard.isGaming("🇩🇪 Игровой"))
        assertTrue(HomeCard.isGaming("Game DE"))
        assertFalse(HomeCard.isGaming("Germany"))
    }

    @Test
    fun automaticPaletteStaysThree() {
        listOf("a", "b", "xyz", "4dd0918faa9a2cca").forEach { assertTrue(HomeCard.paletteIndex(it) in 0..2) }
        assertEquals(8, HomeCard.GRADIENTS.size)
    }

    @Test
    fun whiteTextReadableOnEveryCard() {
        // Large bold text on cards needs 3:1 (WCAG AA large text); every gradient stop is checked.
        (HomeCard.GRADIENTS.flatten() + HomeCard.EXPIRED).forEach {
            assertTrue("%08X".format(it) + " " + HomeCard.whiteContrast(it), HomeCard.whiteContrast(it) >= 3.0)
        }
        assertTrue(HomeCard.whiteContrast(0xFFFFFFFF, 0f) < 1.01)
    }
}
