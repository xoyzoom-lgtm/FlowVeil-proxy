package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * "Add to a TV": a TV has no keyboard and no camera, so it shows a QR code that leads to a tiny page
 * on the local network; the phone opens it, the subscription link is pasted there and arrives here.
 *
 * Kept small on purpose: the server listens only on the TV's own LAN address, only while the screen
 * is open (and for [LIFETIME_MS] at most), every request needs the random token that is in the QR,
 * bodies are capped, and the only thing accepted is one http(s) link. The link travels over plain
 * HTTP inside the home network, like a code typed on a remote; it is not for public Wi-Fi.
 */
class TvReceiver(private val onLink: (String) -> Unit) : Closeable {
    private val token = newToken()

    @Volatile
    private var server: ServerSocket? = null

    /** The address to put into the QR code; null when the device is on no local network. */
    fun start(): String? {
        val ip = lanAddress() ?: return null
        val socket = ServerSocket(0, 4, InetAddress.getByName(ip))
        server = socket
        thread(name = "tv-receiver", isDaemon = true) {
            val deadline = System.currentTimeMillis() + LIFETIME_MS
            socket.soTimeout = 1_000
            while (!socket.isClosed && System.currentTimeMillis() < deadline) {
                val client = try {
                    socket.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                runCatching { handle(client) }.onFailure { LogUtil.w(AppConfig.TAG, "TvReceiver: request failed") }
                runCatching { client.close() }
            }
            runCatching { socket.close() }
        }
        return "http://$ip:${socket.localPort}/?$MARKER=1&t=$token"
    }

    override fun close() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val input = client.getInputStream().buffered()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, target) = lines[0].split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val out = client.getOutputStream()
        when {
            method == "GET" && target.substringBefore('?') == "/" -> {
                val query = parseForm(target.substringAfter('?', ""))
                if (!tokenOk(query["t"])) reply(out, 403, page("Ссылка устарела. Покажите QR-код на ТВ заново."))
                else reply(out, 200, form())
            }
            method == "POST" && target == "/add" && length in 1..MAX_BODY -> {
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val form = parseForm(String(body, 0, read, Charsets.UTF_8))
                val link = form["url"]?.trim().orEmpty()
                when {
                    !tokenOk(form["t"]) -> reply(out, 403, page("Ссылка устарела. Покажите QR-код на ТВ заново."))
                    !validLink(link) -> reply(out, 400, form("Нужна ссылка вида https://…"))
                    else -> {
                        onLink(link)
                        reply(out, 200, page("Готово. Подписка отправлена на ТВ, смотрите на экран."))
                    }
                }
            }
            else -> reply(out, 404, page("Не найдено"))
        }
    }

    private fun tokenOk(given: String?): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(), token.toByteArray())

    private fun readHead(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
        }
        return null
    }

    private fun reply(out: java.io.OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write(
            ("HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun page(text: String) =
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"font:18px system-ui;margin:24px\"><h2>FlowVeil</h2><p>${text.escapeHtml()}</p></body>"

    private fun form(error: String? = null) =
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<body style=\"font:18px system-ui;margin:24px\"><h2>FlowVeil на ТВ</h2>" +
            "<p>Вставьте ссылку подписки, она придёт на ТВ.</p>" +
            (error?.let { "<p style=color:#c00>${it.escapeHtml()}</p>" } ?: "") +
            "<form method=post action=/add><input type=hidden name=t value=$token>" +
            "<input name=url type=url required autofocus placeholder=\"https://…\" style=\"width:100%;font-size:18px;padding:12px;box-sizing:border-box\">" +
            "<p><button style=\"font-size:18px;padding:12px 24px\">Отправить на ТВ</button></p></form></body>"

    private fun String.escapeHtml() = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        /** Marks our own address, so a scan of the QR by the app's scanner is not taken for a subscription. */
        const val MARKER = "fv_tv"
        private const val LIFETIME_MS = 10 * 60_000L
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 4 * 1024

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return (1..12).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        }

        /** The address on the home network (Wi-Fi or cable); null on mobile data only. */
        internal fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        internal fun validLink(link: String): Boolean =
            link.length in 8..2000 && link.none { it.isWhitespace() } &&
                (link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)) &&
                !link.contains("$MARKER=1")

        internal fun parseForm(text: String): Map<String, String> = text.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else runCatching {
                URLDecoder.decode(pair.substring(0, i), "UTF-8") to URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }.getOrNull()
        }.toMap()

        @Suppress("unused")
        internal fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
