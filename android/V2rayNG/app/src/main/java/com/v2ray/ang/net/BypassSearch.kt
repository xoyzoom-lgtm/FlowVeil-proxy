package com.v2ray.ang.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

data class SearchCandidate(val id: String, val score: Int, val order: Int)

/** Result of the isolated test of one candidate. */
data class IsoResult(val ok: Boolean, val pingMs: Long)

enum class LiveOutcome { OK, FAIL, UNKNOWN }

data class SearchLimits(
    val parallel: Int = 8,
    /** Stop testing as soon as this many candidates passed. */
    val earlyExit: Int = 2,
    /** ...or as soon as one with at least this score passed (a sure bet needs no rivals). */
    val stopAtScore: Int = Int.MAX_VALUE,
    val totalMs: Long = 40_000L,
    val perCandidateMs: Long = 15_000L,
    /** A slower ping is not a failure, only a lower place in the choice. */
    val pingLimitMs: Long = 1_500L,
    /** How many candidates may be switched to (each switch interrupts the traffic). */
    val maxLive: Int = 3,
    /** Only test ("check on the mobile network now"): never switch. */
    val testOnly: Boolean = false,
)

/** What the search needs from the app; a fake one drives the tests. */
interface SearchEnv {
    /** Tests [id] away from the live connection (its own core instance, on the phone's real network). */
    suspend fun isolated(id: String): IsoResult
    suspend fun switchTo(id: String): Boolean
    /** Checks the connection that is live now (after [switchTo]). */
    suspend fun verify(id: String): LiveOutcome
    /** Back to the server that was active when the search began. */
    suspend fun rollback()
    /** False when the network changed, the feature was turned off, the user stepped in. */
    fun stillNeeded(): Boolean
    fun log(message: String)
    fun onIsolated(id: String, result: IsoResult) {}
    fun onLiveFail(id: String) {}
    fun onProgress(checked: Int, total: Int) {}
}

data class SearchResult(
    val found: String?,
    val aborted: Boolean,
    val tested: Int,
    val passed: Int,
    val timedOut: Boolean,
)

/**
 * The rule of the whole feature: a server is switched to only after it passed the isolated test,
 * and stays only after the live check; whatever else happens the connection ends on a verified
 * server or on the one it started with.
 */
object BypassSearch {
    suspend fun run(candidates: List<SearchCandidate>, limits: SearchLimits, env: SearchEnv): SearchResult {
        if (candidates.isEmpty()) return SearchResult(null, aborted = false, tested = 0, passed = 0, timedOut = false)
        val lock = Any()
        val passers = ArrayList<Pair<SearchCandidate, IsoResult>>()
        var tested = 0
        val sem = Semaphore(limits.parallel.coerceAtLeast(1))

        val finished = withTimeoutOrNull(limits.totalMs) {
            coroutineScope {
                val scopeJob = coroutineContext.job
                for (c in candidates) {
                    launch {
                        sem.withPermit {
                            if (!env.stillNeeded()) return@withPermit
                            val r = withTimeoutOrNull(limits.perCandidateMs) { env.isolated(c.id) } ?: IsoResult(false, -1L)
                            var enough = false
                            var done = 0
                            synchronized(lock) {
                                tested++
                                done = tested
                                if (r.ok) passers += c to r
                                enough = passers.size >= limits.earlyExit || (r.ok && c.score >= limits.stopAtScore)
                            }
                            env.onIsolated(c.id, r)
                            env.onProgress(done, candidates.size)
                            if (enough) scopeJob.cancelChildren(CancellationException("enough servers passed"))
                        }
                    }
                }
            }
            true
        }
        val timedOut = finished == null
        val passed = synchronized(lock) { passers.toList() }
        val done = synchronized(lock) { tested }
        if (timedOut) env.log("test budget ${limits.totalMs / 1000}s is over: $done of ${candidates.size} tested, ${passed.size} passed")
        if (!env.stillNeeded()) return SearchResult(null, aborted = true, tested = done, passed = passed.size, timedOut = timedOut)

        if (limits.testOnly) return SearchResult(null, aborted = false, tested = done, passed = passed.size, timedOut = timedOut)

        // A slow but working server goes after the fast ones; then the higher score; then the earlier in the list.
        val ordered = passed.sortedWith(
            compareBy<Pair<SearchCandidate, IsoResult>>(
                { if (it.second.pingMs > limits.pingLimitMs) 1 else 0 },
                { -it.first.score },
                { it.second.pingMs },
                { it.first.order },
            )
        )
        var switched = false
        var aborted = false
        var found: String? = null
        for ((c, r) in ordered.take(limits.maxLive)) {
            if (!env.stillNeeded()) { aborted = true; break }
            if (!env.switchTo(c.id)) continue
            switched = true
            when (env.verify(c.id)) {
                LiveOutcome.OK -> { found = c.id }
                LiveOutcome.FAIL -> { env.onLiveFail(c.id); env.log("live check failed after the switch to ${c.id} (${r.pingMs} ms in the test)") }
                LiveOutcome.UNKNOWN -> aborted = true
            }
            if (found != null || aborted) break
        }
        if (found == null && switched) env.rollback()
        return SearchResult(found, aborted, done, passed.size, timedOut)
    }
}
