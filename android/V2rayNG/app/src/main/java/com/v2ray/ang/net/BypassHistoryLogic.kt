package com.v2ray.ang.net

import java.security.MessageDigest

/** How one server did in tests on a mobile network (pure part; storage is in the handler package). */
data class HistoryEntry(
    val ok: Int = 0,
    val fail: Int = 0,
    val lastOkAt: Long = 0L,
    val lastFailAt: Long = 0L,
    /** Failures since the last success. */
    val streak: Int = 0,
    val avgPingMs: Long = 0L,
)

object BypassHistoryLogic {
    /** A success counts as "good" for this long. */
    const val GOOD_TTL_MS = 24 * 60 * 60_000L
    private const val MAX_DOUBLINGS = 3
    const val MAX_ENTRIES = 300

    /**
     * One failure proves little (a busy phone, a bad moment on the network): a server is held back only from its
     * second failure in a row. Failures are counted in the history anyway.
     */
    const val BAN_AT_STREAK = 2

    /** A ban lasts [baseMinutes], doubled with every further failure in a row (to 8x). */
    fun badTtlMs(streak: Int, baseMinutes: Int): Long =
        baseMinutes * 60_000L * (1L shl (streak - BAN_AT_STREAK).coerceIn(0, MAX_DOUBLINGS))

    fun isBad(e: HistoryEntry?, now: Long, baseMinutes: Int): Boolean =
        e != null && e.streak >= BAN_AT_STREAK && now - e.lastFailAt in 0 until badTtlMs(e.streak, baseMinutes)

    fun isGood(e: HistoryEntry?, now: Long): Boolean =
        e != null && e.lastOkAt > 0 && e.streak == 0 && now - e.lastOkAt < GOOD_TTL_MS

    fun recordOk(e: HistoryEntry?, now: Long, pingMs: Long): HistoryEntry {
        val old = e ?: HistoryEntry()
        val avg = if (old.avgPingMs <= 0) pingMs else (old.avgPingMs * 2 + pingMs) / 3
        return old.copy(ok = old.ok + 1, lastOkAt = now, streak = 0, avgPingMs = avg.coerceAtLeast(0))
    }

    fun recordFail(e: HistoryEntry?, now: Long): HistoryEntry {
        val old = e ?: HistoryEntry()
        return old.copy(fail = old.fail + 1, lastFailAt = now, streak = old.streak + 1)
    }

    fun facts(e: HistoryEntry?, now: Long, baseMinutes: Int): HistoryFacts? {
        if (e == null) return null
        return HistoryFacts(
            lastOkAgeMs = if (e.lastOkAt > 0) (now - e.lastOkAt).coerceAtLeast(0) else null,
            badNow = isBad(e, now, baseMinutes),
        )
    }

    /** One line per key: `key=ok,fail,lastOk,lastFail,streak,avg`. Keys never contain `=` or `;`. */
    fun encode(map: Map<String, HistoryEntry>): String = map.entries
        .sortedByDescending { maxOf(it.value.lastOkAt, it.value.lastFailAt) }
        .take(MAX_ENTRIES)
        .joinToString(";") { (k, v) -> "$k=${v.ok},${v.fail},${v.lastOkAt},${v.lastFailAt},${v.streak},${v.avgPingMs}" }

    fun decode(text: String?): Map<String, HistoryEntry> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, HistoryEntry>()
        for (item in text.split(';')) {
            val idx = item.indexOf('=')
            if (idx <= 0) continue
            val p = item.substring(idx + 1).split(',')
            if (p.size != 6) continue
            val n = p.map { it.toLongOrNull() }
            if (n.any { it == null }) continue
            val v = n.map { it!! }
            out[item.substring(0, idx)] = HistoryEntry(v[0].toInt(), v[1].toInt(), v[2], v[3], v[4].toInt(), v[5])
        }
        return out
    }

    fun key(fingerprint: String, bucket: String): String = "$fingerprint@$bucket"

    /** Drops results of servers that no longer exist in any profile. */
    fun prune(map: Map<String, HistoryEntry>, liveFingerprints: Set<String>): Map<String, HistoryEntry> =
        map.filterKeys { it.substringBefore('@') in liveFingerprints }

    /**
     * A stable id of a server that survives a subscription update (the profile id may change):
     * a hash of what makes the server the server. 16 hex characters.
     */
    fun fingerprint(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|") { it.trim().lowercase() }.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** `cellular:25001` for a known operator (numeric MCC+MNC), `cellular` otherwise. */
    fun bucket(operatorCode: String?): String {
        val code = operatorCode?.trim().orEmpty()
        return if (code.length in 5..6 && code.all { it.isDigit() }) "cellular:$code" else "cellular"
    }
}
