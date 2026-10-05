package com.v2ray.ang.net

/**
 * Is this a server address the core can connect to? Android's own patterns (WEB_URL, DOMAIN_NAME) only accept host names with
 * a known top-level domain and plain ASCII, so a working server on a new TLD, in Cyrillic (IDN), with an underscore in the name
 * or a bracketed IPv6 was rejected as "invalid profile". This accepts what a resolver would accept and nothing else.
 */
object ServerAddress {

    enum class Problem { EMPTY, SPACES, SCHEME_OR_PATH, BAD_HOST }

    /** null when the address is fine, otherwise what is wrong with it. */
    fun problem(value: String?): Problem? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return Problem.EMPTY
        if (v.any { it.isWhitespace() || it.isISOControl() }) return Problem.SPACES
        if (v.contains("://") || v.contains('/') || v.contains('?') || v.contains('#') || v.contains('@')) return Problem.SCHEME_OR_PATH
        if (isIpv4(v) || isIpv6(v)) return null
        return if (isHostName(v)) null else Problem.BAD_HOST
    }

    fun isValid(value: String?): Boolean = problem(value) == null

    private val ipv4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")

    fun isIpv4(v: String): Boolean = ipv4.matches(v)

    fun isIpv6(value: String): Boolean {
        var v = value
        if (v.startsWith("[")) {
            if (!v.endsWith("]")) return false
            v = v.substring(1, v.length - 1)
        }
        v = v.substringBefore('%') // zone id
        return IpParser.isIpv6(v)
    }

    /** Labels of letters (any script), digits, '-' and '_', 1..63 long, not starting or ending with '-'; at most 253 in total; a final dot is fine. */
    fun isHostName(value: String): Boolean {
        val v = value.removeSuffix(".")
        if (v.isEmpty() || v.length > 253) return false
        val labels = v.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return false
        if (!labels.all { label -> label.all { it.isLetterOrDigit() || it == '-' || it == '_' } }) return false
        // "1.2.3" or "999.1.1.1" look like a broken IPv4, not a host.
        return !labels.all { l -> l.all { it.isDigit() } }
    }
}
