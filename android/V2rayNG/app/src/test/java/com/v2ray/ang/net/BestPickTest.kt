package com.v2ray.ang.net

import com.v2ray.ang.net.BestPick.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BestPickTest {
    @Test
    fun orderFavoritesGoodUnknownBad() {
        val list = listOf(
            Candidate("bad", false, -1, false),
            Candidate("unknown1", false, 0, false),
            Candidate("slow", false, 300, false),
            Candidate("fast", false, 40, false),
            Candidate("fav", true, 500, false),
            Candidate("ru", false, 10, true),
            Candidate("unknown2", false, 0, false),
        )
        assertEquals(listOf("fav", "fast", "slow", "unknown1", "unknown2", "bad"), BestPick.order(list))
    }

    @Test
    fun noDuplicatesAndEmpty() {
        assertEquals(emptyList<String>(), BestPick.order(emptyList()))
        assertEquals(listOf("a"), BestPick.order(listOf(Candidate("a", true, 5, false), Candidate("a", false, 5, false))))
    }

    @Test
    fun waves() {
        assertEquals(24, BestPick.waveSize(0))
        assertEquals(48, BestPick.waveSize(1))
        assertEquals(96, BestPick.waveSize(7))
    }

    @Test
    fun stopRule() {
        assertTrue(BestPick.enough(120, 80))
        assertTrue(BestPick.enough(350, 80))
        assertFalse(BestPick.enough(351, 80))
        assertFalse(BestPick.enough(null, 80))
        assertTrue(BestPick.enough(null, 0))
        assertTrue(BestPick.enough(900, 0))
        assertFalse(BestPick.enough(-1, 5))
        assertFalse(BestPick.enough(0, 5))
    }
}
