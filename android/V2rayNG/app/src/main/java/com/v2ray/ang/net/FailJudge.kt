package com.v2ray.ang.net

/**
 * When may a failed test count against a server? Only when the test could be trusted. If nobody in the whole
 * search passed, the network itself was most likely the problem (a bad moment, a flapping link): nobody is
 * held back and nothing is written to the history. The same when the network changed during the test or the
 * search stopped being needed. A test that did not come back in time says nothing about the server either.
 */
object FailJudge {
    fun trusted(passedInSearch: Int, stillNeeded: Boolean): Boolean = passedInSearch > 0 && stillNeeded
}

/** Failures in a row per server inside this process, for servers that have no history (the way back to the normal network). */
class FailStreaks {
    private val map = HashMap<String, Int>()

    @Synchronized
    fun fail(id: String): Int {
        val n = (map[id] ?: 0) + 1
        map[id] = n
        return n
    }

    @Synchronized
    fun ok(id: String) {
        map.remove(id)
    }
}
