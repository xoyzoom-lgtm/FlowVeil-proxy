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
    private val quick = method != PingChoice.Method.PROXY

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
                        val raw = when (method) {
                            PingChoice.Method.PROXY -> startRealPing(guid)
                            PingChoice.Method.TCP -> startTcping(guid)
                            PingChoice.Method.ICMP -> startIcmp(guid)
                        }
                        PingChoice.applyTimeout(raw, timeoutSeconds)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        -1L
                    }
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

    private fun startTcping(guid: String): Long {
        val retFailure = -1L

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        if (!config.configType.isComplexType()
            && config.configType != EConfigType.HYSTERIA2
            && config.configType != EConfigType.TUIC
            && config.configType != EConfigType.WIREGUARD
            && config.alpn?.split(',')?.all { it.trim().startsWith("h3") } != true
            && config.server.isNotNullEmpty()
            && config.serverPort?.toIntOrNull() != null
        ) {
            val url = config.server.orEmpty()
            val port = config.serverPort.orEmpty().toInt()
            val tcpTime = SpeedtestManager.socketConnectTime(url, port, (timeoutSeconds * 1000).coerceIn(1000, 15_000))

            return tcpTime
        }

        return retFailure
    }

    /** The system ping, one packet; -1 when the server does not answer it (many do not) or the address is unknown. */
    private fun startIcmp(guid: String): Long {
        val host = MmkvManager.decodeServerConfig(guid)?.server?.trim()?.removePrefix("[")?.removeSuffix("]")
        if (host.isNullOrEmpty() || host.any { it.isWhitespace() || it == ';' || it == '&' || it == '|' }) return -1L
        return runCatching {
            val wait = timeoutSeconds.coerceIn(1, 15)
            val p = ProcessBuilder("ping", "-c", "1", "-W", wait.toString(), host).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { r -> r.readText() }
            if (!p.waitFor(wait + 2L, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
            PingChoice.icmpTime(out)
        }.getOrDefault(-1L)
    }
}
