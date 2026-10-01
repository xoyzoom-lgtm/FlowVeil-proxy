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
/** [timedOut]: the test did not come back in time (the phone was busy, not necessarily the server dead). */
data class IsoResult(val ok: Boolean, val pingMs: Long, val timedOut: Boolean = false)

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
    /** [SwitchResult.BUSY_RELOADING] means nothing was switched and [id] was not judged; the search just goes on. */
    suspend fun switchTo(id: String): SwitchResult
    /** Checks the connection that is live now (after [switchTo]). */
    suspend fun verify(id: String): LiveOutcome
    /** Back to the server that was active when the search began. */
    suspend fun rollback()
    /** False when the network changed, the feature was turned off, the user stepped in. */
    fun stillNeeded(): Boolean
    fun log(message: String)
    fun onIsolated(id: String, result: IsoResult) {}
    /**
     * Once per search, after the tests: the candidates that did not answer ([failed], timeouts left out: a test that
     * did not come back says nothing about the server). [trusted] is false when they must not be held against anyone.
     */
    fun recordIsolatedFailures(failed: List<SearchCandidate>, uncertain: Int, trusted: Boolean) {}
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
        val failures = ArrayList<SearchCandidate>()
        var uncertain = 0
        var tested = 0
        val sem = Semaphore(limits.parallel.coerceAtLeast(1))

        val finished = withTimeoutOrNull(limits.totalMs) {
            coroutineScope {
                val scopeJob = coroutineContext.job
                for (c in candidates) {
                    launch {
                        sem.withPermit {
                            if (!env.stillNeeded()) return@withPermit
                            val r = withTimeoutOrNull(limits.perCandidateMs) { env.isolated(c.id) } ?: IsoResult(false, -1L, timedOut = true)
                            var enough = false
                            var done = 0
                            synchronized(lock) {
                                tested++
                                done = tested
                                if (r.ok) passers += c to r else if (r.timedOut) uncertain++ else failures += c
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
        val failed = synchronized(lock) { failures.toList() }
        val unsure = synchronized(lock) { uncertain }
        env.recordIsolatedFailures(failed, unsure, FailJudge.trusted(passed.size, env.stillNeeded()))

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
        var liveTried = 0
        var busyInARow = 0
        for ((c, r) in ordered) {
            if (liveTried >= limits.maxLive) break
            if (!env.stillNeeded()) { aborted = true; break }
            when (env.switchTo(c.id)) {
                SwitchResult.OK -> busyInARow = 0
                SwitchResult.NOT_RUNNING -> { aborted = true; break }
                SwitchResult.BUSY_RELOADING -> {
                    // Not the candidate's fault and not one of the few live tries; two in a row mean the core is stuck: stop.
                    env.log("the core was busy, ${c.id} not tried this time")
                    if (++busyInARow >= 2) break
                    continue
                }
                SwitchResult.FAILED -> { liveTried++; continue }
            }
            liveTried++
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
