package com.v2ray.ang.net

/**
 * Sharing the connection with other devices of the home network (a TV box, a console, a friend's phone): the pure parts.
 * The proxy always has a login and a password made here; an open proxy in a network must never happen.
 */
object LanShare {
    /** An address of one network interface of this phone. */
    data class Candidate(val iface: String, val ip: String)

    const val USER = "flowveil"
    private const val PASSWORD_LENGTH = 12

    /** No look-alike characters (0/O, 1/l/I), so the password can be typed by hand from the screen. */
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private val USABLE_PREFIXES = listOf("wlan", "ap", "swlan", "eth", "rndis", "usb")

    fun isPrivateV4(ip: String): Boolean {
        val p = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    private fun rank(iface: String): Int = when {
        iface.startsWith("wlan") -> 0
        iface.startsWith("eth") -> 1
        else -> 2
    }

    /** The address the other devices should use: a home-network IPv4 of a Wi-Fi, cable or hotspot interface; mobile and tunnels never. */
    fun pickAddress(candidates: List<Candidate>): String? = candidates
        .filter { c -> isPrivateV4(c.ip) && USABLE_PREFIXES.any { c.iface.startsWith(it) } }
        .minByOrNull { rank(it.iface) }
        ?.ip

    /** [nextInt] gives a random number below its argument (a secure source in the app, a fixed one in tests). */
    fun newPassword(nextInt: (Int) -> Int): String = buildString {
        repeat(PASSWORD_LENGTH) { append(ALPHABET[nextInt(ALPHABET.length)]) }
    }

    fun proxyUri(user: String, password: String, ip: String, port: Int): String = "socks5://$user:$password@$ip:$port"
}
