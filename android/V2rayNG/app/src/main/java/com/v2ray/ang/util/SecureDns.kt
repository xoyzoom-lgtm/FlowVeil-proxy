package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS over HTTPS (Cloudflare, then Google), used when the ISP's DNS hides a subscription host.
 * The resolvers are addressed by IP, so no plain DNS lookup is needed.
 */
object SecureDns : Dns {
    private val resolvers = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
    )
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        runCatching { if (hostname.all { it.isDigit() || it == '.' }) return listOf(InetAddress.getByName(hostname)) }
        for (template in resolvers) {
            try {
                val request = Request.Builder()
                    .url(template.format(hostname))
                    .header("Accept", "application/dns-json")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val answers = JSONObject(body).optJSONArray("Answer") ?: return@use
                    val ips = (0 until answers.length())
                        .mapNotNull { answers.optJSONObject(it) }
                        .filter { it.optInt("type") == 1 }
                        .mapNotNull { runCatching { InetAddress.getByName(it.optString("data")) }.getOrNull() }
                    if (ips.isNotEmpty()) return ips
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Secure DNS lookup failed via $template", e)
            }
        }
        throw UnknownHostException("Secure DNS could not resolve $hostname")
    }
}
