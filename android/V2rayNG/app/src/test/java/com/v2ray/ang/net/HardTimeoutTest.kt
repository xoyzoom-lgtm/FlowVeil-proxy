package com.v2ray.ang.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HardTimeoutTest {

    @Test
    fun aQuickCallReturnsItsValue() = runBlocking {
        val calls = AbandonableCalls(2)
        val r = calls.call(1_000) { 7 }
        assertEquals(HardResult.Done(7), r)
    }

    @Test
    fun aHungCallIsGivenUpOnAtTheLimitButKeepsItsSlot() = runBlocking {
        val calls = AbandonableCalls(1)
        val release = CountDownLatch(1)
        val t0 = System.nanoTime()
        val r = calls.call(150) { release.await(); 1 }
        val took = (System.nanoTime() - t0) / 1_000_000
        assertEquals(HardResult.TimedOut, r)
        assertTrue("gave up in time: $took ms", took in 100..600)
        assertEquals("the hung call still counts as running", 1, calls.running)

        // The only slot is held by the hung call: the next call cannot even start and times out waiting.
        val second = calls.call(150) { 2 }
        assertEquals(HardResult.TimedOut, second)

        release.countDown()
        Thread.sleep(100)
        assertEquals(0, calls.running)
        assertEquals(HardResult.Done(3), calls.call(1_000) { 3 })
    }

    @Test
    fun anExceptionComesBackAsAFailure() = runBlocking {
        val r = AbandonableCalls(1).call(1_000) { error("boom") }
        assertTrue(r is HardResult.Failed)
    }

    @Test
    fun cancellingTheCallerFreesItAtOnceWhileTheCallKeepsRunning() = runBlocking {
        val calls = AbandonableCalls(2)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val job = async(Dispatchers.Default) { calls.call(10_000) { started.countDown(); release.await(); 1 } }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        job.cancel()
        job.join()
        assertTrue("the waiting caller is released immediately", (System.nanoTime() - t0) / 1_000_000 < 500)
        assertEquals(1, calls.running)
        release.countDown()
    }

    @Test
    fun cancellingTearsDownABlockingRequest() = runBlocking(Dispatchers.IO) {
        val blocked = CountDownLatch(1)
        val t0 = System.nanoTime()
        val r = withTimeoutOrNull(200) {
            cancellingWith(onCancel = { blocked.countDown() }) { blocked.await(); "finished" }
        }
        val took = (System.nanoTime() - t0) / 1_000_000
        assertEquals(null, r)
        assertTrue("the limit held: $took ms", took < 1_500)
    }

    @Test
    fun withoutACancelTheBlockIsLeftAlone() = runBlocking(Dispatchers.IO) {
        var cancelled = false
        val r = coroutineScope { cancellingWith(onCancel = { cancelled = true }) { "done" } }
        assertEquals("done", r)
        // the hook runs after a normal finish too, harmlessly
        Thread.sleep(50)
        assertTrue(cancelled)
    }
}
