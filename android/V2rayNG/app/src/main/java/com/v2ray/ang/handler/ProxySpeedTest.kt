package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Speed of the running connection, measured like common speed tests: several parallel streams
 * through the local proxy, the first seconds (TCP ramp-up) are not counted.
 */
object ProxySpeedTest {
    data class Result(val downloadMbps: Double?, val uploadMbps: Double?, val pingMs: Long?)

    private const val STREAMS = 6
    private const val WARMUP_MS = 2_000L
    private const val DOWNLOAD_MS = 10_000L
    private const val UPLOAD_MS = 8_000L

    private val downloadUrls = listOf(
        "https://speed.cloudflare.com/__down?bytes=200000000",
        "https://proof.ovh.net/files/1Gb.dat",
        "http://speedtest.tele2.net/1GB.zip",
    )
    private const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    private const val PING_URL = "https://speed.cloudflare.com/__down?bytes=0"

    fun run(): Result {
        val client = buildClient()
        return try {
            val ping = measurePing(client)
            val down = downloadUrls.firstNotNullOfOrNull { url -> measure(DOWNLOAD_MS) { counter, stop -> download(client, url, counter, stop) } }
            val up = measure(UPLOAD_MS) { counter, stop -> upload(client, counter, stop) }
            Result(down, up, ping)
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun buildClient(): OkHttpClient {
        val user = SettingsManager.getSocksUsername()
        val pass = SettingsManager.getSocksPassword()
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", SettingsManager.getHttpPort())))
            .apply {
                if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass)).build()
                    }
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Best of a few small requests over a warm connection. */
    private fun measurePing(client: OkHttpClient): Long? {
        var best: Long? = null
        repeat(4) {
            runCatching {
                val start = System.nanoTime()
                client.newCall(Request.Builder().url(PING_URL).build()).execute().use { it.body?.bytes() }
                val ms = (System.nanoTime() - start) / 1_000_000
                if (it > 0) best = minOf(best ?: ms, ms) // first call also opens the connection
            }
        }
        return best
    }

    /**
     * Runs [STREAMS] workers that add transferred bytes to a counter and returns Mbit/s over the
     * time after the warm-up, or null when nothing was transferred.
     */
    private fun measure(durationMs: Long, worker: (AtomicLong, AtomicBoolean) -> Unit): Double? {
        val counter = AtomicLong()
        val stop = AtomicBoolean(false)
        val threads = (1..STREAMS).map {
            thread(isDaemon = true, name = "speedtest-$it") {
                runCatching { worker(counter, stop) }.onFailure { e -> LogUtil.w(AppConfig.TAG, "Speed test stream: ${e.message}") }
            }
        }
        Thread.sleep(WARMUP_MS)
        val startBytes = counter.get()
        val start = System.nanoTime()
        Thread.sleep(durationMs - WARMUP_MS)
        val bytes = counter.get() - startBytes
        val seconds = (System.nanoTime() - start) / 1e9
        stop.set(true)
        threads.forEach { it.join(3_000) }
        return if (bytes <= 0) null else bytes * 8 / seconds / 1_000_000
    }

    private fun download(client: OkHttpClient, url: String, counter: AtomicLong, stop: AtomicBoolean) {
        val buffer = ByteArray(64 * 1024)
        while (!stop.get()) {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val stream = response.body?.byteStream() ?: return
                while (!stop.get()) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    counter.addAndGet(read.toLong())
                }
            }
        }
    }

    private fun upload(client: OkHttpClient, counter: AtomicLong, stop: AtomicBoolean) {
        val chunk = ByteArray(64 * 1024)
        while (!stop.get()) {
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = 25L * 1024 * 1024
                override fun writeTo(sink: BufferedSink) {
                    var left = contentLength()
                    while (left > 0 && !stop.get()) {
                        val n = minOf(left, chunk.size.toLong()).toInt()
                        sink.write(chunk, 0, n)
                        sink.flush()
                        counter.addAndGet(n.toLong())
                        left -= n
                    }
                    if (left > 0) throw java.io.IOException("stopped")
                }
            }
            runCatching {
                client.newCall(Request.Builder().url(UPLOAD_URL).post(body).build()).execute().use { }
            }
        }
    }
}
