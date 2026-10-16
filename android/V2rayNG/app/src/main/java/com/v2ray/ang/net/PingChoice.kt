package com.v2ray.ang.net

/**
 * The ping settings the user picks in the "Ping" sheet (pure, so it can be tested; stored by handler.PingPrefs).
 *
 *  - [Method.PROXY]: a real request through the server (its own core instance): the only one that proves the server works
 *    (with "best of two" the request is made twice and the faster answer counts: the second one skips the DNS lookup);
 *  - [Method.TCP]: a plain connect to the server's address and port: quick, but only says the address is reachable;
 *  - [Method.TLS]: a connect plus the TLS handshake: shows that the port answers like a web server and not just that it is open;
 *  - [Method.ICMP]: the system "ping": many servers do not answer it at all, which says nothing about the server;
 *  - [Method.SMART]: TCP first; a server that answers it gets the real request through the server, one that does not is
 *    not tried further: quick for long lists and still exact.
 * Servers that run over UDP (Hysteria2, TUIC, WireGuard, HTTP/3) cannot be checked by TCP, TLS or ICMP: they always get
 * the real request through the server instead of a false "not working".
 */
object PingChoice {
    enum class Method(val id: String) { PROXY("proxy"), TCP("tcp"), TLS("tls"), ICMP("icmp"), SMART("smart") }

    /** Methods that do not need a core instance per server: many at once. */
    fun isQuick(m: Method): Boolean = m == Method.TCP || m == Method.TLS || m == Method.ICMP

    /** The server can be reached by a TCP-based check (false: UDP-based or a group/chain that has no single address). */
    fun tcpApplicable(udpBased: Boolean, hasAddress: Boolean): Boolean = !udpBased && hasAddress

    /**
     * A quick check that fails for every server of a list (three or more) says the way itself does not work on this network
     * (a blocked port, no system DNS), not that all servers are dead: the list is checked again through the servers.
     */
    fun allFailed(results: Collection<Long>): Boolean = results.size >= 3 && results.all { it <= 0L }

    /** Two measurements, the faster working one counts (-1 when neither worked). */
    fun bestOfTwo(a: Long, b: Long): Long = listOf(a, b).filter { it > 0 }.minOrNull() ?: -1L

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
