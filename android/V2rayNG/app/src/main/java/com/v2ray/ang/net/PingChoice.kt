package com.v2ray.ang.net

/**
 * The ping settings the user picks in the "Ping" sheet (pure, so it can be tested; stored by handler.PingPrefs).
 *
 *  - [Method.PROXY]: a real request through the server (its own core instance): the only one that proves the server works;
 *  - [Method.TCP]: a plain connect to the server's address and port: quick, but only says the address is reachable;
 *  - [Method.ICMP]: the system "ping": many servers do not answer it at all, which says nothing about the server.
 */
object PingChoice {
    enum class Method(val id: String) { PROXY("proxy"), TCP("tcp"), ICMP("icmp") }

    data class UrlPreset(val label: String, val url: String)

    val URL_PRESETS = listOf(
        UrlPreset("Google", PingUrls.PRIMARY),
        UrlPreset("Cloudflare", PingUrls.FALLBACK),
        UrlPreset("Google 2", PingUrls.WHITELIST),
    )

    /** Seconds offered for "count as not working after". */
    val TIMEOUTS = listOf(3, 5, 10, 15)
    const val DEFAULT_TIMEOUT = 10

    fun method(id: String?): Method = Method.entries.firstOrNull { it.id == id } ?: Method.PROXY

    fun timeoutSeconds(stored: String?): Int = stored?.toIntOrNull()?.takeIf { it in 1..60 } ?: DEFAULT_TIMEOUT

    /** A result slower than the chosen limit counts as "not working" (-1), the way other clients treat their timeout. */
    fun applyTimeout(delayMs: Long, timeoutSeconds: Int): Long =
        if (delayMs > 0 && delayMs > timeoutSeconds * 1000L) -1L else delayMs

    /** A test address the user typed: only http(s) with a host, trimmed; null when it is not usable. */
    fun cleanUrl(text: String?): String? {
        val t = text?.trim().orEmpty()
        if (!(t.startsWith("http://", true) || t.startsWith("https://", true))) return null
        val host = t.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        return t
    }

    private val ICMP_TIME = Regex("""time[=<]\s*([0-9]+(?:[.,][0-9]+)?)\s*ms""")

    /** The round-trip time from the output of the system `ping -c 1`, rounded up to whole ms (at least 1); -1 without an answer. */
    fun icmpTime(output: String?): Long {
        val m = ICMP_TIME.find(output.orEmpty()) ?: return -1L
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return -1L
        return kotlin.math.ceil(v).toLong().coerceAtLeast(1L)
    }
}
