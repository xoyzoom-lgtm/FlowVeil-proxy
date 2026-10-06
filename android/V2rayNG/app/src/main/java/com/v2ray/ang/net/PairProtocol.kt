package com.v2ray.ang.net

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "FlowVeil Pair", the phone side (pure logic, no Android classes so it is unit-tested on the JVM).
 * The computer shows a QR code `http://ip:port/p/SID#t=TOKEN&k=KEY&v=1`; the phone sends the subscription
 * link to `POST /p/SID/send` as AES-256-GCM (key = k, AAD = SID, wire = base64url(nonce12 || cipher || tag16)).
 * The same test vector is checked on the Windows side (PairTests.Crypto_SharedVector).
 */
object PairProtocol {
    const val MAX_ITEMS = 200
    const val MAX_ITEM_LENGTH = 4096
    private val SCHEMES = setOf("http", "https", "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic", "wireguard")

    data class Qr(val host: String, val port: Int, val sid: String, val token: String, val key: ByteArray)

    /** Parses the address from the computer's QR code; null for anything else. */
    fun parseQr(text: String?): Qr? {
        val t = unwrap(text?.trim() ?: return null) ?: return null
        if (!t.startsWith("http://")) return null
        val hash = t.indexOf('#')
        if (hash < 0) return null
        val base = t.substring("http://".length, hash)
        val slash = base.indexOf('/')
        if (slash < 0) return null
        val hostPort = base.substring(0, slash)
        val path = base.substring(slash).trim('/').split('/')
        if (path.size != 2 || path[0] != "p" || path[1].isEmpty()) return null
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) return null
        val host = hostPort.substring(0, colon)
        val port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        if (port !in 1..65535 || host.isEmpty()) return null
        val params = t.substring(hash + 1).split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        if (params["v"] != "1") return null
        val token = params["t"]?.takeIf { it.isNotEmpty() } ?: return null
        val key = b64Decode(params["k"] ?: return null) ?: return null
        if (key.size != 32) return null
        return Qr(host, port, path[1], token, key)
    }

    /** The computer's QR in the form `flowveil://pair?q=<encoded http address>`: the address inside, or the text as it is. */
    private fun unwrap(t: String): String? {
        if (!t.startsWith("flowveil://pair", ignoreCase = true)) return t
        val q = t.substringAfter("?q=", "").substringBefore('&')
        if (q.isEmpty()) return null
        return runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrNull()
    }

    /** Looks like a code from a FlowVeil computer (even a broken or old one): never to be imported as a server or a link. */
    fun looksLikePair(text: String?): Boolean {
        val t = text?.trim() ?: return false
        if (t.startsWith("flowveil://pair", ignoreCase = true)) return true
        if (!t.startsWith("http://")) return false
        val rest = t.removePrefix("http://")
        val host = rest.substringBefore(':').substringBefore('/')
        return isPrivateHost(host) && rest.substringAfter('/', "").startsWith("p/")
    }

    /** Only home-network addresses are accepted: a QR must never make the phone talk to the internet. */
    fun isPrivateHost(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    fun isAllowedLink(link: String): Boolean {
        if (link.length < 8 || link.length > MAX_ITEM_LENGTH || link.any { it.isWhitespace() || it.isISOControl() }) return false
        val i = link.indexOf("://")
        if (i <= 0) return false
        val scheme = link.substring(0, i).lowercase()
        if (scheme == "flowveil") return link.startsWith("flowveil://add", ignoreCase = true)
        return scheme in SCHEMES
    }

    /** The plaintext the computer expects. */
    fun payloadJson(items: List<String>, name: String?): String {
        val sb = StringBuilder("{\"type\":\"subscription\",\"items\":[")
        items.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append(jsonString(s))
        }
        sb.append(']')
        if (!name.isNullOrBlank()) sb.append(",\"name\":").append(jsonString(name.take(100)))
        return sb.append('}').toString()
    }

    fun sendBody(qr: Qr, plaintext: String, device: String, nonce: ByteArray? = null): String =
        "{\"t\":${jsonString(qr.token)},\"enc\":1,\"device\":${jsonString(device)},\"data\":${jsonString(encrypt(qr.key, qr.sid, plaintext, nonce))}}"

    /** Manual entry: address + 6-digit code, no key, so the payload goes in the clear (home network only). */
    fun codeBody(code: String, plaintext: String, device: String): String =
        "{\"code\":${jsonString(code)},\"enc\":0,\"device\":${jsonString(device)},\"data\":$plaintext}"

    fun encrypt(key: ByteArray, sid: String, plaintext: String, nonce: ByteArray? = null): String {
        val n = nonce ?: ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, n))
        cipher.updateAAD(sid.toByteArray(Charsets.US_ASCII))
        return b64Encode(n + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(key: ByteArray, sid: String, wire: String): String? = try {
        val all = b64Decode(wire) ?: return null
        if (all.size < 28) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        cipher.updateAAD(sid.toByteArray(Charsets.US_ASCII))
        String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** base64url without padding; hand-written because java.util.Base64 needs API 26 and this app supports 24. */
    fun b64Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < data.size) sb.append(ALPHABET[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < data.size) sb.append(ALPHABET[b2 and 63])
            i += 3
        }
        return sb.toString()
    }

    fun b64Decode(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in text.trimEnd('=')) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }
}
