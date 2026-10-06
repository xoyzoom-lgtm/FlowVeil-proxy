package com.v2ray.ang.net

import java.net.URI

/**
 * Where a subscription server sends us with a 3xx answer. Many panels redirect clients they do not know (or the ones they
 * serve through an app) to an app link such as happ://… or v2rayng://install-sub?url=…; the web client library cannot follow
 * those, which used to end as "Failed to resolve redirect location".
 *  - http(s) address (also a relative one): follow it;
 *  - an app link that wraps an ordinary http(s) address (v2rayng://install-sub?url=…, happ://add/https://…): follow the address inside;
 *  - Happ's encrypted link (happ://crypt…): not openable here, the user is told to ask for a normal link;
 *  - any other app link: refused with its scheme named (never its content, it may hold the key).
 */
object RedirectTarget {
    sealed interface Kind {
        data class Follow(val url: String) : Kind
        data object HappEncrypted : Kind
        data class AppLink(val scheme: String) : Kind
        data object Invalid : Kind
    }

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun classify(baseUrl: String, location: String): Kind {
        val loc = location.trim()
        if (loc.isEmpty() || loc.any { it.isISOControl() }) return Kind.Invalid
        val scheme = SCHEME.find(loc)?.groupValues?.get(1)?.lowercase()
        if (scheme == null) {
            // Relative address: resolve against the page that sent us here.
            return runCatching { URI(baseUrl).resolve(URI(loc)).toString() }.getOrNull()
                ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?.let { Kind.Follow(it) } ?: Kind.Invalid
        }
        if (scheme == "http" || scheme == "https") {
            return runCatching { URI(loc) }.getOrNull()?.takeIf { it.host != null }?.let { Kind.Follow(loc) } ?: Kind.Invalid
        }
        return when (val r = ImportSource.normalize(loc)) {
            ImportSource.Result.HappEncrypted -> Kind.HappEncrypted
            is ImportSource.Result.Text ->
                if ((r.text.startsWith("http://", true) || r.text.startsWith("https://", true)) && r.text != loc) {
                    Kind.Follow(r.text.substringBefore('#'))
                } else {
                    Kind.AppLink(scheme)
                }
            ImportSource.Result.Empty -> Kind.AppLink(scheme)
        }
    }
}
