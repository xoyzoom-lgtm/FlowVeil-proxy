package com.v2ray.ang.net

import java.net.IDN
import java.net.URI

/**
 * What an incoming link or scanned code would add, so the user can say yes before anything is downloaded or sent.
 * Rejects what must never be imported: other schemes (javascript:, file:, intent:, content:), control characters,
 * oversize text, a flowveil:// link nested in another.
 */
object ImportPreview {
    const val MAX_LENGTH = 2048
    const val MAX_BATCH = 64 * 1024

    enum class Kind { SUBSCRIPTION, SERVERS, REJECTED }

    data class Preview(
        val kind: Kind,
        /** The host to show (Unicode as typed; [hostAscii] is how it really resolves). */
        val host: String = "",
        val hostAscii: String = "",
        /** The host mixes scripts (e.g. Cyrillic letters inside a Latin name): a look-alike, shown with a warning. */
        val lookAlike: Boolean = false,
        val insecure: Boolean = false,
        val servers: Int = 0,
        val reason: String? = null,
    )

    private val SERVER_SCHEMES = setOf("vless", "vmess", "trojan", "ss", "ssr", "hysteria2", "hy2", "hysteria", "tuic", "wireguard", "socks", "http", "https", "anytls")
    private val BANNED = Regex("""(?i)\b(javascript|file|intent|content|data|vbscript):""")

    fun of(text: String?): Preview {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return Preview(Kind.REJECTED, reason = "empty")
        if (t.length > MAX_BATCH) return Preview(Kind.REJECTED, reason = "too long")
        if (BANNED.containsMatchIn(t)) return Preview(Kind.REJECTED, reason = "scheme")
        if (t.contains("flowveil://", ignoreCase = true)) return Preview(Kind.REJECTED, reason = "nested")
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size == 1 && (t.startsWith("http://", true) || t.startsWith("https://", true))) {
            if (t.length > MAX_LENGTH) return Preview(Kind.REJECTED, reason = "too long")
            if (t.any { it.isISOControl() || it.isWhitespace() }) return Preview(Kind.REJECTED, reason = "control")
            // Encoded line breaks or NUL in a link only serve to smuggle headers or a second line.
            if (Regex("(?i)%(0d|0a|00)").containsMatchIn(t)) return Preview(Kind.REJECTED, reason = "control")
            val uri = runCatching { URI(t) }.getOrNull() ?: return Preview(Kind.REJECTED, reason = "not a link")
            val host = uri.host ?: uri.rawAuthority?.substringAfter('@')?.substringBefore(':') ?: return Preview(Kind.REJECTED, reason = "no host")
            return Preview(
                Kind.SUBSCRIPTION, host = display(host), hostAscii = ascii(host), lookAlike = mixedScripts(display(host)),
                insecure = t.startsWith("http://", true),
            )
        }
        // Share links of single servers (one per line), or a whole config: count what looks like servers, show the first host.
        val servers = lines.filter { l -> l.substringBefore("://", "").lowercase() in SERVER_SCHEMES }
        if (servers.isEmpty()) {
            return if (t.contains("outbounds") || t.contains("proxies:")) Preview(Kind.SERVERS, servers = 1, reason = "config")
            else Preview(Kind.REJECTED, reason = "nothing to import")
        }
        if (servers.any { it.length > MAX_LENGTH * 4 }) return Preview(Kind.REJECTED, reason = "too long")
        val firstHost = servers.firstNotNullOfOrNull { hostOfServerLink(it) }.orEmpty()
        return Preview(Kind.SERVERS, host = display(firstHost), hostAscii = ascii(firstHost), lookAlike = mixedScripts(display(firstHost)), servers = servers.size)
    }

    private fun hostOfServerLink(link: String): String? {
        val afterScheme = link.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBeforeLast(':', hostPort)
        return host.takeIf { it.isNotBlank() && !it.contains(' ') }
    }

    private fun ascii(host: String): String = runCatching { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()

    private fun display(host: String): String = runCatching { IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED) }.getOrDefault(host).lowercase()

    /** A label that mixes Latin with Cyrillic or Greek letters: "аpple.com" with a Cyrillic "а". Whole-Cyrillic names (сервер.рф) are fine. */
    fun mixedScripts(host: String): Boolean = host.split('.').any { label ->
        val scripts = label.filter { it.isLetter() }.map { Character.UnicodeScript.of(it.code) }.toSet()
        Character.UnicodeScript.LATIN in scripts && scripts.any { it == Character.UnicodeScript.CYRILLIC || it == Character.UnicodeScript.GREEK }
    }
}
