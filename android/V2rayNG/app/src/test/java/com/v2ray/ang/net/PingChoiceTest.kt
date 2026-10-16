package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class PingChoiceTest {
    @Test
    fun unknownOrMissingMethodIsTheRealRequest() {
        assertEquals(PingChoice.Method.PROXY, PingChoice.method(null))
        assertEquals(PingChoice.Method.PROXY, PingChoice.method("head"))
        assertEquals(PingChoice.Method.TCP, PingChoice.method("tcp"))
        assertEquals(PingChoice.Method.ICMP, PingChoice.method("icmp"))
    }

    @Test
    fun timeoutTurnsASlowResultIntoNotWorking() {
        assertEquals(-1L, PingChoice.applyTimeout(5_200L, 5))
        assertEquals(4_999L, PingChoice.applyTimeout(4_999L, 5))
        assertEquals(-1L, PingChoice.applyTimeout(-1L, 5))
        assertEquals(0L, PingChoice.applyTimeout(0L, 5))
    }

    @Test
    fun storedTimeoutIsCheckedAndDefaulted() {
        assertEquals(5, PingChoice.timeoutSeconds("5"))
        assertEquals(PingChoice.DEFAULT_TIMEOUT, PingChoice.timeoutSeconds(null))
        assertEquals(PingChoice.DEFAULT_TIMEOUT, PingChoice.timeoutSeconds("0"))
        assertEquals(PingChoice.DEFAULT_TIMEOUT, PingChoice.timeoutSeconds("abc"))
    }

    @Test
    fun onlyRealWebAddressesAreAccepted() {
        assertEquals("https://cp.cloudflare.com/generate_204", PingChoice.cleanUrl("  https://cp.cloudflare.com/generate_204 "))
        assertNull(PingChoice.cleanUrl("cp.cloudflare.com"))
        assertNull(PingChoice.cleanUrl("https://"))
        assertNull(PingChoice.cleanUrl("https://bad host/x"))
        assertNull(PingChoice.cleanUrl(null))
    }

    @Test
    fun icmpTimeIsReadFromThePingOutput() {
        val out = "PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.\n64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=23.4 ms\n"
        assertEquals(24L, PingChoice.icmpTime(out))
        assertEquals(1L, PingChoice.icmpTime("64 bytes from x: icmp_seq=1 ttl=64 time<1 ms"))
        assertEquals(-1L, PingChoice.icmpTime("1 packets transmitted, 0 received, 100% packet loss"))
        assertEquals(-1L, PingChoice.icmpTime(null))
    }

    @Test
    fun newWaysAreRecognised() {
        assertEquals(PingChoice.Method.TLS, PingChoice.method("tls"))
        assertEquals(PingChoice.Method.SMART, PingChoice.method("smart"))
        assertTrue(PingChoice.isQuick(PingChoice.Method.TCP))
        assertTrue(PingChoice.isQuick(PingChoice.Method.TLS))
        assertTrue(PingChoice.isQuick(PingChoice.Method.ICMP))
        assertFalse(PingChoice.isQuick(PingChoice.Method.PROXY))
        assertFalse(PingChoice.isQuick(PingChoice.Method.SMART))
    }

    @Test
    fun udpServersAreNeverJudgedByTcp() {
        assertFalse(PingChoice.tcpApplicable(udpBased = true, hasAddress = true))
        assertFalse(PingChoice.tcpApplicable(udpBased = false, hasAddress = false))
        assertTrue(PingChoice.tcpApplicable(udpBased = false, hasAddress = true))
    }

    @Test
    fun aQuickWayThatFailsEverywhereIsRetriedThroughTheServers() {
        assertTrue(PingChoice.allFailed(listOf(-1L, -1L, 0L)))
        assertFalse(PingChoice.allFailed(listOf(-1L, 35L, -1L)))
        // Two servers say too little to blame the way.
        assertFalse(PingChoice.allFailed(listOf(-1L, -1L)))
        assertFalse(PingChoice.allFailed(emptyList()))
    }

    @Test
    fun bestOfTwoTakesTheFasterWorkingAnswer() {
        assertEquals(80L, PingChoice.bestOfTwo(120L, 80L))
        assertEquals(120L, PingChoice.bestOfTwo(120L, -1L))
        assertEquals(-1L, PingChoice.bestOfTwo(-1L, 0L))
    }
}
