package com.v2ray.ang.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BypassSearchTest {

    private class Env(
        val iso: Map<String, IsoResult>,
        val live: Map<String, LiveOutcome> = emptyMap(),
        val isoDelayMs: Long = 0,
        var needed: () -> Boolean = { true },
    ) : SearchEnv {
        val switched = CopyOnWriteArrayList<String>()
        val isolatedCalls = AtomicInteger()
        val liveFails = CopyOnWriteArrayList<String>()
        val isoRecorded = CopyOnWriteArrayList<String>()
        var judged: Triple<List<String>, Int, Boolean>? = null
        var rolledBack = false
        override suspend fun isolated(id: String): IsoResult {
            isolatedCalls.incrementAndGet()
            if (isoDelayMs > 0) delay(isoDelayMs)
            return iso[id] ?: IsoResult(false, -1)
        }
        val switchResults = HashMap<String, SwitchResult>()
        override suspend fun switchTo(id: String): SwitchResult { switched += id; return switchResults[id] ?: SwitchResult.OK }
        override suspend fun verify(id: String): LiveOutcome = live[id] ?: LiveOutcome.OK
        override suspend fun rollback() { rolledBack = true }
        override fun stillNeeded(): Boolean = needed()
        override fun log(message: String) {}
        override fun onLiveFail(id: String) { liveFails += id }
        override fun onIsolated(id: String, result: IsoResult) { isoRecorded += id }
        override fun recordIsolatedFailures(failed: List<SearchCandidate>, uncertain: Int, trusted: Boolean) {
            judged = Triple(failed.map { it.id }.sorted(), uncertain, trusted)
        }
    }

    private fun cands(vararg ids: String) = ids.mapIndexed { i, id -> SearchCandidate(id, score = 50, order = i) }

    private fun ok(ping: Long = 300) = IsoResult(true, ping)

    @Test
    fun aDeadFirstServerIsSkippedAndTheNextOneWorkingIsChosen() = runBlocking {
        val env = Env(iso = mapOf("second" to ok(), "third" to ok()))
        val r = BypassSearch.run(cands("first", "second", "third"), SearchLimits(earlyExit = 5), env)
        assertEquals("second", r.found)
        assertEquals(listOf("second"), env.switched.toList())
        assertFalse(env.rolledBack)
    }

    @Test
    fun nothingPassesNothingIsSwitched() = runBlocking {
        val env = Env(iso = emptyMap())
        val r = BypassSearch.run(cands("a", "b", "c"), SearchLimits(), env)
        assertNull(r.found)
        assertTrue(env.switched.isEmpty())
        assertFalse(env.rolledBack)
        assertEquals(3, r.tested)
    }

    @Test
    fun aServerThatPassesTheTestButFailsTheLiveCheckIsRejectedAndNextIsTried() = runBlocking {
        val env = Env(iso = mapOf("a" to ok(200), "b" to ok(400)), live = mapOf("a" to LiveOutcome.FAIL))
        val r = BypassSearch.run(cands("a", "b"), SearchLimits(earlyExit = 5), env)
        assertEquals("b", r.found)
        assertEquals(listOf("a", "b"), env.switched.toList())
        assertEquals(listOf("a"), env.liveFails.toList())
        assertFalse(env.rolledBack)
    }

    @Test
    fun whenEveryLiveCheckFailsTheConnectionGoesBack() = runBlocking {
        val env = Env(iso = mapOf("a" to ok(), "b" to ok()), live = mapOf("a" to LiveOutcome.FAIL, "b" to LiveOutcome.FAIL))
        val r = BypassSearch.run(cands("a", "b"), SearchLimits(earlyExit = 5), env)
        assertNull(r.found)
        assertTrue(env.rolledBack)
    }

    @Test
    fun anInterruptedLiveCheckRollsBack() = runBlocking {
        val env = Env(iso = mapOf("a" to ok()), live = mapOf("a" to LiveOutcome.UNKNOWN))
        val r = BypassSearch.run(cands("a"), SearchLimits(), env)
        assertNull(r.found)
        assertTrue(r.aborted)
        assertTrue(env.rolledBack)
    }

    @Test
    fun searchStopsEarlyWhenEnoughServersPassed() = runBlocking {
        val ids = (1..20).map { "s$it" }
        val env = Env(iso = ids.associateWith { ok() }, isoDelayMs = 30)
        val r = BypassSearch.run(cands(*ids.toTypedArray()), SearchLimits(parallel = 2, earlyExit = 2), env)
        assertTrue(r.found != null)
        assertTrue("stopped early, tested ${env.isolatedCalls.get()}", env.isolatedCalls.get() < 12)
    }

    @Test
    fun aSlowServerGoesAfterFastOnes() = runBlocking {
        val env = Env(iso = mapOf("slow" to ok(2_500), "fast" to ok(900)))
        val c = listOf(SearchCandidate("slow", score = 90, order = 0), SearchCandidate("fast", score = 40, order = 1))
        val r = BypassSearch.run(c, SearchLimits(earlyExit = 5, pingLimitMs = 1_500), env)
        assertEquals("fast", r.found)
    }

    @Test
    fun equalPingsFollowTheScoreThenTheOrder() = runBlocking {
        val env = Env(iso = mapOf("a" to ok(500), "b" to ok(500), "c" to ok(500)))
        val c = listOf(SearchCandidate("a", 30, 0), SearchCandidate("b", 60, 1), SearchCandidate("c", 60, 2))
        assertEquals("b", BypassSearch.run(c, SearchLimits(earlyExit = 5), env).found)
    }

    @Test
    fun theBudgetEndsTheTestsButPassedServersStillCount() = runBlocking {
        val iso = mapOf("quick" to ok(), "stuck" to ok())
        val env = object : SearchEnv by Env(iso) {
            override suspend fun isolated(id: String): IsoResult {
                if (id == "stuck") delay(60_000)
                return IsoResult(true, 100)
            }
            override suspend fun verify(id: String) = LiveOutcome.OK
        }
        val r = BypassSearch.run(cands("quick", "stuck"), SearchLimits(earlyExit = 5, totalMs = 300, perCandidateMs = 60_000), env)
        assertTrue(r.timedOut)
        assertEquals("quick", r.found)
    }

    @Test
    fun aCandidateThatTimesOutCountsAsFailed() = runBlocking {
        val env = object : SearchEnv by Env(emptyMap()) {
            override suspend fun isolated(id: String): IsoResult { delay(5_000); return IsoResult(true, 1) }
        }
        val r = BypassSearch.run(cands("a"), SearchLimits(perCandidateMs = 100, totalMs = 5_000), env)
        assertNull(r.found)
        assertEquals(0, r.passed)
    }

    @Test
    fun ifTheNetworkChangedNothingIsSwitched() = runBlocking {
        val env = Env(iso = mapOf("a" to ok()), needed = { false })
        val r = BypassSearch.run(cands("a"), SearchLimits(), env)
        assertNull(r.found)
        assertTrue(r.aborted)
        assertTrue(env.switched.isEmpty())
    }

    @Test
    fun onlyTheAllowedNumberOfSwitchesIsMade() = runBlocking {
        val ids = (1..6).map { "s$it" }
        val env = Env(iso = ids.associateWith { ok() }, live = ids.associateWith { LiveOutcome.FAIL })
        BypassSearch.run(cands(*ids.toTypedArray()), SearchLimits(earlyExit = 10, maxLive = 3), env)
        assertEquals(3, env.switched.size)
        assertTrue(env.rolledBack)
    }

    @Test
    fun noCandidatesNoAction() = runBlocking {
        val env = Env(iso = emptyMap())
        val r = BypassSearch.run(emptyList(), SearchLimits(), env)
        assertNull(r.found)
        assertEquals(0, r.tested)
    }

    @Test
    fun testOnlyNeverSwitches() = runBlocking {
        val env = Env(iso = mapOf("a" to ok(), "b" to ok()))
        val r = BypassSearch.run(cands("a", "b", "c"), SearchLimits(earlyExit = 50, testOnly = true), env)
        assertNull(r.found)
        assertEquals(2, r.passed)
        assertEquals(3, r.tested)
        assertTrue(env.switched.isEmpty())
        assertFalse(env.rolledBack)
    }

    @Test
    fun aSureBetEndsTheTestsAtOnce() = runBlocking {
        val ids = (1..20).map { "s$it" }
        val env = Env(iso = ids.associateWith { ok() }, isoDelayMs = 30)
        val c = ids.mapIndexed { i, id -> SearchCandidate(id, score = if (i == 0) 80 else 40, order = i) }
        val r = BypassSearch.run(c, SearchLimits(parallel = 2, earlyExit = 5, stopAtScore = 60), env)
        assertEquals("s1", r.found)
        assertTrue("tested ${env.isolatedCalls.get()}", env.isolatedCalls.get() <= 4)
    }

    @Test
    fun whenNobodyPassesTheFailuresAreNotHeldAgainstAnyone() = runBlocking {
        val env = Env(iso = emptyMap())
        BypassSearch.run(cands("a", "b", "c"), SearchLimits(), env)
        assertEquals(Triple(listOf("a", "b", "c"), 0, false), env.judged)
    }

    @Test
    fun whenSomeoneAnswersTheOthersCountAsFailed() = runBlocking {
        val env = Env(iso = mapOf("b" to ok()))
        BypassSearch.run(cands("a", "b", "c"), SearchLimits(earlyExit = 5), env)
        assertEquals(Triple(listOf("a", "c"), 0, true), env.judged)
    }

    @Test
    fun aTestThatDidNotComeBackIsNotAFailure() = runBlocking {
        val env = Env(iso = mapOf("a" to ok(), "slow" to IsoResult(false, -1, timedOut = true)))
        BypassSearch.run(cands("a", "slow", "dead"), SearchLimits(earlyExit = 5), env)
        assertEquals(Triple(listOf("dead"), 1, true), env.judged)
    }

    @Test
    fun aSearchThatIsNoLongerNeededJudgesNothing() = runBlocking {
        val env = Env(iso = mapOf("a" to ok()), needed = { false })
        val r = BypassSearch.run(cands("a", "b"), SearchLimits(), env)
        assertTrue(r.aborted)
        assertNull(env.judged)
    }

    @Test
    fun failStreaksCountInARowAndASuccessResets() {
        val s = FailStreaks()
        assertEquals(1, s.fail("x"))
        assertEquals(2, s.fail("x"))
        s.ok("x")
        assertEquals(1, s.fail("x"))
        assertEquals(1, s.fail("y"))
    }

    @Test
    fun aCandidateMetWithABusyCoreIsNotFailedAndDoesNotUseALiveTry() = runBlocking {
        val env = Env(iso = mapOf("a" to ok(100), "b" to ok(200)))
        env.switchResults["a"] = SwitchResult.BUSY_RELOADING
        val r = BypassSearch.run(cands("a", "b"), SearchLimits(earlyExit = 5, maxLive = 1), env)
        assertEquals("b", r.found)
        assertTrue(env.liveFails.isEmpty())
        assertFalse(env.rolledBack)
    }

    @Test
    fun aCoreThatStaysBusyEndsTheSearchWithoutRollbackOrFailures() = runBlocking {
        val env = Env(iso = mapOf("a" to ok(100), "b" to ok(200), "c" to ok(300)))
        env.switchResults.putAll(mapOf("a" to SwitchResult.BUSY_RELOADING, "b" to SwitchResult.BUSY_RELOADING, "c" to SwitchResult.BUSY_RELOADING))
        val r = BypassSearch.run(cands("a", "b", "c"), SearchLimits(earlyExit = 5, maxLive = 3), env)
        assertNull(r.found)
        assertEquals(2, env.switched.size)
        assertTrue(env.liveFails.isEmpty())
        assertFalse(env.rolledBack)
    }

    @Test
    fun aCoreThatIsNotRunningAnyMoreAbortsTheSearch() = runBlocking {
        val env = Env(iso = mapOf("a" to ok()))
        env.switchResults["a"] = SwitchResult.NOT_RUNNING
        val r = BypassSearch.run(cands("a"), SearchLimits(), env)
        assertTrue(r.aborted)
        assertNull(r.found)
    }

    @Test
    fun theSecondWaveIsNotStartedWhenTheFirstOneHasAServer() = runBlocking {
        val env = Env(iso = mapOf("b" to ok(), "e" to ok()))
        val r = BypassSearch.run(cands("a", "b", "c", "d", "e", "f"), SearchLimits(earlyExit = 5, waves = listOf(3)), env)
        assertEquals("b", r.found)
        assertEquals(3, env.isolatedCalls.get())
    }

    @Test
    fun aWaveThatGivesNothingIsFollowedByTheNextOne() = runBlocking {
        val env = Env(iso = mapOf("e" to ok()))
        val r = BypassSearch.run(cands("a", "b", "c", "d", "e", "f", "g"), SearchLimits(earlyExit = 5, waves = listOf(2, 2)), env)
        assertEquals("e", r.found)
        // waves of 2 and 2 gave nothing; the last wave is all that is left: e, f, g
        assertEquals(7, env.isolatedCalls.get())
    }

    @Test
    fun splitIntoWavesTakesTheRestAsTheLastWave() {
        val c = cands("a", "b", "c", "d", "e", "f", "g")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f", "g")), splitIntoWaves(c, listOf(2, 2)).map { w -> w.map { it.id } })
        assertEquals(listOf(listOf("a", "b", "c", "d", "e", "f", "g")), splitIntoWaves(c, emptyList()).map { w -> w.map { it.id } })
        assertEquals(listOf(listOf("a", "b", "c")), splitIntoWaves(cands("a", "b", "c"), listOf(8, 8)).map { w -> w.map { it.id } })
    }

    @Test
    fun allWavesFailingGivesNothingAndJudgesFailuresAsUntrusted() = runBlocking {
        val env = Env(iso = emptyMap())
        val r = BypassSearch.run(cands("a", "b", "c", "d", "e"), SearchLimits(waves = listOf(2, 2)), env)
        assertNull(r.found)
        assertEquals(5, r.tested)
        assertEquals(false, env.judged?.third)
    }
}
