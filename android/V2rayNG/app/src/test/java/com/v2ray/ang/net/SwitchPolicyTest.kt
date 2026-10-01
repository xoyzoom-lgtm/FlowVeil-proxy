package com.v2ray.ang.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPolicyTest {
    private class Clock { var t = 0L }

    @Test
    fun noWaitingWhenTheFirstTryWorks() = runBlocking {
        val clock = Clock()
        var slept = 0
        val r = SwitchRetry.run({ SwitchResult.OK }, { false }, { slept++; clock.t += it }, { clock.t })
        assertEquals(SwitchResult.OK, r)
        assertEquals(0, slept)
    }

    @Test
    fun waitsForTheReloadByPollingThenTriesOnce() = runBlocking {
        val clock = Clock()
        var busyUntil = 1_000L
        var attempts = 0
        var busyReported = 0
        val r = SwitchRetry.run(
            attempt = { attempts++; if (clock.t < busyUntil) SwitchResult.BUSY_RELOADING else SwitchResult.OK },
            isBusy = { clock.t < busyUntil },
            sleep = { clock.t += it },
            now = { clock.t },
            onBusy = { busyReported++ },
        )
        assertEquals(SwitchResult.OK, r)
        assertEquals(2, attempts)
        assertEquals(1, busyReported)
        assertTrue("polled, not a fixed 5 s pause: ${clock.t}", clock.t in 1_000L..1_200L)
    }

    @Test
    fun aReloadThatNeverEndsGivesBusyBackAfterTheWait() = runBlocking {
        val clock = Clock()
        var attempts = 0
        val r = SwitchRetry.run({ attempts++; SwitchResult.BUSY_RELOADING }, { true }, { clock.t += it }, { clock.t })
        assertEquals(SwitchResult.BUSY_RELOADING, r)
        assertEquals(1, attempts)
        assertTrue(clock.t >= SwitchRetry.WAIT_MS)
    }

    @Test
    fun aFailureIsNotRetried() = runBlocking {
        var attempts = 0
        val r = SwitchRetry.run({ attempts++; SwitchResult.FAILED }, { false }, { }, { 0L })
        assertEquals(SwitchResult.FAILED, r)
        assertEquals(1, attempts)
    }
}
