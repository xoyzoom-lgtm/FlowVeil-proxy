package com.v2ray.ang.net

/**
 * Short subscription name for cards and the bottom panel: emoji dropped, spaces collapsed,
 * SHOUTING turned into normal case, long names cut to 15 characters plus "…".
 * Mirrored in Windows ServiceLib Handler/ShortName.cs; keep both in step.
 */
object ShortName {
    const val MAX = 16

    private fun isEmoji(cp: Int) = cp in 0x1F000..0x1FFFF || cp in 0x2600..0x27BF || cp == 0xFE0F || cp == 0x200D

    fun of(name: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (!isEmoji(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        var s = sb.toString().replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return name.trim()
        val letters = s.filter { it.isLetter() }
        if (letters.length > 1 && letters == letters.uppercase() && letters != letters.lowercase()) {
            s = s.take(1) + s.drop(1).lowercase()
        }
        val count = s.codePointCount(0, s.length)
        if (count > MAX) {
            s = s.substring(0, s.offsetByCodePoints(0, MAX - 1)).trimEnd() + "…"
        }
        return s
    }

    /** First letter for the avatar tile. */
    fun initial(name: String): String = of(name).firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
}
