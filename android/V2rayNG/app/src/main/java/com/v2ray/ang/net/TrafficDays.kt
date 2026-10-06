package com.v2ray.ang.net

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Traffic and connected time per calendar day, kept on the device only (nothing is sent anywhere).
 * Stored as one short string "day,proxyUp,proxyDown,direct,seconds;…", at most [MAX_DAYS] days.
 */
object TrafficDays {
    const val MAX_DAYS = 90

    data class Day(val day: String, val proxyUp: Long, val proxyDown: Long, val direct: Long, val seconds: Long) {
        val proxy: Long get() = proxyUp + proxyDown
        val total: Long get() = proxy + direct
    }

    fun dayKey(millis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(millis)

    fun parse(text: String?): MutableMap<String, Day> {
        val out = LinkedHashMap<String, Day>()
        if (text.isNullOrBlank()) return out
        text.split(';').forEach { entry ->
            val p = entry.split(',')
            if (p.size != 5 || p[0].length != 10) return@forEach
            val nums = p.drop(1).map { it.toLongOrNull() ?: return@forEach }
            out[p[0]] = Day(p[0], nums[0], nums[1], nums[2], nums[3])
        }
        return out
    }

    fun serialize(days: Map<String, Day>): String =
        days.values.sortedBy { it.day }.takeLast(MAX_DAYS)
            .joinToString(";") { "${it.day},${it.proxyUp},${it.proxyDown},${it.direct},${it.seconds}" }

    /** Adds one measurement to [day]; negative numbers are ignored. */
    fun add(days: MutableMap<String, Day>, day: String, proxyUp: Long, proxyDown: Long, direct: Long, seconds: Long) {
        val old = days[day] ?: Day(day, 0, 0, 0, 0)
        days[day] = Day(
            day,
            old.proxyUp + proxyUp.coerceAtLeast(0), old.proxyDown + proxyDown.coerceAtLeast(0),
            old.direct + direct.coerceAtLeast(0), old.seconds + seconds.coerceAtLeast(0),
        )
    }

    /** The last [n] days ending with [today], oldest first; a day without data is an empty [Day]. */
    fun lastDays(days: Map<String, Day>, n: Int, today: Long, zone: TimeZone = TimeZone.getDefault()): List<Day> {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = today }
        val keys = ArrayList<String>()
        repeat(n) {
            keys.add(dayKey(cal.timeInMillis, zone))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return keys.reversed().map { days[it] ?: Day(it, 0, 0, 0, 0) }
    }

    fun sum(list: List<Day>): Day = Day("", list.sumOf { it.proxyUp }, list.sumOf { it.proxyDown }, list.sumOf { it.direct }, list.sumOf { it.seconds })

    /** "1.2 ГБ" style size with the unit given by the caller's labels (bytes → KB/MB/GB, 1024 based). */
    fun size(bytes: Long, units: List<String>): String {
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${bytes} ${units[0]}" else String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    /** "2 ч 05 мин" / "5 мин" / "0 мин" with labels supplied by the caller. */
    fun duration(seconds: Long, hour: String, minute: String): String {
        val m = seconds / 60
        return if (m >= 60) "${m / 60} $hour ${"%02d".format(m % 60)} $minute" else "$m $minute"
    }
}
