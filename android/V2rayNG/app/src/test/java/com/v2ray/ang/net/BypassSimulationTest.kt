package com.v2ray.ang.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthetic scenarios (not real networks): count what the search does before and after the changes, in numbers that do not
 * depend on the speed of the machine: tests run, live switches, servers wrongly held back. The "old" side is the same search
 * with the old rules switched on (one big wave, no quick path, a ban on every failure).
 */
class BypassSimulationTest {

    private class World(val working: Set<String>, val live: Set<String> = working, val networkDown: () -> Boolean = { false }) : SearchEnv {
        val isolatedRuns = AtomicInteger()
        val switches = AtomicInteger()
        var failuresJudged: Pair<Int, Boolean>? = null
        override suspend fun isolated(id: String): IsoResult {
            isolatedRuns.incrementAndGet()
            return if (!networkDown() && id in working) IsoResult(true, 300) else IsoResult(false, -1)
        }
        override suspend fun switchTo(id: String): SwitchResult { switches.incrementAndGet(); return SwitchResult.OK }
        override suspend fun verify(id: String) = if (id in live) LiveOutcome.OK else LiveOutcome.FAIL
        override suspend fun rollback() {}
        override fun stillNeeded() = true
        override fun log(message: String) {}
        override fun recordIsolatedFailures(failed: List<SearchCandidate>, uncertain: Int, trusted: Boolean) { failuresJudged = failed.size to trusted }
    }

    private fun cands(n: Int) = (0 until n).map { SearchCandidate("s$it", score = 50, order = it) }

    @Test
    fun theQuickPathNeedsOneTestAndOneSwitchWhereTheFullSearchNeedsMany() = runBlocking {
        val all = cands(24)
        val knownGood = "s17"
        // old: everything in one wave, two servers must pass before the first is chosen
        val old = World(working = setOf(knownGood, "s20"))
        BypassSearch.run(all, SearchLimits(parallel = 1, earlyExit = 2), old)
        // new: the server that worked here before is tried alone
        val quick = World(working = setOf(knownGood, "s20"))
        val r = BypassSearch.run(listOf(all[17]), SearchLimits(parallel = 1, earlyExit = 1, maxLive = 1), quick)
        println("SIM quick-path: tests old=${old.isolatedRuns.get()} new=${quick.isolatedRuns.get()}; switches old=${old.switches.get()} new=${quick.switches.get()}")
        assertEquals(knownGood, r.found)
        assertEquals(1, quick.isolatedRuns.get())
        assertEquals(1, quick.switches.get())
        assertTrue(old.isolatedRuns.get() >= 18)
    }

    @Test
    fun wavesTestFewerServersWhenTheFirstOnesAreGood() = runBlocking {
        val all = cands(24)
        val flat = World(working = setOf("s1", "s2", "s3"))
        BypassSearch.run(all, SearchLimits(parallel = 1, earlyExit = 5), flat)
        val waved = World(working = setOf("s1", "s2", "s3"))
        BypassSearch.run(all, SearchLimits(parallel = 1, earlyExit = 5, waves = listOf(4, 8)), waved)
        println("SIM waves: tests flat=${flat.isolatedRuns.get()} waves=${waved.isolatedRuns.get()}")
        assertEquals(24, flat.isolatedRuns.get())
        assertEquals(4, waved.isolatedRuns.get())
    }

    @Test
    fun aFlappingNetworkNoLongerHoldsWorkingServersBack() = runBlocking {
        // 10 searches in a row on a network that is down during the test: every server "fails".
        val ids = cands(12)
        var oldBans = 0
        var newBans = 0
        val history = HashMap<String, HistoryEntry>()
        repeat(10) { episode ->
            val world = World(working = ids.map { it.id }.toSet(), networkDown = { true })
            BypassSearch.run(ids, SearchLimits(parallel = 4), world)
            val (failed, trusted) = world.failuresJudged!!
            // old rule: every failure marks the server bad
            oldBans += failed
            // new rule: nothing is held against anyone when the test was not trustworthy; otherwise the second failure in a row bans
            if (trusted) ids.forEach {
                val e = BypassHistoryLogic.recordFail(history[it.id], 1_000L * episode)
                history[it.id] = e
                if (e.streak >= BypassHistoryLogic.BAN_AT_STREAK) newBans++
            }
        }
        println("SIM flapping network, 10 searches x 12 servers (all of them fine): wrongly held back old=$oldBans new=$newBans")
        assertEquals(120, oldBans)
        assertEquals(0, newBans)
    }

    @Test
    fun aRealDeadServerIsStillHeldBackFromTheSecondFailure() = runBlocking {
        val history = HashMap<String, HistoryEntry>()
        var bans = 0
        repeat(2) { i ->
            // one server works, so the test is trustworthy; "dead" does not answer
            val world = World(working = setOf("alive"))
            BypassSearch.run(listOf(SearchCandidate("alive", 50, 0), SearchCandidate("dead", 50, 1)), SearchLimits(earlyExit = 5), world)
            val (failed, trusted) = world.failuresJudged!!
            assertTrue(trusted)
            assertEquals(1, failed)
            val e = BypassHistoryLogic.recordFail(history["dead"], 1_000L * i)
            history["dead"] = e
            if (e.streak >= BypassHistoryLogic.BAN_AT_STREAK) bans++
        }
        assertEquals(1, bans)
    }
}
