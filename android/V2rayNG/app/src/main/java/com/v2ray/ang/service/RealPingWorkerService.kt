package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PingPrefs
import com.v2ray.ang.handler.PingProbes
import com.v2ray.ang.net.PingChoice
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object RealPingExecutionLimiter {
    private val customConfigMutex = Mutex()

    /** Every TUIC test starts its own sing-box process: a few at a time keep the phone responsive. */
    private const val MAX_PARALLEL_TUIC = 3
    private val tuicPermits = Semaphore(MAX_PARALLEL_TUIC)

    suspend fun <T> run(configType: EConfigType, block: () -> T): T {
        // Custom profiles bypass speed-test trimming and start complete Xray configs.
        // Parallel teardown can abort the native probe process, so serialize their
        // JNI measurements globally across batches.
        return if (configType == EConfigType.CUSTOM) {
            customConfigMutex.withLock { block() }
        } else if (configType == EConfigType.TUIC) {
            tuicPermits.withPermit { block() }
        } else {
            block()
        }
    }
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    /** The way the user chose in the "Ping" sheet (an explicit TCP request from the caller wins). */
    private val method = if (onlyTcp) PingChoice.Method.TCP else PingPrefs.method()
    private val timeoutSeconds = PingPrefs.timeoutSeconds()
    private val double = PingPrefs.bestOfTwo()
    private val quick = PingChoice.isQuick(method)
    private val results = ConcurrentHashMap<String, Long>()

    private val job = SupervisorJob()
    // Too many parallel handshakes choke mobile networks and make healthy servers time out.
    private val concurrency = if (quick) SettingsManager.getRealPingConcurrency() else SettingsManager.getRealPingConcurrency().coerceAtMost(8)
    private val dispatcher = Executors.newFixedThreadPool(if (quick) concurrency * 2 else concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        // Ordinary servers first: the slow one-by-one JSON checks must not hold up the quick ones.
        val ordered = if (quick) guids else guids.sortedBy { SpeedtestConfig.limiterType(it) == EConfigType.CUSTOM }
        val jobs = ordered.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = try {
                        val raw = measure(guid, method)
                        PingChoice.applyTimeout(raw, timeoutSeconds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        -1L
                    }
                    results[guid] = result
                    // Always report, so a failed test never leaves the row without a result.
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Result(guid, result))
                    }
                } catch (_: CancellationException) {
                    // cancelled
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Progress("$left / $count"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                // A quick way that failed for every server says the way does not work on this network, not that all servers
                // are dead: the list is checked again through the servers (once), so it never ends as one red column.
                if (isActive && quick && PingChoice.allFailed(results.values)) {
                    val again = guids.map { guid ->
                        scope.launch {
                            val r = runCatching { PingChoice.applyTimeout(startRealPing(guid), timeoutSeconds) }.getOrDefault(-1L)
                            if (scope.isActive) onEvent(RealPingEvent.Result(guid, r))
                        }
                    }
                    joinAll(*again.toTypedArray())
                }
                if (isActive) {
                    onEvent(RealPingEvent.Finish("0"))
                }
            } catch (_: CancellationException) {
                // If cancelled, don't send finish event to avoid confusion
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private suspend fun startRealPing(guid: String): Long =
        RealPingExecutionLimiter.run(SpeedtestConfig.limiterType(guid)) { SpeedtestConfig.measure(context, guid) }

    /** One server by the chosen way; servers that cannot be reached by TCP (UDP-based, groups) always go through the core. */
    private suspend fun measure(guid: String, m: PingChoice.Method): Long {
        val config = MmkvManager.decodeServerConfig(guid) ?: return -1L
        val port = config.serverPort?.toIntOrNull()
        val host = config.server?.trim().orEmpty()
        val udp = config.configType == EConfigType.HYSTERIA2 || config.configType == EConfigType.TUIC ||
            config.configType == EConfigType.WIREGUARD || config.configType.isComplexType() ||
            config.alpn?.split(',')?.all { it.trim().startsWith("h3") } == true
        val tcpOk = PingChoice.tcpApplicable(udp, host.isNotEmpty() && port != null)
        val timeoutMs = (timeoutSeconds * 1000L).coerceIn(1000L, 15_000L)
        return when {
            m == PingChoice.Method.PROXY || !tcpOk -> proxy(guid)
            m == PingChoice.Method.TCP -> PingProbes.tcp(host, port!!, timeoutMs)
            m == PingChoice.Method.TLS -> {
                val tls = config.security == "tls" || config.security == "reality"
                if (tls) PingProbes.tls(host, port!!, config.sni, timeoutMs) else PingProbes.tcp(host, port!!, timeoutMs)
            }
            m == PingChoice.Method.ICMP -> PingProbes.icmp(host, timeoutSeconds)
            else -> { // SMART: TCP first, only a server that answered it gets the real request
                if (PingProbes.tcp(host, port!!, timeoutMs) > 0) proxy(guid) else -1L
            }
        }
    }

    private suspend fun proxy(guid: String): Long {
        val first = startRealPing(guid)
        return if (double) PingChoice.bestOfTwo(first, startRealPing(guid)) else first
    }
}
