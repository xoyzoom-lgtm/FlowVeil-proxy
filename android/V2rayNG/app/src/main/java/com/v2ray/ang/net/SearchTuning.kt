package com.v2ray.ang.net

/** Pause before the next search after one found nothing: short at first (a restriction often passes in seconds), growing to five minutes. */
object Backoff {
    private val STEPS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)
    const val JITTER = 0.20

    /** The step for the [failedSearches]-th failure in a row (1 = the first). */
    fun baseMs(failedSearches: Int): Long = STEPS_MS[(failedSearches - 1).coerceIn(0, STEPS_MS.size - 1)]

    /** [random] in 0.0..1.0 (any generator): the step plus or minus 20%, so phones do not retry in step. */
    fun delayMs(failedSearches: Int, random: Double): Long {
        val base = baseMs(failedSearches)
        val factor = 1.0 + JITTER * (random.coerceIn(0.0, 1.0) * 2.0 - 1.0)
        return (base * factor).toLong()
    }
}

/** Polls instead of sleeping a fixed time: ready as soon as [isReady] says so, never longer than [timeoutMs]. */
object ReadyWait {
    const val POLL_MS = 200L

    /** True when it became ready in time. */
    suspend fun poll(isReady: () -> Boolean, sleep: suspend (Long) -> Unit, now: () -> Long, timeoutMs: Long): Boolean {
        val deadline = now() + timeoutMs
        while (true) {
            if (isReady()) return true
            if (now() >= deadline) return false
            sleep(POLL_MS)
        }
    }
}

/** How many isolated tests (each one runs its own core) may run at once: fewer on phones with little memory. */
object AdaptiveParallel {
    fun pick(isLowRam: Boolean, memoryClassMb: Int, max: Int): Int {
        val byMemory = when {
            isLowRam -> 2
            memoryClassMb < 128 -> 3
            memoryClassMb < 192 -> 4
            else -> 6
        }
        return byMemory.coerceIn(1, max.coerceAtLeast(1))
    }
}
