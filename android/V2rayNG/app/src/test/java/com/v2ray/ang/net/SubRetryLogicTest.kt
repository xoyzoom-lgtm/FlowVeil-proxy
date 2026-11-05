package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubRetryLogicTest {
    @Test
    fun emptyEnabledSubscriptionWithLinkIsRetried() {
        assertTrue(SubRetryLogic.needsRetry(enabled = true, url = "https://example.org/sub", serverCount = 0))
    }

    @Test
    fun otherSubscriptionsAreNotRetried() {
        assertFalse(SubRetryLogic.needsRetry(enabled = false, url = "https://example.org/sub", serverCount = 0))
        assertFalse(SubRetryLogic.needsRetry(enabled = true, url = " ", serverCount = 0))
        assertFalse(SubRetryLogic.needsRetry(enabled = true, url = "https://example.org/sub", serverCount = 3))
    }

    @Test
    fun pausesGrowAndStopGrowing() {
        assertEquals(20_000L, SubRetryLogic.delayMs(0, 0.5))
        assertEquals(45_000L, SubRetryLogic.delayMs(1, 0.5))
        assertEquals(300_000L, SubRetryLogic.delayMs(4, 0.5))
        assertEquals(300_000L, SubRetryLogic.delayMs(50, 0.5))
        assertEquals(20_000L, SubRetryLogic.delayMs(-3, 0.5))
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        assertEquals(16_000L, SubRetryLogic.delayMs(0, 0.0))
        assertEquals(24_000L, SubRetryLogic.delayMs(0, 1.0))
        assertEquals(24_000L, SubRetryLogic.delayMs(0, 9.0))
    }
}
