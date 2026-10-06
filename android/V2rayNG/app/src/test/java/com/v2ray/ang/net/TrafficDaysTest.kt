package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TrafficDaysTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun addAndRoundTrip() {
        val m = TrafficDays.parse(null)
        TrafficDays.add(m, "2026-10-01", 10, 20, 5, 60)
        TrafficDays.add(m, "2026-10-01", 1, 2, 3, 30)
        TrafficDays.add(m, "2026-10-02", -5, 7, 0, 10)
        val again = TrafficDays.parse(TrafficDays.serialize(m))
        assertEquals(TrafficDays.Day("2026-10-01", 11, 22, 8, 90), again["2026-10-01"])
        assertEquals(TrafficDays.Day("2026-10-02", 0, 7, 0, 10), again["2026-10-02"])
        assertEquals(33L, again["2026-10-01"]!!.proxy)
    }

    @Test
    fun brokenInputIsIgnored() {
        assertTrue(TrafficDays.parse("junk;2026-10-01,a,b,c,d;2026-10-02,1,2,3").isEmpty())
        assertEquals(1, TrafficDays.parse("x;2026-10-02,1,2,3,4").size)
    }

    @Test
    fun keepsAtMostNinetyDays() {
        val m = TrafficDays.parse(null)
        repeat(100) { TrafficDays.add(m, TrafficDays.dayKey(it * day, utc), 1, 1, 1, 1) }
        assertEquals(TrafficDays.MAX_DAYS, TrafficDays.parse(TrafficDays.serialize(m)).size)
    }

    @Test
    fun lastDaysFillsGaps() {
        val m = TrafficDays.parse(null)
        val today = 1_700_000_000_000L
        TrafficDays.add(m, TrafficDays.dayKey(today, utc), 5, 5, 0, 1)
        val list = TrafficDays.lastDays(m, 7, today, utc)
        assertEquals(7, list.size)
        assertEquals(10L, list.last().total)
        assertEquals(0L, list.first().total)
        assertEquals(TrafficDays.dayKey(today - 6 * day, utc), list.first().day)
    }

    @Test
    fun formatting() {
        val u = listOf("Б", "КБ", "МБ", "ГБ")
        assertEquals("500 Б", TrafficDays.size(500, u))
        assertEquals("1.5 КБ", TrafficDays.size(1536, u))
        assertEquals("2.0 ГБ", TrafficDays.size(2L * 1024 * 1024 * 1024, u))
        assertEquals("5 мин", TrafficDays.duration(300, "ч", "мин"))
        assertEquals("2 ч 05 мин", TrafficDays.duration(7500, "ч", "мин"))
    }
}
