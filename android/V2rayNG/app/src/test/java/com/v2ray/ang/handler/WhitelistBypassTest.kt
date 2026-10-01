package com.v2ray.ang.handler

import com.v2ray.ang.handler.WhitelistBypass.Diagnosis
import org.junit.Assert.assertEquals
import org.junit.Test

class WhitelistBypassTest {

    @Test
    fun workingProxyIsAlwaysOk() {
        assertEquals(Diagnosis.OK, WhitelistBypass.diagnose(viaProxy = true, domesticDirect = false, foreignDirect = false))
        assertEquals(Diagnosis.OK, WhitelistBypass.diagnose(viaProxy = true, domesticDirect = true, foreignDirect = false))
    }

    @Test
    fun onlyDomesticReachableIsWhitelist() {
        assertEquals(Diagnosis.WHITELIST, WhitelistBypass.diagnose(viaProxy = false, domesticDirect = true, foreignDirect = false))
    }

    @Test
    fun nothingReachableIsNoNetworkNotWhitelist() {
        assertEquals(Diagnosis.NO_NETWORK, WhitelistBypass.diagnose(viaProxy = false, domesticDirect = false, foreignDirect = false))
    }

    @Test
    fun openInternetWithDeadServerIsServerDown() {
        assertEquals(Diagnosis.SERVER_DOWN, WhitelistBypass.diagnose(viaProxy = false, domesticDirect = true, foreignDirect = true))
        assertEquals(Diagnosis.SERVER_DOWN, WhitelistBypass.diagnose(viaProxy = false, domesticDirect = false, foreignDirect = true))
    }

    @Test
    fun candidatesFavoritesThenRecentThenFastest() {
        val order = WhitelistBypass.orderCandidates(
            guids = listOf("slow", "fast", "untested", "recent", "fav", "fast"),
            favorites = setOf("fav"),
            recentSuccess = mapOf("recent" to 1000L),
            knownDelay = { mapOf("slow" to 900L, "fast" to 100L, "fav" to 500L)[it] ?: 0L },
        )
        assertEquals(listOf("fav", "recent", "fast", "slow", "untested"), order)
    }

    @Test
    fun backoffGrowsAndCaps() {
        assertEquals(15_000L, WhitelistBypass.backoffMillis(1))
        assertEquals(30_000L, WhitelistBypass.backoffMillis(2))
        assertEquals(60_000L, WhitelistBypass.backoffMillis(3))
        assertEquals(120_000L, WhitelistBypass.backoffMillis(4))
        assertEquals(300_000L, WhitelistBypass.backoffMillis(5))
        assertEquals(300_000L, WhitelistBypass.backoffMillis(50))
    }
}
