package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetDiagnosisTest {
    private fun hosts(vararg ok: Pair<Boolean, Boolean>) = ok.mapIndexed { i, (tcp, http) -> HostProbe("h$i", tcp, http) }
    private val up = true to true
    private val tcpOnly = true to false
    private val down = false to false

    private fun d(domestic: List<HostProbe>, foreign: List<HostProbe>, partial: Boolean = true) = NetDiagnosis.diagnose(domestic, foreign, partial).diagnosis

    private val domesticUp = hosts(up, up, down)
    private val domesticDown = hosts(down, down, down)

    @Test
    fun nothingAnswersIsNoNetwork() = assertEquals(LinkDiagnosis.NO_NETWORK, d(domesticDown, hosts(down, down, down, down, down)))

    @Test
    fun mostForeignHostsAnsweringMeansTheServerIsTheProblem() {
        assertEquals(LinkDiagnosis.SERVER_DOWN, d(domesticUp, hosts(up, up, up, down, down)))
        assertEquals(LinkDiagnosis.SERVER_DOWN, d(domesticUp, hosts(up, up, up, up, up)))
        assertEquals(LinkDiagnosis.SERVER_DOWN, d(domesticDown, hosts(up, up, up, up, down)))
    }

    @Test
    fun domesticOnlyIsAWhitelist() {
        assertEquals(LinkDiagnosis.WHITELIST, d(domesticUp, hosts(down, down, down, down, down)))
        // TCP connects but nothing is answered over HTTP: still a whitelist, said so in the reason
        val r = NetDiagnosis.diagnose(domesticUp, hosts(tcpOnly, tcpOnly, tcpOnly, down, down), true)
        assertEquals(LinkDiagnosis.WHITELIST, r.diagnosis)
        assertTrue(r.why, "TCP connects" in r.why)
    }

    @Test
    fun oneForeignHostOutOfFiveIsAPartialWhitelistOnlyWithTheRule() {
        assertEquals(LinkDiagnosis.WHITELIST, d(domesticUp, hosts(up, down, down, down, down), partial = true))
        assertEquals(LinkDiagnosis.UNSURE, d(domesticUp, hosts(up, down, down, down, down), partial = false))
    }

    @Test
    fun aMinorityThatAnswersIsContradictory() = assertEquals(LinkDiagnosis.UNSURE, d(domesticUp, hosts(up, up, down, down, down)))

    @Test
    fun foreignAnswersButDomesticDoesNotIsUnsureNotAWhitelist() {
        assertEquals(LinkDiagnosis.UNSURE, d(domesticDown, hosts(up, down, down, down, down)))
        assertEquals(LinkDiagnosis.UNSURE, d(domesticDown, hosts(tcpOnly, down, down, down, down)))
    }

    @Test
    fun theReasonShowsTheCounts() {
        val r = NetDiagnosis.diagnose(domesticUp, hosts(up, up, up, down, down), true)
        assertTrue(r.why, "foreign-http=3/5" in r.why)
    }
}
