package com.v2ray.ang.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTuningTest {
    @Test
    fun backoffStepsAreShortFirstAndCapAtFiveMinutes() {
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L, 300_000L), (1..7).map { Backoff.baseMs(it) })
        assertEquals(15_000L, Backoff.baseMs(0))
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        assertEquals(12_000L, Backoff.delayMs(1, 0.0))
        assertEquals(15_000L, Backoff.delayMs(1, 0.5))
        assertEquals(18_000L, Backoff.delayMs(1, 1.0))
        assertEquals(240_000L, Backoff.delayMs(5, 0.0))
        for (n in 1..8) for (r in listOf(0.0, 0.25, 0.5, 0.99, 1.0, 7.0, -3.0)) {
            val v = Backoff.delayMs(n, r)
            assertTrue(v >= Backoff.baseMs(n) * 0.8 - 1 && v <= Backoff.baseMs(n) * 1.2 + 1)
        }
    }

    @Test
    fun pollingReturnsAsSoonAsReady() = runBlocking {
        var t = 0L
        var polls = 0
        val ok = ReadyWait.poll({ polls++; t >= 600 }, { t += it }, { t }, 3_000)
        assertTrue(ok)
        assertEquals(600L, t)
    }

    @Test
    fun pollingGivesUpAtTheLimit() = runBlocking {
        var t = 0L
        val ok = ReadyWait.poll({ false }, { t += it }, { t }, 3_000)
        assertFalse(ok)
        assertTrue(t in 3_000..3_200)
    }

    @Test
    fun parallelismFollowsMemory() {
        assertEquals(2, AdaptiveParallel.pick(true, 512, 8))
        assertEquals(3, AdaptiveParallel.pick(false, 96, 8))
        assertEquals(4, AdaptiveParallel.pick(false, 160, 8))
        assertEquals(6, AdaptiveParallel.pick(false, 256, 8))
        assertEquals(4, AdaptiveParallel.pick(false, 512, 4))
    }
}
