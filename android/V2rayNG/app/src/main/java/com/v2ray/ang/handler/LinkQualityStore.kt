package com.v2ray.ang.handler

import com.v2ray.ang.net.LinkQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Jitter and loss of the gaming servers, measured after a list check (see [LinkQuality]). Only servers a plain TCP connect says
 * something about are measured (UDP-based servers, groups and info lines have no such address and get no mark).
 * Results live in memory and are replaced by the next measurement.
 */
object LinkQualityStore {
    private const val SAMPLES = 8
    private const val GAP_MS = 120L
    private const val TIMEOUT_MS = 1_500
    private const val PARALLEL = 12

    private val state = MutableStateFlow<Map<String, LinkQuality.Result>>(emptyMap())
    val results: StateFlow<Map<String, LinkQuality.Result>> = state.asStateFlow()

    /** Measures [guids] (a dozen at a time) and publishes each result as soon as it is ready. */
    suspend fun measure(guids: List<String>) = withContext(Dispatchers.IO) {
        val permits = Semaphore(PARALLEL)
        coroutineScope {
            guids.forEach { guid ->
                launch {
                    permits.withPermit {
                        val target = ServerUsable.tcpTarget(guid) ?: return@withPermit
                        val result = LinkQuality.evaluate(sample(target.first, target.second))
                        if (result != null) state.value = state.value + (guid to result)
                    }
                }
            }
        }
    }

    /** One address lookup, then [SAMPLES] separate connects with a short pause; a failed or slow one is -1. */
    private suspend fun sample(host: String, port: Int): List<Long> {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Long>(SAMPLES)
        repeat(SAMPLES) {
            val start = System.currentTimeMillis()
            val ok = runCatching { Socket().use { it.connect(InetSocketAddress(address, port), TIMEOUT_MS) } }.isSuccess
            out += if (ok) (System.currentTimeMillis() - start).coerceAtLeast(1L) else -1L
            delay(GAP_MS)
        }
        return out
    }
}
