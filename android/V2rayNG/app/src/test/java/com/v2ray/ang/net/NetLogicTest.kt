package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetLogicTest {

    // ---- physical network ----

    @Test
    fun tunnelAndNoInternetAreNotPhysicalNetworks() {
        assertNull(NetworkPicker.classify(internet = true, vpn = true, cellular = false, wifi = false, ethernet = false))
        assertNull(NetworkPicker.classify(internet = false, vpn = false, cellular = true, wifi = false, ethernet = false))
        assertNull(NetworkPicker.classify(internet = true, vpn = false, cellular = false, wifi = false, ethernet = false))
    }

    @Test
    fun transportsAreClassified() {
        assertEquals(NetType.CELLULAR, NetworkPicker.classify(true, false, cellular = true, wifi = false, ethernet = false))
        assertEquals(NetType.WIFI, NetworkPicker.classify(true, false, cellular = false, wifi = true, ethernet = false))
        assertEquals(NetType.ETHERNET, NetworkPicker.classify(true, false, cellular = false, wifi = false, ethernet = true))
    }

    @Test
    fun wifiBeatsCellularWhenBothAreValidated() {
        val pick = NetworkPicker.pick(
            listOf(NetCandidate(1, NetType.CELLULAR, true), NetCandidate(2, NetType.WIFI, true))
        )
        assertEquals(NetType.WIFI, pick.primary?.type)
        assertEquals(listOf(NetType.CELLULAR), pick.others.map { it.type })
    }

    @Test
    fun validatedMobileBeatsUnvalidatedWifi() {
        val pick = NetworkPicker.pick(
            listOf(NetCandidate(1, NetType.WIFI, false), NetCandidate(2, NetType.CELLULAR, true))
        )
        assertEquals(NetType.CELLULAR, pick.primary?.type)
    }

    @Test
    fun unvalidatedNetworkIsStillChosenWhenItIsTheOnlyOne() {
        val pick = NetworkPicker.pick(listOf(NetCandidate(7, NetType.CELLULAR, false)))
        assertEquals(7L, pick.primary?.key)
        assertTrue(pick.others.isEmpty())
    }

    @Test
    fun noNetworks() {
        val pick = NetworkPicker.pick(emptyList())
        assertNull(pick.primary)
    }

    @Test
    fun ethernetBeatsWifi() {
        val pick = NetworkPicker.pick(
            listOf(NetCandidate(1, NetType.WIFI, true), NetCandidate(2, NetType.ETHERNET, true))
        )
        assertEquals(NetType.ETHERNET, pick.primary?.type)
    }

    // ---- IP parsing ----

    @Test
    fun parsesPlainAndJsonReplies() {
        assertEquals("93.184.216.34", IpParser.parse("93.184.216.34\n"))
        assertEquals("93.184.216.34", IpParser.parse("\"93.184.216.34\""))
        assertEquals("10.0.0.1", IpParser.parse("""{"ip":"10.0.0.1","country":"RU"}"""))
        assertEquals("1.2.3.4", IpParser.parse("<html>Your IP: <b>1.2.3.4</b></html>"))
    }

    @Test
    fun ignoresOutOfRangeAndVersionLikeNumbers() {
        assertNull(IpParser.parse("version 999.300.1.2 build"))
        assertNull(IpParser.parse("1.2.3"))
        assertNull(IpParser.parse(""))
        assertNull(IpParser.parse(null))
    }

    @Test
    fun parsesIpv6() {
        assertEquals("2001:db8::1", IpParser.parse("2001:db8::1\n"))
        assertEquals("2a00:1450:4001:81c::200e", IpParser.parse("your ip is 2a00:1450:4001:81c::200e ok"))
        assertTrue(IpParser.isIpv6("::1"))
        assertTrue(IpParser.isIpv6("::ffff:1.2.3.4"))
        assertFalse(IpParser.isIpv6("12:34"))
        assertFalse(IpParser.isIpv6("2001:db8:::1"))
        assertFalse(IpParser.isIpv6("1:2:3:4:5:6:7:8:9"))
        assertFalse(IpParser.isIpv6("gggg::1"))
    }

    @Test
    fun ipv4IsPreferredOverIpv6() {
        assertEquals("8.8.8.8", IpParser.parse("2001:db8::1 and 8.8.8.8"))
    }

    // ---- verdict of a check ----

    private val ok204 = ProbeStep.OK_204
    private val goodContent = ProbeStep.ok(200, 5000)

    @Test
    fun healthyServerPasses() {
        val v = BypassVerdict.decide(ok204, goodContent, "5.6.7.8", "9.9.9.9", ok204)
        assertEquals(Outcome.OK, v.outcome)
        assertFalse(v.ipUnknown)
    }

    @Test
    fun gstatic200IsACaptivePageNotSuccess() {
        val v = BypassVerdict.decide(ProbeStep.ok(200), goodContent, "5.6.7.8", "9.9.9.9", null)
        assertEquals(Outcome.FAIL, v.outcome)
        assertEquals(FailReason.TAMPERED, v.reason)
        assertEquals(FailReason.TAMPERED, BypassVerdict.decide(ProbeStep(ProbeStep.Kind.BAD_STATUS, 302), null, null, null, null).reason)
    }

    @Test
    fun timeoutsAndRefusalsHaveHumanReasons() {
        assertEquals(FailReason.NO_GSTATIC, BypassVerdict.decide(ProbeStep(ProbeStep.Kind.TIMEOUT), null, null, null, null).reason)
        assertEquals(FailReason.NO_GSTATIC, BypassVerdict.decide(ProbeStep(ProbeStep.Kind.BAD_STATUS, 503), null, null, null, null).reason)
        assertEquals(FailReason.PROXY_DOWN, BypassVerdict.decide(ProbeStep(ProbeStep.Kind.REFUSED), null, null, null, null).reason)
    }

    @Test
    fun connectedButNoDataIsCaught() {
        val tiny = ProbeStep.ok(200, 0)
        assertEquals(FailReason.NO_DATA, BypassVerdict.decide(ok204, tiny, "1.1.1.1", "2.2.2.2", null).reason)
        assertEquals(FailReason.NO_DATA, BypassVerdict.decide(ok204, ProbeStep(ProbeStep.Kind.TIMEOUT), null, null, null).reason)
    }

    @Test
    fun sameIpMeansTheProxyIsNotApplied() {
        val v = BypassVerdict.decide(ok204, goodContent, "9.9.9.9", "9.9.9.9", null)
        assertEquals(Outcome.FAIL, v.outcome)
        assertEquals(FailReason.IP_SAME, v.reason)
    }

    @Test
    fun unknownIpIsNotAFailureButIsFlagged() {
        val v = BypassVerdict.decide(ok204, goodContent, null, "9.9.9.9", null)
        assertEquals(Outcome.OK, v.outcome)
        assertTrue(v.ipUnknown)
        assertEquals(Outcome.OK, BypassVerdict.decide(ok204, goodContent, "5.5.5.5", null, null).outcome)
    }

    @Test
    fun serverThatDiesAfterHandshakeFailsTheStabilityStep() {
        val v = BypassVerdict.decide(ok204, goodContent, "5.6.7.8", "9.9.9.9", ProbeStep(ProbeStep.Kind.TIMEOUT))
        assertEquals(Outcome.FAIL, v.outcome)
        assertEquals(FailReason.UNSTABLE, v.reason)
    }

    @Test
    fun cancelledCheckIsUnknownNotAVerdict() {
        val v = BypassVerdict.decide(ProbeStep(ProbeStep.Kind.TIMEOUT), null, null, null, null, cancelled = true)
        assertEquals(Outcome.UNKNOWN, v.outcome)
        assertNull(v.reason)
    }

    @Test
    fun softCheckWithoutStabilityStep() {
        assertEquals(Outcome.OK, BypassVerdict.decide(ok204, goodContent, null, null, stable = null).outcome)
    }

    // ---- snapshot ----

    @Test
    fun snapshotRoundTripsWithSpecialCharacters() {
        val s = BypassSnapshot("server", "guid-1", "🇩🇪 Berlin & Co = fast", "1.2.3.4:443", "DE", "wifi", 1234L)
        assertEquals(s, BypassSnapshot.decode(s.encode()))
    }

    @Test
    fun brokenSnapshotIsIgnored() {
        assertNull(BypassSnapshot.decode(null))
        assertNull(BypassSnapshot.decode(""))
        assertNull(BypassSnapshot.decode("a&b"))
        assertNull(BypassSnapshot.decode("a&b&c&d&e&f&notanumber"))
    }

    @Test
    fun aChainOfBypassServersKeepsTheFirstSnapshot() {
        val first = BypassSnapshot("server", "A", "A", "a", "DE", "wifi", 1L)
        assertTrue(BypassSnapshot.shouldCreate(null))
        assertFalse(BypassSnapshot.shouldCreate(first))
    }

    // ---- return ----

    private val fine = OriginState(exists = true, alive = true, pingMs = 120)

    @Test
    fun healthyOriginIsReturnedTo() {
        assertEquals(ReturnAction.RETURN, ReturnLogic.decide("server", false, fine, 400, allowReplacement = true))
    }

    @Test
    fun alreadyOnOriginDoesNothing() {
        assertEquals(ReturnAction.STAY, ReturnLogic.decide("server", true, fine, 400, allowReplacement = true))
        assertEquals(ReturnAction.STAY, ReturnLogic.decide("best", true, fine, 400, allowReplacement = true))
    }

    @Test
    fun deadSlowOrMissingOriginSearchesAReplacementOnWifi() {
        assertEquals(ReturnAction.SEARCH_REPLACEMENT, ReturnLogic.decide("server", false, OriginState(true, false, 0), 400, true))
        assertEquals(ReturnAction.SEARCH_REPLACEMENT, ReturnLogic.decide("server", false, OriginState(true, true, 900), 400, true))
        assertEquals(ReturnAction.SEARCH_REPLACEMENT, ReturnLogic.decide("server", false, OriginState(false, false, 0), 400, true))
        assertEquals(ReturnAction.SEARCH_REPLACEMENT, ReturnLogic.decide("server", false, null, 400, true))
    }

    @Test
    fun pingExactlyAtTheLimitIsStillFine() {
        assertEquals(ReturnAction.RETURN, ReturnLogic.decide("server", false, OriginState(true, true, 400), 400, true))
    }

    @Test
    fun bestModeRunsBestOnWifi() {
        assertEquals(ReturnAction.RUN_BEST, ReturnLogic.decide("best", false, fine, 400, true))
    }

    @Test
    fun onMobileNetworkOnlyAWorkingOriginIsActedOn() {
        assertEquals(ReturnAction.RETURN, ReturnLogic.decide("server", false, fine, 400, allowReplacement = false))
        assertEquals(ReturnAction.RETURN, ReturnLogic.decide("best", false, fine, 400, allowReplacement = false))
        assertEquals(ReturnAction.STAY, ReturnLogic.decide("server", false, OriginState(true, false, 0), 400, allowReplacement = false))
        assertEquals(ReturnAction.STAY, ReturnLogic.decide("best", false, null, 400, allowReplacement = false))
    }

    @Test
    fun replacementOrderFavoritesSameCountryThenFastest_noRussia() {
        val russian = setOf("ru1")
        val country = mapOf("de1" to "DE", "de2" to "DE", "fr1" to "FR", "ru1" to "RU", "nl1" to "NL")
        val delays = mapOf("de1" to 300L, "de2" to 100L, "fr1" to 50L, "nl1" to 0L, "ru1" to 10L)
        val order = ReturnLogic.orderReplacement(
            guids = listOf("fr1", "de1", "nl1", "ru1", "de2", "fav"),
            favorites = setOf("fav"),
            sameCountry = { country[it] == "DE" },
            isRussian = { it in russian },
            knownDelay = { delays[it] ?: 0L },
        )
        assertEquals(listOf("fav", "de2", "de1", "fr1", "nl1"), order)
    }

    @Test
    fun bypassSearchKeepsRussianServersWhenNotExcluded() {
        val order = ReturnLogic.orderReplacement(
            guids = listOf("ru1", "de1"),
            favorites = emptySet(),
            sameCountry = { false },
            isRussian = { it == "ru1" },
            knownDelay = { if (it == "ru1") 20L else 80L },
            excludeRussia = false,
        )
        assertEquals(listOf("ru1", "de1"), order)
    }

    // ---- bad marks ----

    @Test
    fun badMarksExpire() {
        val ttl = 10 * 60_000L
        val marks = mapOf("a" to 1_000L)
        assertTrue(BadMarks.isBad(marks, "a", 1_000L + ttl - 1, ttl))
        assertFalse(BadMarks.isBad(marks, "a", 1_000L + ttl, ttl))
        assertFalse(BadMarks.isBad(marks, "b", 1_500L, ttl))
    }

    @Test
    fun badMarksRoundTripAndDropExpired() {
        val ttl = 60_000L
        val text = BadMarks.encode(mapOf("a" to 100_000L, "b" to 10_000L))
        assertEquals(mapOf("a" to 100_000L), BadMarks.decode(text, now = 120_000L, ttlMs = ttl))
        assertTrue(BadMarks.decode("garbage;x=", 0, ttl).isEmpty())
    }

    // ---- ping url migration ----

    @Test
    fun onlyTheOldDefaultPingUrlIsMigrated() {
        assertEquals(PingUrls.PRIMARY, PingUrls.migrate("https://www.google.com/generate_204"))
        assertEquals(PingUrls.PRIMARY, PingUrls.migrate(""))
        assertEquals(PingUrls.PRIMARY, PingUrls.migrate(null))
        assertNull(PingUrls.migrate("https://www.gstatic.com/generate_204"))
        assertNull(PingUrls.migrate("https://my.example.com/ping"))
    }
}
