package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickPathTest {
    private val now = 1_000_000_000L
    private val hour = 3_600_000L
    private fun f(id: String, ok: Long = 0, streak: Int = 0, ping: Long = 0, recent: Long = 0) = QuickFacts(id, ok, streak, ping, recent)

    @Test
    fun nothingKnownMeansNoQuickPath() {
        assertNull(QuickPath.pick(emptyList(), now, 1500))
        assertNull(QuickPath.pick(listOf(f("a"), f("b")), now, 1500))
    }

    @Test
    fun aServerThatWorkedWithinADayWithoutFailingSinceQualifies() {
        assertEquals("a", QuickPath.pick(listOf(f("a", ok = now - 2 * hour, ping = 300), f("b")), now, 1500))
    }

    @Test
    fun anOldSuccessOrALaterFailureDoesNot() {
        assertNull(QuickPath.pick(listOf(f("old", ok = now - 25 * hour), f("failed", ok = now - hour, streak = 1)), now, 1500))
    }

    @Test
    fun theFastRecentOneWins() {
        val facts = listOf(
            f("slow", ok = now - hour, ping = 3000),
            f("older", ok = now - 5 * hour, ping = 200),
            f("newer", ok = now - hour, ping = 250),
        )
        assertEquals("newer", QuickPath.pick(facts, now, 1500))
    }

    @Test
    fun withoutHistoryTheLastSuccessAsATargetIsUsed() {
        assertEquals("t", QuickPath.pick(listOf(f("t", recent = now - hour), f("x")), now, 1500))
        assertNull(QuickPath.pick(listOf(f("t", recent = now - 30 * hour)), now, 1500))
    }

    @Test
    fun historyBeatsTheLastSuccessWhenBothExist() {
        assertEquals("h", QuickPath.pick(listOf(f("h", ok = now - 3 * hour, ping = 400), f("t", recent = now - hour)), now, 1500))
    }
}
