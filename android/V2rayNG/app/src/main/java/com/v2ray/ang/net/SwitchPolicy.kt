package com.v2ray.ang.net

/** Why a change of the server did or did not happen. */
enum class SwitchResult {
    OK,
    /** The core was in the middle of a reload: nothing was changed (not a failure of the server that was meant). */
    BUSY_RELOADING,
    NOT_RUNNING,
    FAILED,
}

/**
 * A switch that meets a reload in progress waits for it to finish (polling, not a fixed pause) and tries once more.
 * Still busy afterwards: [SwitchResult.BUSY_RELOADING] goes back to the caller, who must not count the server as failed.
 */
object SwitchRetry {
    const val WAIT_MS = 5_000L
    const val POLL_MS = 200L

    suspend fun run(
        attempt: () -> SwitchResult,
        isBusy: () -> Boolean,
        sleep: suspend (Long) -> Unit,
        now: () -> Long,
        onBusy: () -> Unit = {},
    ): SwitchResult {
        var result = attempt()
        if (result != SwitchResult.BUSY_RELOADING) return result
        onBusy()
        val deadline = now() + WAIT_MS
        while (isBusy() && now() < deadline) sleep(POLL_MS)
        if (isBusy()) return SwitchResult.BUSY_RELOADING
        result = attempt()
        if (result == SwitchResult.BUSY_RELOADING) onBusy()
        return result
    }
}
