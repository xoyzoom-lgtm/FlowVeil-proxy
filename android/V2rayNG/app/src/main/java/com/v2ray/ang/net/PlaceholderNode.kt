package com.v2ray.ang.net

/**
 * Info lines that providers put into a subscription as fake servers ("subscription ends in 93 days", "white-list servers are
 * at the end of the list"). They point at a loopback or empty address, so nothing can be connected to or measured: they must
 * never take part in "Best", failover, the bypass search or a list check. Pure, so it can be tested.
 */
object PlaceholderNode {
    private val LOCAL_HOSTS = setOf("localhost", "0.0.0.0", "::", "::1", "0:0:0:0:0:0:0:0", "0:0:0:0:0:0:0:1")

    private val LOOPBACK_V4 = Regex("""127(\.\d{1,3}){3}""")

    /** True when [host]:[port] cannot be a real server: empty, loopback, unspecified or a port outside 1..65535. */
    fun isPlaceholder(host: String?, port: String?): Boolean {
        val h = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase().orEmpty()
        if (h.isEmpty()) return true
        if (h in LOCAL_HOSTS || LOOPBACK_V4.matches(h)) return true
        val p = port?.trim()?.toIntOrNull() ?: return true
        return p !in 1..65535
    }
}
