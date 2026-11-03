package com.v2ray.ang.net

import com.v2ray.ang.net.LinkQuality.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkQualityTest {
    @Test
    fun tooFewSamplesGiveNoVerdict() {
        assertNull(LinkQuality.evaluate(emptyList()))
        assertNull(LinkQuality.evaluate(listOf(30, 31, 30, 29)))
    }

    @Test
    fun steadyServerIsGreat() {
        val r = LinkQuality.evaluate(listOf(32, 33, 31, 32, 34, 32, 33, 32))!!
        assertEquals(Grade.GREAT, r.grade)
        assertEquals(0, r.lossPercent)
        assertEquals(32L, r.pingMs)
        assertEquals(1L, r.jitterMs)
    }

    @Test
    fun lowPingThatJumpsIsVideoOnly() {
        val r = LinkQuality.evaluate(listOf(30, 70, 32, 75, 31, 72, 33, 70))!!
        assertEquals(Grade.VIDEO_ONLY, r.grade)
    }

    @Test
    fun lostAttemptsOfAGoodPingMakeItVideoOnlyThenBad() {
        // 1 of 8 lost = 12% -> video only; 3 of 8 lost = 37% -> bad
        assertEquals(Grade.VIDEO_ONLY, LinkQuality.evaluate(listOf(30, -1, 31, 30, 32, 31, 30, 31))!!.grade)
        assertEquals(Grade.BAD, LinkQuality.evaluate(listOf(30, -1, -1, 30, 32, -1, 30, 31))!!.grade)
    }

    @Test
    fun noAnswerAtAllIsBad() {
        val r = LinkQuality.evaluate(listOf(-1, -1, -1, -1, -1, -1))!!
        assertEquals(Grade.BAD, r.grade)
        assertEquals(100, r.lossPercent)
    }

    @Test
    fun pingIsTheMedianNotTheMean() {
        val r = LinkQuality.evaluate(listOf(30, 30, 30, 30, 900, 30, 30, 30))!!
        assertEquals(30L, r.pingMs)
    }
}
