package com.v2ray.ang.handler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.v2ray.ang.net.PairProtocol
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Sends subscription links to a computer that shows a FlowVeil Pair QR code. Never logs links, tokens or keys. */
object PairClient {
    enum class Result { OK, BAD_TOKEN, EXPIRED, USED, LOCKED, REJECTED, NOT_HOME_NETWORK, NO_ROUTE }

    /** The name of the computer from /info, or null when it cannot be reached. */
    suspend fun info(context: Context, qr: PairProtocol.Qr): String? = withContext(Dispatchers.IO) {
        if (!PairProtocol.isPrivateHost(qr.host)) return@withContext null
        try {
            val conn = open(context, "http://${qr.host}:${qr.port}/p/${qr.sid}/info")
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.use { it.readBytes().take(4096).toByteArray().toString(Charsets.UTF_8) }
            Regex("\"device\"\\s*:\\s*\"([^\"]{0,60})\"").find(body)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            null
        }
    }

    suspend fun send(context: Context, qr: PairProtocol.Qr, items: List<String>, name: String?): Result =
        withContext(Dispatchers.IO) {
            if (!PairProtocol.isPrivateHost(qr.host)) return@withContext Result.NOT_HOME_NETWORK
            val body = PairProtocol.sendBody(qr, PairProtocol.payloadJson(items, name), Build.MODEL ?: "Android")
            post(context, "http://${qr.host}:${qr.port}/p/${qr.sid}/send", body)
        }

    /** Manual entry: "192.168.1.5:5123" and the 6-digit code from the computer's screen. */
    suspend fun sendWithCode(context: Context, hostPort: String, code: String, items: List<String>, name: String?): Result =
        withContext(Dispatchers.IO) {
            val host = hostPort.substringBeforeLast(':', "")
            val port = hostPort.substringAfterLast(':', "").toIntOrNull()
            if (host.isEmpty() || port == null || !PairProtocol.isPrivateHost(host)) return@withContext Result.NOT_HOME_NETWORK
            val body = PairProtocol.codeBody(code.trim(), PairProtocol.payloadJson(items, name), Build.MODEL ?: "Android")
            post(context, "http://$host:$port/c", body)
        }

    private fun post(context: Context, url: String, body: String): Result = try {
        val conn = open(context, url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        when (conn.responseCode) {
            200 -> Result.OK
            403 -> Result.BAD_TOKEN
            429 -> Result.LOCKED
            410 -> {
                val text = conn.errorStream?.use { it.readBytes().take(512).toByteArray().toString(Charsets.UTF_8) }.orEmpty()
                if (text.contains("used")) Result.USED else Result.EXPIRED
            }
            404 -> Result.EXPIRED
            else -> Result.REJECTED
        }
    } catch (e: Exception) {
        LogUtil.w("PairClient", "send failed: ${e.javaClass.simpleName}")
        Result.NO_ROUTE
    }

    private fun open(context: Context, url: String): HttpURLConnection {
        val network = lanNetwork(context)
        val conn = (if (network != null) network.openConnection(URL(url)) else URL(url).openConnection()) as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 8_000
        conn.instanceFollowRedirects = false
        return conn
    }

    /** Wi-Fi or cable, never a tunnel: when the tunnel is on, the computer must still be reached directly. */
    @Suppress("DEPRECATION")
    private fun lanNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }
}
