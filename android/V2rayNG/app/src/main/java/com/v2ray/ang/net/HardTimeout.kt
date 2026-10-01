package com.v2ray.ang.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/** What a call that cannot be cancelled came back with. */
sealed class HardResult<out T> {
    data class Done<T>(val value: T) : HardResult<T>()
    /** It did not come back in time (or the caller was cancelled): the caller moves on, the call may still be running. */
    object TimedOut : HardResult<Nothing>()
    data class Failed(val error: Throwable) : HardResult<Nothing>()
}

/**
 * Runs blocking calls that no coroutine cancellation can stop (the native delay test, a socket read) so that the caller's
 * time limit really holds: the call runs on its own thread, the caller waits cancellably and gives up at the limit.
 *
 * A call that was given up on is NOT forgotten: it keeps its slot until it really returns. New calls wait for a free slot
 * (inside the same time limit), so a few hung native tests can never pile up into dozens of running cores.
 */
class AbandonableCalls(val slots: Int) {
    private val sem = Semaphore(slots.coerceAtLeast(1))
    private val busy = java.util.concurrent.atomic.AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r -> Thread(r, "hard-call").apply { isDaemon = true } })

    /** Calls that are running right now, including the ones the caller gave up on. */
    val running: Int get() = busy.get()

    suspend fun <T> call(timeoutMs: Long, block: () -> T): HardResult<T> {
        val outcome = withTimeoutOrNull(timeoutMs) {
            sem.acquire()
            val done = CompletableDeferred<Result<T>>()
            busy.incrementAndGet()
            try {
                pool.execute {
                    val r = runCatching(block)
                    busy.decrementAndGet()
                    sem.release()
                    done.complete(r)
                }
            } catch (e: Throwable) {
                busy.decrementAndGet()
                sem.release()
                throw e
            }
            done.await()
        } ?: return HardResult.TimedOut
        return outcome.fold({ HardResult.Done(it) }, { HardResult.Failed(it) })
    }
}

/**
 * Runs [block] and, when the coroutine is cancelled (a time limit or a stop), calls [onCancel] at once from another
 * thread, so a blocking request inside [block] is torn down instead of finishing in its own time. [onCancel] also runs
 * after a normal finish, when there is nothing left to cancel.
 */
suspend fun <T> cancellingWith(onCancel: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val hook = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            onCancel()
        }
    }
    try {
        block()
    } finally {
        hook.cancel()
    }
}
