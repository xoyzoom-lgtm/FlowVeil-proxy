package com.v2ray.ang.net

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Invite links from providers: flowveil://add?url=<link>&name=<title>, flowveil://add/<link>,
 * flowveil://install-sub?url=..., v2rayng://install-sub?url=...
 * Pure string handling so it can be tested without Android.
 */
object InviteLink {

    const val MAX_NAME = 60

    data class Invite(val link: String, val name: String?)

    /** The link to import; [Invite.name] is already folded into it as a `#fragment` (the importer uses that as the subscription name). */
    fun parse(raw: String): Invite? {
        val full = raw.trim()
        val afterScheme = full.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val outerFragment = afterScheme.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::decode)
        val noFragment = afterScheme.substringBefore('#')
        val slash = noFragment.indexOf('/')
        val question = noFragment.indexOf('?')
        var link: String? = null
        var name: String? = null
        if (question >= 0 && (slash < 0 || question < slash)) {
            for (pair in noFragment.substring(question + 1).split('&')) {
                val key = pair.substringBefore('=').lowercase()
                val value = pair.substringAfter('=', "")
                when (key) {
                    "url" -> if (link == null) link = decodeLink(value)
                    "name" -> if (name == null) name = cleanName(decode(value))
                }
            }
        } else if (slash >= 0) {
            // flowveil://add/https://host/path -> everything after the first segment, taken as is
            val rest = afterScheme.substring(slash + 1)
            link = decodeLink(rest)
        }
        if (link.isNullOrBlank()) return null
        // A single server link (vless://…#name) keeps its own fragment untouched; only subscription addresses get a name.
        if (!link.startsWith("http://", true) && !link.startsWith("https://", true)) {
            return Invite(if (outerFragment != null && !link.contains('#')) "$link#$outerFragment" else link, null)
        }
        val hash = link.substringAfter('#', "")
        val finalName = name ?: cleanName(decode(outerFragment ?: hash))
        val base = link.substringBefore('#')
        return if (finalName != null) Invite("$base#${encodeFragment(finalName)}", finalName) else Invite(base, null)
    }

    /** A title from a link is shown to the user: no control characters, no extra blanks, at most [MAX_NAME] characters. */
    fun cleanName(value: String?): String? {
        if (value == null) return null
        val cleaned = value.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > MAX_NAME) cleaned.take(MAX_NAME).trimEnd() else cleaned
    }

    private fun decodeLink(value: String): String {
        val v = value.trim()
        return if (v.contains("%3A", ignoreCase = true) || v.contains("%2F", ignoreCase = true)) decode(v) else v
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: Exception) {
        value
    }

    private fun encodeFragment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
