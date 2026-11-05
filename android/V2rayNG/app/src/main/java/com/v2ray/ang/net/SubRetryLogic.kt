package com.v2ray.ang.net

/**
 * When a subscription link could not be downloaded (a restricted mobile network lets only a few sites through), the link stays
 * saved and the download is tried again on its own: soon at first, then less often. Pure, so it can be tested.
 */
object SubRetryLogic {
    /** Pauses between attempts, in seconds; the last one repeats. */
    private val STEPS_SECONDS = longArrayOf(20, 45, 90, 180, 300)

    /** Only a subscription that has a link, is switched on and still has no servers is worth another try. */
    fun needsRetry(enabled: Boolean, url: String, serverCount: Int): Boolean =
        enabled && url.isNotBlank() && serverCount == 0

    /** The pause before attempt number [failures] + 1; [jitter] in 0..1 spreads the attempts by +/- 20%. */
    fun delayMs(failures: Int, jitter: Double = 0.5): Long {
        val base = STEPS_SECONDS[failures.coerceIn(0, STEPS_SECONDS.lastIndex)] * 1000.0
        return (base * (0.8 + 0.4 * jitter.coerceIn(0.0, 1.0))).toLong()
    }
}
