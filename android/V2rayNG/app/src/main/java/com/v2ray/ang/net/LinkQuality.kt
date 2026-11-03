package com.v2ray.ang.net

import kotlin.math.abs

/**
 * What a series of connection times says about a server for games and calls: not only the ping, but also how much it jumps
 * (jitter) and how many attempts were lost. The samples are TCP connects, so the loss is the loss of connection attempts, not of
 * game packets: it is a good warning sign, not a measurement of the game's own traffic. Pure, so it can be tested.
 */
object LinkQuality {
    enum class Grade { GREAT, VIDEO_ONLY, BAD }

    data class Result(val pingMs: Long, val jitterMs: Long, val lossPercent: Int, val grade: Grade)

    /** At least this many attempts are needed for a verdict. */
    const val MIN_SAMPLES = 5

    private const val GREAT_LOSS = 5
    private const val GREAT_JITTER_MS = 12L
    private const val VIDEO_LOSS = 15
    private const val VIDEO_JITTER_MS = 40L

    /** [samples]: the time of each attempt in ms, or a value <= 0 for a lost one. Null when there are too few attempts. */
    fun evaluate(samples: List<Long>): Result? {
        if (samples.size < MIN_SAMPLES) return null
        val ok = samples.filter { it > 0 }
        val loss = (samples.size - ok.size) * 100 / samples.size
        if (ok.isEmpty()) return Result(0L, 0L, 100, Grade.BAD)
        val sorted = ok.sorted()
        val ping = sorted[sorted.size / 2]
        val jitter = if (ok.size < 2) 0L else ok.zipWithNext { a, b -> abs(b - a) }.average().toLong()
        val grade = when {
            loss <= GREAT_LOSS && jitter <= GREAT_JITTER_MS -> Grade.GREAT
            loss <= VIDEO_LOSS && jitter <= VIDEO_JITTER_MS -> Grade.VIDEO_ONLY
            else -> Grade.BAD
        }
        return Result(ping, jitter, loss, grade)
    }
}
