package com.v2ray.ang.net

/** Plain rules behind the new home screen, kept out of Compose so they can be tested. */
object HomeCard {
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Card gradients from the v4 mockup, ARGB. */
    val GRADIENTS: List<List<Long>> = listOf(
        listOf(0xFF4D6BFF, 0xFF7A4DFF, 0xFFA24DD8),
        listOf(0xFFFF9A3D, 0xFFF0585F, 0xFFD8438F),
        listOf(0xFF25C48A, 0xFF1A9A9A, 0xFF1C6FB0),
        // Extra choices of the appearance picker (never picked automatically: the automatic choice stays hash % 3).
        listOf(0xFFE0457B, 0xFFB5179E, 0xFF7209B7),
        listOf(0xFF2D6CDF, 0xFF1E3A8A, 0xFF0F172A),
        listOf(0xFFD97706, 0xFFB45309, 0xFF7C2D12),
        listOf(0xFF16A34A, 0xFF047857, 0xFF064E3B),
        listOf(0xFF64748B, 0xFF334155, 0xFF1E293B),
    )
    val EXPIRED: List<Long> = listOf(0xFF6A7482, 0xFF2F353F)
    /** Black overlay alpha on cards. */
    const val SHADE = 0.22f

    private fun channel(c: Long, shift: Int, shade: Float): Double {
        val v = ((c shr shift) and 0xFF).toDouble() / 255.0 * (1.0 - shade)
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    /** WCAG contrast of white text on [argb] darkened by [shade]. */
    fun whiteContrast(argb: Long, shade: Float = SHADE): Double {
        val l = 0.2126 * channel(argb, 16, shade) + 0.7152 * channel(argb, 8, shade) + 0.0722 * channel(argb, 0, shade)
        return 1.05 / (l + 0.05)
    }

    /** One of three card gradients, stable for a subscription id (same id = same colour on every start). */
    fun paletteIndex(id: String): Int {
        var h = 0
        for (c in id) h = (h * 31 + c.code) and 0x7fffffff
        return h % 3
    }

    /** Whole days left, rounded up; 0 on the last day; null when the provider gives no date. */
    fun daysLeft(expireAt: Long?, now: Long): Long? {
        if (expireAt == null || expireAt <= 0L) return null
        val left = expireAt - now
        if (left <= 0L) return 0L
        return (left + DAY_MILLIS - 1) / DAY_MILLIS
    }

    fun expired(expireAt: Long?, now: Long): Boolean = expireAt != null && expireAt > 0L && expireAt <= now

    /** Share of traffic used, 0..1; null when the plan has no limit or nothing is known. */
    fun usedShare(used: Long?, total: Long?): Float? {
        if (used == null || total == null || total <= 0L) return null
        return (used.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    enum class Ping { NONE, GOOD, MEDIUM, BAD }

    fun ping(delayMillis: Long): Ping = when {
        delayMillis == 0L -> Ping.NONE
        delayMillis < 0L -> Ping.BAD
        delayMillis <= 150L -> Ping.GOOD
        delayMillis <= 400L -> Ping.MEDIUM
        else -> Ping.BAD
    }

    /** "05:07" under an hour, "1:05:07" after. */
    fun clock(elapsedMillis: Long): String {
        val s = (elapsedMillis / 1000L).coerceAtLeast(0L)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** Server name hints that the provider marks as a gaming line. */
    fun isGaming(name: String): Boolean {
        val n = name.lowercase()
        return "игр" in n || "game" in n || "gaming" in n || "🎮" in name
    }
}
