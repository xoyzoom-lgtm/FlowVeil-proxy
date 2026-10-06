package com.v2ray.ang.net

import java.net.URLDecoder

/**
 * Text from a QR code, the clipboard or a shared link, unwrapped from other clients' import wrappers before the
 * "Добавить?" confirmation (the same rules as Windows Handler/ImportSource.cs):
 *  - flowveil://…, v2rayng://install-sub?url=… → the link inside (InviteLink);
 *  - happ://add/<link> (Happ's open "add" link) → the link inside;
 *  - happ://crypt… is encrypted for Happ only: not decrypted, the user is told to ask the provider for a normal link;
 *  - anything else (https://, vless://, …) is passed on unchanged and checked by [ImportPreview].
 */
object ImportSource {
    sealed interface Result {
        data class Text(val text: String) : Result
        data object HappEncrypted : Result
        data object Empty : Result
    }

    fun normalize(raw: String?): Result {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return Result.Empty
        val lower = t.lowercase()
        if (lower.startsWith("happ://crypt")) return Result.HappEncrypted
        if (lower.startsWith("happ://add/")) {
            val inner = t.substring("happ://add/".length).trim()
            val decoded = if (inner.contains("%3A", true) || inner.contains("%2F", true)) {
                runCatching { URLDecoder.decode(inner.replace("+", "%2B"), "UTF-8") }.getOrDefault(inner)
            } else {
                inner
            }
            return if (decoded.isBlank()) Result.Empty else Result.Text(decoded)
        }
        if (lower.startsWith("flowveil://") || lower.startsWith("v2rayng://install-sub")) {
            return InviteLink.parse(t)?.let { Result.Text(it.link) } ?: Result.Text(t)
        }
        if (t.lines().size == 1) unwrapGeneric(t)?.let { return Result.Text(it) }
        return Result.Text(t)
    }

    /** Protocols of single servers: never unwrapped, they are imported as they are. */
    private val SERVER_SCHEMES = setOf(
        "http", "https", "vless", "vmess", "trojan", "ss", "ssr", "shadowsocks", "hysteria", "hysteria2", "hy2", "tuic",
        "wireguard", "wg", "socks", "socks4", "socks5", "anytls", "naive", "juicity", "mieru",
    )
    private val QUERY_KEYS = listOf("url", "link", "config", "sub", "subscription", "uri", "profile")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    private fun decode(v: String): String =
        if (v.contains('%')) runCatching { URLDecoder.decode(v.replace("+", "%2B"), "UTF-8") }.getOrDefault(v) else v

    /**
     * Import links of other apps that carry an ordinary subscription address: `hiddify://import/<link>`,
     * `v2raytun://import/<link>`, `streisand://import/<link>`, `clash://install-config?url=<link>`, `stash://install-config?url=…`,
     * `sing-box://import-remote-profile?url=…`, `karing://install-config?url=…`, `v2box://install-sub?url=…`, `sub://<base64 of the link>` and alike.
     * Only the address inside is taken (http/https); null when there is none.
     */
    private fun unwrapGeneric(t: String): String? {
        val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase() ?: return null
        if (scheme in SERVER_SCHEMES) return null
        val rest = t.substring(scheme.length + 3)
        if (scheme == "sub") {
            val payload = rest.substringBefore('#').substringBefore('?').trimEnd('/')
            val bytes = runCatching { decodeBase64(payload) }.getOrNull()
            val link = bytes?.toString(Charsets.UTF_8)?.trim()
            if (link != null && (link.startsWith("http://", true) || link.startsWith("https://", true)) && link.none { it.isISOControl() }) return link
        }
        // query value: ?url=<link>[&name=…]
        val query = rest.substringAfter('?', "").substringBefore('#')
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').lowercase()
            if (key in QUERY_KEYS) {
                val v = decode(pair.substringAfter('=', "")).trim()
                if (v.startsWith("http://", true) || v.startsWith("https://", true)) return v
            }
        }
        // path form: <host>/<link> (the link may be percent-encoded)
        val candidate = decode(rest.substringAfter('/', "")).trim()
        if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) return candidate
        return null
    }

    /** Standard or URL-safe Base64 without Android classes (so it can be tested on the JVM). */
    private fun decodeBase64(text: String): ByteArray {
        val clean = text.replace('-', '+').replace('_', '/').filter { !it.isWhitespace() }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return java.util.Base64.getDecoder().decode(padded)
    }
}
