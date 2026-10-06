package com.v2ray.ang.net

/**
 * "Best server" without testing everything first. With 100+ servers a full check takes minutes, so the servers are tested in
 * waves, most promising first (favourites, then those that answered well before, then the rest, then those that failed),
 * and the search stops as soon as a wave brings a server that is fast enough.
 */
object BestPick {
    /** A server this fast ends the search. */
    const val GOOD_ENOUGH_MS = 350L

    data class Candidate(val guid: String, val favorite: Boolean, val lastDelay: Long, val skip: Boolean)

    /** Order of testing; [Candidate.skip] servers (those that never help) are left out. */
    fun order(candidates: List<Candidate>): List<String> {
        val usable = candidates.filterNot { it.skip }
        val favorites = usable.filter { it.favorite }
        val rest = usable.filterNot { it.favorite }
        val good = rest.filter { it.lastDelay > 0 }.sortedBy { it.lastDelay }
        val unknown = rest.filter { it.lastDelay == 0L }
        val bad = rest.filter { it.lastDelay < 0 }
        return (favorites.sortedBy { if (it.lastDelay > 0) it.lastDelay else Long.MAX_VALUE } + good + unknown + bad).map { it.guid }.distinct()
    }

    /** How many servers wave number [wave] (0, 1, 2…) tests at once. */
    fun waveSize(wave: Int): Int = when (wave) {
        0 -> 24
        1 -> 48
        else -> 96
    }

    /** True when the search can stop: a fast server was found, or nothing is left to test. */
    fun enough(bestDelay: Long?, remaining: Int): Boolean = remaining == 0 || (bestDelay != null && bestDelay in 1..GOOD_ENOUGH_MS)
}
