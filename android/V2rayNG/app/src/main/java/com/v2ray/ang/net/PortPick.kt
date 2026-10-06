package com.v2ray.ang.net

/**
 * A free local port for the proxy when the usual one is taken (another VPN or proxy app already listens on 10808).
 * [isFree] tells whether a port can be opened; [offsets] are the extra ports the core needs next to the main one (e.g. HTTP = +1).
 */
object PortPick {
    /** [preferred] when it and its neighbours are free, otherwise the nearest free port above it; null when none is found. */
    fun pick(preferred: Int, offsets: List<Int> = listOf(0), isFree: (Int) -> Boolean): Int? {
        fun ok(p: Int) = p in 1024..65000 && offsets.all { isFree(p + it) }
        if (ok(preferred)) return preferred
        for (step in 1..300) {
            val p = preferred + step
            if (ok(p)) return p
        }
        return null
    }
}
