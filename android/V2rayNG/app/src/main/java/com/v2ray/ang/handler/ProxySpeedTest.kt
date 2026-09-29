package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Download speed through the running connection: reads a test file via the local proxy for a
 * few seconds and returns megabits per second, or null when nothing could be downloaded.
 */
object ProxySpeedTest {
    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=50000000"
    private const val DURATION_MS = 8_000L

    fun run(): Double? {
        val port = SettingsManager.getHttpPort()
        val user = SettingsManager.getSocksUsername()
        val pass = SettingsManager.getSocksPassword()
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .apply {
                if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
                    proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass)).build()
                    }
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return try {
            client.newCall(Request.Builder().url(TEST_URL).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val stream = response.body?.byteStream() ?: return null
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                val start = System.nanoTime()
                val deadline = start + DURATION_MS * 1_000_000
                while (System.nanoTime() < deadline) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                }
                val seconds = (System.nanoTime() - start) / 1e9
                if (total <= 0 || seconds <= 0) null else total * 8 / seconds / 1_000_000
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Speed test failed", e)
            null
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
