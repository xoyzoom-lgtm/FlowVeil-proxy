package com.v2ray.ang.handler

import android.net.Network
import com.v2ray.ang.AppConfig
import com.v2ray.ang.net.IpParser
import com.v2ray.ang.net.PingUrls
import com.v2ray.ang.net.ProbeStep
import com.v2ray.ang.util.LogUtil
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * HTTP probes over one of two routes, and always said out loud in the (developer) log:
 *  - [Route.PHYSICAL]: bound to the real mobile / Wi-Fi network with its socket factory and its
 *    resolver, so the request never enters our own tunnel whatever the routes are;
 *  - [Route.TUNNEL]: through the local HTTP proxy of the running core, i.e. through the server.
 * (Not `addDisallowedApplication`: that would pull all of the app's traffic out of the tunnel.)
 */
object NetProbe {
    enum class Route(val label: String) { PHYSICAL("physical"), TUNNEL("tunnel") }

    const val PROBE_TIMEOUT_MS = 4_000L

    val GSTATIC_204 = PingUrls.PRIMARY

    /** The 204 check of the whitelist bypass: see [PingUrls.WHITELIST]. */
    val BYPASS_204 = PingUrls.WHITELIST

    /** Small real downloads for the "data really flows" step; the first one that completes wins. */
    val CONTENT_URLS = listOf(
        "https://www.google.com/robots.txt",
        "https://www.gstatic.com/images/branding/product/1x/googleg_48dp.png",
        "https://www.cloudflare.com/favicon.ico",
    )

    /**
     * IP lookup services: a Russian one first (reachable when only domestic sites are), then
     * two well-known plain-text ones. Only an IP address is read out of the reply.
     */
    val IP_SERVICES = listOf(
        "https://ipv4-internet.yandex.net/api/v0/ip",
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
    )
    val IPV6_SERVICES = listOf("https://api6.ipify.org")

    /** Domestic sites: if these answer on the mobile network directly, the network itself is alive. */
    val DOMESTIC_SITES = listOf("https://ya.ru", "https://vk.com", "https://mail.ru")

    /** Foreign reference hosts for the direct (whitelist) diagnosis. */
    val FOREIGN_SITES = listOf(
        GSTATIC_204, // Google
        PingUrls.FALLBACK, // Cloudflare
        "https://www.msftconnecttest.com/connecttest.txt", // Microsoft
        "https://captive.apple.com/hotspot-detect.html", // Apple
        "https://detectportal.firefox.com/success.txt", // Mozilla
    )

    /** A plain TCP connect to [host]:[port] over [network] (null = the system's default), no HTTP: told apart from an HTTP answer. */
    fun tcpReachable(network: Network?, host: String, port: Int = 443, timeoutMs: Long = PROBE_TIMEOUT_MS): Boolean = try {
        val address = (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).firstOrNull()
        if (address == null) {
            false
        } else {
            (network?.socketFactory?.createSocket() ?: java.net.Socket()).use { socket ->
                socket.connect(InetSocketAddress(address, port), timeoutMs.toInt())
                true
            }
        }
    } catch (e: Exception) {
        false
    }

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')

    private const val MAX_BODY = 16 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"

    class Client internal constructor(private val http: OkHttpClient, val route: Route) : java.io.Closeable {

        /** One request. With [readBody] the body (up to 16 KB) is read and its size reported. */
        fun get(url: String, readBody: Boolean = false, maxBody: Int = MAX_BODY): Result {
            val host = url.substringAfter("://").substringBefore('/')
            return try {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
                http.newCall(request).execute().use { response ->
                    var size = 0
                    var text: String? = null
                    val stream = if (readBody) response.body?.byteStream() else null
                    if (stream != null) {
                        val buffer = ByteArray(2048)
                        val collected = java.io.ByteArrayOutputStream()
                        while (size < maxBody) {
                            val n = stream.read(buffer, 0, minOf(buffer.size, maxBody - size))
                            if (n < 0) break
                            size += n
                            if (collected.size() < 2048) collected.write(buffer, 0, minOf(n, 2048 - collected.size()))
                        }
                        text = collected.toString(Charsets.UTF_8.name())
                    }
                    val step = if (response.code in 200..299 || response.code == 204) ProbeStep.ok(response.code, size)
                    else ProbeStep(ProbeStep.Kind.BAD_STATUS, response.code, size)
                    LogUtil.d(AppConfig.TAG, "NetProbe[${route.label}] $host -> HTTP ${response.code}")
                    Result(step, text, com.v2ray.ang.net.HttpDate.parse(response.header("Date")))
                }
            } catch (e: Exception) {
                val kind = classify(e)
                LogUtil.d(AppConfig.TAG, "NetProbe[${route.label}] $host -> $kind")
                Result(ProbeStep(kind), null)
            }
        }

        /** True when the request came back with any HTTP status: the network really carried it. */
        fun reachable(url: String): Boolean {
            val host = url.substringAfter("://").substringBefore('/')
            return try {
                val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).head().build()
                http.newCall(request).execute().use { true }
            } catch (e: Exception) {
                LogUtil.d(AppConfig.TAG, "NetProbe[${route.label}] $host -> ${classify(e)}")
                false
            }
        }

        /** The address the internet sees, asked from [services] one by one; null when none answers. */
        fun fetchIp(services: List<String>): String? {
            for (url in services) {
                val result = get(url, readBody = true, maxBody = 512)
                if (result.step.kind == ProbeStep.Kind.OK) IpParser.parse(result.body)?.let { return it }
            }
            return null
        }

        /** Cancels every request of this client that is still running (the coroutine waiting for them was cancelled). */
        fun cancelAll() {
            runCatching { http.dispatcher.cancelAll() }
        }

        override fun close() {
            runCatching { http.connectionPool.evictAll() }
            runCatching { http.dispatcher.executorService.shutdown() }
        }
    }

    /** [serverDateMs]: the time the server put into its `Date` header (used to judge the phone clock). */
    data class Result(val step: ProbeStep, val body: String?, val serverDateMs: Long? = null)

    private fun classify(e: Exception): ProbeStep.Kind = when {
        e is SocketTimeoutException || (e is InterruptedIOException && e.message?.contains("timeout", true) == true) -> ProbeStep.Kind.TIMEOUT
        e is ConnectException -> ProbeStep.Kind.REFUSED
        e is IOException -> ProbeStep.Kind.IO
        else -> ProbeStep.Kind.IO
    }

    /** Client bound to [network] (null = the system's default network, used when no tunnel is up). */
    fun physical(network: Network?, timeoutMs: Long = PROBE_TIMEOUT_MS): Client {
        val builder = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 1_000L, TimeUnit.MILLISECONDS)
        if (network != null) {
            builder.socketFactory(network.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
        }
        return Client(builder.build(), Route.PHYSICAL)
    }

    /**
     * Client that goes through the local proxy of the running core, i.e. through the server.
     * [route] says where that proxy listens (see [LocalProxy]); null = FlowVeil's own settings.
     */
    fun tunnel(route: LocalProxy.Route? = null, timeoutMs: Long = PROBE_TIMEOUT_MS): Client {
        val type = route?.type ?: Proxy.Type.HTTP
        val port = route?.port ?: SettingsManager.getHttpPort()
        val user = if (route != null) route.user else SettingsManager.getSocksUsername()
        val pass = if (route != null) route.pass else SettingsManager.getSocksPassword()
        val builder = OkHttpClient.Builder()
            .proxy(Proxy(type, InetSocketAddress(AppConfig.LOOPBACK, port)))
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 1_000L, TimeUnit.MILLISECONDS)
        if (type == Proxy.Type.HTTP && !user.isNullOrBlank() && !pass.isNullOrBlank()) {
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass)).build()
            }
        }
        return Client(builder.build(), Route.TUNNEL)
    }
}
