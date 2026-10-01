package com.v2ray.ang.net

/** What is known about one server that could be tried first (from the history on this operator and from recent successes). */
data class QuickFacts(
    val id: String,
    /** Last time it passed a test on this operator's mobile network (0 = never). */
    val lastOkAt: Long,
    /** Failures since that success. */
    val streak: Int,
    val avgPingMs: Long,
    /** Last time it worked as the switch target (0 = never). */
    val recentSuccessAt: Long,
)

/**
 * The quick path: when the network turns restricted, ONE server that worked here before is tried first (a short test,
 * a switch, the live check), which gives a working connection in seconds in the usual case. Only a server that proved
 * itself within a day and has not failed since qualifies; if none does, or the quick try fails, the ordinary search runs.
 */
object QuickPath {
    const val MAX_AGE_MS = BypassHistoryLogic.GOOD_TTL_MS

    fun pick(facts: List<QuickFacts>, now: Long, pingLimitMs: Long): String? {
        fun fresh(t: Long) = t > 0 && now - t < MAX_AGE_MS
        val good = facts.filter { it.streak == 0 && fresh(it.lastOkAt) }
        val pool = good.ifEmpty { facts.filter { it.streak == 0 && fresh(it.recentSuccessAt) } }
        return pool.sortedWith(
            compareBy<QuickFacts>(
                { if (it.avgPingMs in 1..pingLimitMs) 0 else 1 },
                { -maxOf(it.lastOkAt, it.recentSuccessAt) },
                { if (it.avgPingMs > 0) it.avgPingMs else Long.MAX_VALUE },
            )
        ).firstOrNull()?.id
    }
}
