package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassHistoryLogicTest {
    private val t0 = 1_000_000_000L
    private val minute = 60_000L

    @Test
    fun oneFailureIsOnlyCountedTheSecondInARowBansAndTheBanDoublesEachTime() {
        var e = BypassHistoryLogic.recordFail(null, t0)
        assertFalse(BypassHistoryLogic.isBad(e, t0 + minute, 10))
        e = BypassHistoryLogic.recordFail(e, t0 + 2 * minute)
        assertTrue(BypassHistoryLogic.isBad(e, t0 + 2 * minute + 9 * minute, 10))
        assertFalse(BypassHistoryLogic.isBad(e, t0 + 2 * minute + 10 * minute, 10))
        e = BypassHistoryLogic.recordFail(e, t0 + 13 * minute)
        assertTrue(BypassHistoryLogic.isBad(e, t0 + 13 * minute + 19 * minute, 10))
        assertFalse(BypassHistoryLogic.isBad(e, t0 + 13 * minute + 20 * minute, 10))
    }

    @Test
    fun theGrowthStopsAtEightTimes() {
        var e: HistoryEntry? = null
        repeat(12) { e = BypassHistoryLogic.recordFail(e, t0) }
        assertEquals(80 * minute, BypassHistoryLogic.badTtlMs(e!!.streak, 10))
    }

    @Test
    fun aSuccessClearsTheBadMarkAndCountsAsGoodForADay() {
        var e = BypassHistoryLogic.recordFail(null, t0)
        e = BypassHistoryLogic.recordFail(e, t0)
        e = BypassHistoryLogic.recordOk(e, t0 + minute, 300)
        assertFalse(BypassHistoryLogic.isBad(e, t0 + 2 * minute, 10))
        assertTrue(BypassHistoryLogic.isGood(e, t0 + 23 * 60 * minute))
        assertFalse(BypassHistoryLogic.isGood(e, t0 + 25 * 60 * minute))
    }

    @Test
    fun theStoredFormRoundTrips() {
        val map = mapOf(
            "abc@cellular:25001" to HistoryEntry(2, 1, 5L, 3L, 0, 250),
            "def@cellular" to HistoryEntry(0, 3, 0L, 9L, 3, 0),
        )
        assertEquals(map, BypassHistoryLogic.decode(BypassHistoryLogic.encode(map)))
        assertTrue(BypassHistoryLogic.decode("garbage;x=1,2;=").isEmpty())
    }

    @Test
    fun resultsOfDeletedServersAreDropped() {
        val map = mapOf("aaa@cellular" to HistoryEntry(), "bbb@cellular" to HistoryEntry())
        assertEquals(setOf("aaa@cellular"), BypassHistoryLogic.prune(map, setOf("aaa")).keys)
    }

    @Test
    fun fingerprintIsStableAndDependsOnWhatMakesTheServer() {
        val a = BypassHistoryLogic.fingerprint(listOf("vless", "1.2.3.4", "443", "tcp", "yandex.ru"))
        assertEquals(a, BypassHistoryLogic.fingerprint(listOf("VLESS", " 1.2.3.4", "443", "tcp", "yandex.ru")))
        assertNotEquals(a, BypassHistoryLogic.fingerprint(listOf("vless", "1.2.3.4", "8443", "tcp", "yandex.ru")))
        assertEquals(16, a.length)
    }

    @Test
    fun operatorBucketIsNumericOrGeneral() {
        assertEquals("cellular:25001", BypassHistoryLogic.bucket("25001"))
        assertEquals("cellular", BypassHistoryLogic.bucket(""))
        assertEquals("cellular", BypassHistoryLogic.bucket("MTS RUS"))
        assertEquals("cellular", BypassHistoryLogic.bucket(null))
    }
}
