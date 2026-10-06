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
        return Result.Text(t)
    }
}
