package com.v2ray.ang.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BypassOfferLogicTest {
    @Test
    fun offersOnlyWhenOffNotDismissedAndStrongServersExist() {
        assertTrue(BypassOfferLogic.shouldOffer(enabled = false, dismissed = false, strongServers = 1))
        assertFalse(BypassOfferLogic.shouldOffer(enabled = true, dismissed = false, strongServers = 5))
        assertFalse(BypassOfferLogic.shouldOffer(enabled = false, dismissed = true, strongServers = 5))
        assertFalse(BypassOfferLogic.shouldOffer(enabled = false, dismissed = false, strongServers = 0))
    }
}
