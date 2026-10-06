package com.v2ray.ang.net

/**
 * A tidier name of a server for the list: drops the marks providers add ("VIP", "100 Mbps", the protocol, "CDN") and the
 * empty brackets they leave. Only what is shown changes; the server keeps its real name. Never returns an empty text.
 */
object ServerName {
    private val NOISE = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(vip|premium|pro|free|tcp|udp|ws|grpc|reality|vless|vmess|trojan|ss|hy2|hysteria2?|tuic|xtls|tls|ipv6|ipv4|cdn|" +
            "\\d+(?:[.,]\\d+)?\\s*(?:mbps|gbps|mb/s|gb/s|мбит/с|мбит|гбит/с|гбит))(?![\\p{L}\\p{N}])"
    )
    private val EMPTY_BRACKETS = Regex("[\\[(\\{【（]\\s*[|·•,;:/\\-–—~]*\\s*[\\])\\}】）]")
    private val SEPARATORS = Regex("\\s*[|·•]+\\s*")
    private val TRIM = Regex("^[\\s|·•,;:/\\-–—~]+|[\\s|·•,;:/\\-–—~]+$")

    fun clean(name: String): String {
        var t = NOISE.replace(name, " ")
        // brackets that held only noise are now empty (possibly with leftover separators inside)
        repeat(2) { t = EMPTY_BRACKETS.replace(t, " ") }
        t = SEPARATORS.replace(t, " · ")
        t = t.replace(Regex("\\s+"), " ")
        t = TRIM.replace(t, "")
        t = t.replace(Regex("(\\s·)+\\s*$"), "").trim()
        return if (t.isBlank()) name.trim() else t
    }
}
