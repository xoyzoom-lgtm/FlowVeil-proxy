package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassRulesTest {
    @Test
    fun anotherServerPickedByTheUserIsHisChoice() {
        assertTrue(BypassRules.isUserChoice(current = "B", active = "A", lastOurs = "A", switching = false))
    }

    @Test
    fun aServerWeSwitchedToOurselvesIsNotTheUsersChoice() {
        // We went to the old server "O" to check it and the check was not confirmed: still ours.
        assertFalse(BypassRules.isUserChoice(current = "O", active = "A", lastOurs = "O", switching = false))
    }

    @Test
    fun nothingIsDecidedDuringOurOwnSwitchOrWithoutAnEpisode() {
        assertFalse(BypassRules.isUserChoice(current = "B", active = "A", lastOurs = "A", switching = true))
        assertFalse(BypassRules.isUserChoice(current = "B", active = null, lastOurs = null, switching = false))
        assertFalse(BypassRules.isUserChoice(current = "A", active = "A", lastOurs = null, switching = false))
    }

    @Test
    fun aFailedReturnGoesBackToTheWorkingBypassServer() {
        // Was on bypass "A", tried the old server "O", it did not hold: back to "A" (the old code compared with the
        // server before the try, found them equal and stayed on "O").
        assertEquals("A", BypassRules.backAfterFailedReturn(active = "A", before = "A", nowOn = "O"))
    }

    @Test
    fun aReturnThatDidNotSwitchNeedsNoSwitchBack() {
        assertNull(BypassRules.backAfterFailedReturn(active = "A", before = "A", nowOn = "A"))
    }

    @Test
    fun withoutAnActiveServerTheOneBeforeTheTryIsUsed() {
        assertEquals("B", BypassRules.backAfterFailedReturn(active = null, before = "B", nowOn = "O"))
    }

    @Test
    fun liveCheckTriesMoreThanOneAddressWhitelistFirst() {
        assertEquals(PingUrls.WHITELIST, BypassRules.LIVE_CHECK_URLS.first())
        assertTrue(BypassRules.LIVE_CHECK_URLS.size >= 3)
        assertTrue(PingUrls.TELEGRAM in BypassRules.LIVE_CHECK_URLS)
    }
}
