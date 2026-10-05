package com.v2ray.ang.net

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** One server as the identity matching sees it: its storage id, a fingerprint of what makes it the server, and its name key. */
data class IdEntry(val id: String, val fingerprint: String, val nameKey: String)

/**
 * A subscription update writes every server again. Without help each one gets a new id, and everything that points at ids
 * (favorites, chosen servers, bad marks, the snapshot, the measured ping) silently loses its server. Here the new servers are
 * matched to the old ones so the old ids can be kept:
 *  1. by fingerprint: protocol, address, port, user id / password, transport and TLS/Reality parameters, never the name
 *     (providers rename servers);
 *  2. what is left, by name, but only when that name is unique among the leftovers on both sides
 *     (the provider moved a server to a new address and kept its name).
 * Pure: the same rule lives in the Windows app.
 */
object ServerIdentity {

    /** 16 hex characters of SHA-256 over the parts (trimmed, lower case, joined with '|'). Empty parts still count. */
    fun fingerprint(parts: List<String?>): String {
        val text = parts.joinToString("|") { it.orEmpty().trim().lowercase() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** The name without decorations: emoji and symbols out, spaces collapsed, lower case. "🇩🇪 Германия | Игровой 🔥" -> "германия | игровой". */
    fun nameKey(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || cp in PUNCT) sb.appendCodePoint(Character.toLowerCase(cp))
            i += Character.charCount(cp)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private val PUNCT = "|-_.,:;()[]/#+&".map { it.code }.toSet()

    /** new id -> old id to keep. Each old id is used at most once; order decides between equal fingerprints. */
    fun reuse(new: List<IdEntry>, old: List<IdEntry>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val freeOld = old.distinctBy { it.id }.toMutableList()
        for (n in new) {
            val i = freeOld.indexOfFirst { it.fingerprint.isNotEmpty() && it.fingerprint == n.fingerprint }
            if (i >= 0) result[n.id] = freeOld.removeAt(i).id
        }
        val leftNew = new.filter { it.id !in result && it.nameKey.isNotEmpty() }
        val newCount = leftNew.groupingBy { it.nameKey }.eachCount()
        val oldCount = freeOld.filter { it.nameKey.isNotEmpty() }.groupingBy { it.nameKey }.eachCount()
        for (n in leftNew) {
            if (newCount[n.nameKey] != 1 || oldCount[n.nameKey] != 1) continue
            val i = freeOld.indexOfFirst { it.nameKey == n.nameKey }
            if (i >= 0) result[n.id] = freeOld.removeAt(i).id
        }
        return result
    }
}

/**
 * A server the user pointed at (favorite, chosen for the mobile network, selected) that disappeared from its subscription.
 * Kept for [KEEP_MS]: if the provider brings it back (same fingerprint or name) it gets its old id again and every choice
 * comes back with it; meanwhile the lists show it as "not in the subscription".
 */
data class LostRef(val id: String, val fingerprint: String, val nameKey: String, val subId: String, val name: String, val lostAt: Long) {
    companion object {
        const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()

        fun encode(list: List<LostRef>): String = list.joinToString(";") { r ->
            listOf(r.id, r.fingerprint, r.nameKey, r.subId, r.name, r.lostAt.toString()).joinToString("&") { enc(it) }
        }

        fun decode(text: String?): List<LostRef> {
            if (text.isNullOrBlank()) return emptyList()
            return text.split(';').mapNotNull { item ->
                val p = item.split('&').map { dec(it) ?: return@mapNotNull null }
                if (p.size != 6) return@mapNotNull null
                val at = p[5].toLongOrNull() ?: return@mapNotNull null
                LostRef(p[0], p[1], p[2], p[3], p[4], at)
            }
        }

        /** Older than [KEEP_MS], or back in a list ([present]): no longer lost. */
        fun prune(list: List<LostRef>, now: Long, present: Set<String>): List<LostRef> =
            list.filter { now - it.lostAt in 0 until KEEP_MS && it.id !in present }.distinctBy { it.id }
    }
}
