package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassEpisodeTest {
    @Test
    fun summaryCountsTimesAndMistakes() {
        val e = EpisodeStats(startedAt = 1_000L)
        e.quickPath("failed")
        e.isolated(passed = true, timedOut = false)
        e.isolated(passed = false, timedOut = true)
        e.isolated(passed = true, timedOut = false)
        e.live(LiveOutcome.FAIL, FailReason.NO_DATA, now = 5_000L)
        e.reloadRejected()
        e.live(LiveOutcome.OK, null, now = 13_300L)
        e.notRecorded(2)
        val line = e.summary(now = 14_000L, result = EpisodeResult.OK)
        assertTrue(line, line.startsWith("episode: result=OK first-ok=12.3s total=13.0s quick=failed"))
        assertTrue(line, "tested=3 passed=2 timed-out=1 live-ok=1 live-fail=1 false-positive=1" in line)
        assertTrue(line, "reasons=NO_DATA:1 reload-rejected=1 not-recorded=2 bans=0" in line)
    }

    @Test
    fun noOkMeansADash() {
        val line = EpisodeStats(0L).summary(2_000L, EpisodeResult.NO_SERVER)
        assertTrue(line, "first-ok=- " in line && "reasons=- " in line)
    }

    @Test
    fun theReportCarriesNoAddressesOrLinks() {
        val log = "12:00:01  live check of Server 1 ok\n12:00:02  vless://abc@1.2.3.4:443?x=1 failed https://sub.example/t/1 uuid 123e4567-e89b-12d3-a456-426614174000"
        val text = BypassReport.build(listOf("build" to "120", "net" to "cellular"), log)
        assertTrue(text.startsWith("build: 120\nnet: cellular"))
        assertFalse(text, "1.2.3.4" in text || "sub.example" in text || "abc@" in text || "426614174000" in text)
        assertTrue("live check of Server 1 ok" in text)
        assertEquals(1, Regex("\\[ip]|vless://\\*\\*\\*").findAll(text).count().coerceAtMost(1))
    }
}
