package com.v2ray.ang.handler

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocketFactory

/**
 * The quick checks of the "Ping" sheet that need no core: TCP connect, TCP + TLS handshake and the system ping.
 * Each returns the time in ms, or -1 when there was no answer within [timeoutMs]. Nothing is sent but the handshake.
 */
object PingProbes {
    private fun resolve(host: String, timeoutMs: Long): List<InetAddress> {
        val task = FutureTask { InetAddress.getAllByName(host).toList() }
        Thread(task, "ping-dns").apply { isDaemon = true; start() }
        return runCatching { task.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
            .sortedBy { it.address.size != 4 } // IPv4 first
    }

    /**
     * A connect to host:port. The name is resolved with a time limit and every address (three at most) is tried; one more
     * attempt follows when time is left, because the first packet of a mobile network is lost more often than it should be.
     */
    fun tcp(host: String, port: Int, timeoutMs: Long): Long {
        val t0 = System.currentTimeMillis()
        val left = { timeoutMs - (System.currentTimeMillis() - t0) }
        val addresses = resolve(host, timeoutMs / 2).take(3)
        if (addresses.isEmpty()) return -1L
        repeat(2) { attempt ->
            for (a in addresses) {
                if (left() < 300L) return -1L
                val per = if (attempt == 0) (left() / addresses.size).coerceAtLeast(500L) else left()
                val start = System.currentTimeMillis()
                val ok = runCatching { Socket().use { it.connect(InetSocketAddress(a, port), per.toInt()) } }.isSuccess
                if (ok) return (System.currentTimeMillis() - start).coerceAtLeast(1L)
            }
        }
        return -1L
    }

    /**
     * TCP connect plus the TLS handshake with [sni] (the server's name if empty). A handshake that ends in a certificate
     * error still proves that the port answers TLS (REALITY servers answer with another site's certificate), so it counts;
     * a reset, a timeout or a closed port does not.
     */
    fun tls(host: String, port: Int, sni: String?, timeoutMs: Long): Long {
        val t0 = System.currentTimeMillis()
        val address = resolve(host, timeoutMs / 2).firstOrNull() ?: return -1L
        val rest = (timeoutMs - (System.currentTimeMillis() - t0)).coerceAtLeast(500L).toInt()
        return runCatching {
            val start = System.currentTimeMillis()
            val raw = Socket()
            raw.connect(InetSocketAddress(address, port), rest)
            raw.soTimeout = rest
            val name = sni?.takeIf { it.isNotBlank() } ?: host
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, name, port, true) as SSLSocket
            ssl.use { s ->
                val params: SSLParameters = s.sslParameters
                if (!name.all { it.isDigit() || it == '.' || it == ':' }) params.serverNames = listOf(SNIHostName(name))
                s.sslParameters = params
                try {
                    s.startHandshake()
                } catch (e: SSLHandshakeException) {
                    // The server answered with a certificate we do not accept: it did answer.
                    if (e.cause !is java.security.cert.CertificateException && e.message?.contains("certificate", true) != true) throw e
                }
            }
            (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }.getOrDefault(-1L)
    }

    /** The system ping, one packet; -1 when there is no answer (many servers do not answer it) or the address is unusable. */
    fun icmp(host: String, timeoutSeconds: Int): Long {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h.any { it.isWhitespace() || it == ';' || it == '&' || it == '|' || it == '$' || it == '`' }) return -1L
        val wait = timeoutSeconds.coerceIn(1, 15)
        val viaCommand = runCatching {
            val p = ProcessBuilder("ping", "-c", "1", "-W", wait.toString(), h).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { r -> r.readText() }
            if (!p.waitFor(wait + 2L, TimeUnit.SECONDS)) p.destroy()
            com.v2ray.ang.net.PingChoice.icmpTime(out)
        }.getOrDefault(-1L)
        if (viaCommand > 0) return viaCommand
        // Phones without the ping command: the system's own reachability check.
        return runCatching {
            val start = System.currentTimeMillis()
            if (InetAddress.getByName(h).isReachable(wait * 1000)) (System.currentTimeMillis() - start).coerceAtLeast(1L) else -1L
        }.getOrDefault(-1L)
    }
}
