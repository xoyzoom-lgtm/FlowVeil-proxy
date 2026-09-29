package com.v2ray.ang.handler

import android.util.Base64
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.AppConfig
import org.json.JSONArray
import org.json.JSONObject
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URLEncoder

/**
 * Makes subscriptions from other providers readable: tolerant base64, and Clash (YAML) /
 * sing-box (JSON) profiles converted into ordinary share links the existing parsers understand.
 */
object SubscriptionFormats {

    /** Base64 with line breaks, spaces, URL-safe alphabet or missing padding; null if not base64. */
    fun decodeBase64Loose(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || !compact.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "+/=-_" }) return null
        val normalized = compact.replace('-', '+').replace('_', '/').trimEnd('=')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            val decoded = Base64.decode(padded, Base64.DEFAULT).toString(Charsets.UTF_8)
            // Reject binary garbage: a real subscription decodes to readable text.
            if (decoded.count { it == '�' } > 2) null else decoded
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Share links from a Clash/Mihomo YAML or sing-box JSON profile; empty when neither. */
    fun toShareLinks(text: String): List<String> {
        val trimmed = text.trim()
        return try {
            when {
                trimmed.startsWith("{") && trimmed.contains("\"outbounds\"") -> fromSingBox(JSONObject(trimmed))
                trimmed.contains("proxies:") -> fromClash(trimmed)
                else -> emptyList()
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Subscription format conversion failed", e)
            emptyList()
        }
    }

    // ---------- Clash / Mihomo ----------

    @Suppress("UNCHECKED_CAST")
    private fun fromClash(yaml: String): List<String> {
        val root = Load(LoadSettings.builder().build()).loadFromString(yaml) as? Map<String, Any?> ?: return emptyList()
        val proxies = root["proxies"] as? List<Any?> ?: return emptyList()
        return proxies.mapNotNull { p ->
            val m = p as? Map<String, Any?> ?: return@mapNotNull null
            runCatching { clashProxy(m) }.getOrNull()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun clashProxy(m: Map<String, Any?>): String? {
        fun s(key: String) = m[key]?.toString()?.takeIf { it.isNotBlank() }
        fun map(key: String) = m[key] as? Map<String, Any?>
        val name = s("name") ?: "server"
        val server = s("server") ?: return null
        val port = s("port") ?: return null
        val network = s("network") ?: "tcp"
        val ws = map("ws-opts")
        val grpc = map("grpc-opts")
        val h2 = map("h2-opts")
        val reality = map("reality-opts")
        val transport = Transport(
            type = network,
            path = ws?.get("path")?.toString() ?: (h2?.get("path")?.toString()),
            host = ((ws?.get("headers") as? Map<String, Any?>)?.get("Host")
                ?: (h2?.get("host") as? List<*>)?.firstOrNull())?.toString(),
            serviceName = grpc?.get("grpc-service-name")?.toString(),
        )
        val tls = m["tls"] == true || reality != null
        val security = Security(
            type = when {
                reality != null -> "reality"
                tls -> "tls"
                else -> "none"
            },
            sni = s("servername") ?: s("sni"),
            fingerprint = s("client-fingerprint"),
            publicKey = reality?.get("public-key")?.toString(),
            shortId = reality?.get("short-id")?.toString(),
            insecure = m["skip-cert-verify"] == true,
        )
        return when (s("type")?.lowercase()) {
            "vless" -> vless(s("uuid") ?: return null, server, port, name, s("flow"), transport, security)
            "vmess" -> vmess(s("uuid") ?: return null, server, port, name, s("alterId") ?: "0", s("cipher") ?: "auto", transport, security)
            "trojan" -> trojan(s("password") ?: return null, server, port, name, transport, security.copy(type = if (reality != null) "reality" else "tls"))
            "ss", "shadowsocks" -> if (s("plugin") != null) null else shadowsocks(s("cipher") ?: return null, s("password") ?: return null, server, port, name)
            "hysteria2", "hy2" -> hysteria2(s("password") ?: s("auth") ?: return null, server, port, name, security.sni, security.insecure, s("obfs"), s("obfs-password"))
            else -> null
        }
    }

    // ---------- sing-box ----------

    private fun fromSingBox(root: JSONObject): List<String> {
        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        return (0 until outbounds.length()).mapNotNull { i ->
            val o = outbounds.optJSONObject(i) ?: return@mapNotNull null
            runCatching { singBoxOutbound(o) }.getOrNull()
        }
    }

    private fun singBoxOutbound(o: JSONObject): String? {
        fun s(obj: JSONObject?, key: String) = obj?.optString(key)?.takeIf { it.isNotBlank() }
        val name = s(o, "tag") ?: "server"
        val server = s(o, "server") ?: return null
        val port = o.optInt("server_port", 0).takeIf { it > 0 }?.toString() ?: return null
        val tlsObj = o.optJSONObject("tls")
        val realityObj = tlsObj?.optJSONObject("reality")
        val isReality = realityObj?.optBoolean("enabled") == true
        val security = Security(
            type = when {
                isReality -> "reality"
                tlsObj?.optBoolean("enabled") == true -> "tls"
                else -> "none"
            },
            sni = s(tlsObj, "server_name"),
            fingerprint = s(tlsObj?.optJSONObject("utls"), "fingerprint"),
            publicKey = s(realityObj, "public_key"),
            shortId = s(realityObj, "short_id"),
            insecure = tlsObj?.optBoolean("insecure") == true,
        )
        val t = o.optJSONObject("transport")
        val transport = Transport(
            type = when (s(t, "type")) {
                null -> "tcp"
                "http" -> "h2"
                else -> s(t, "type")!!
            },
            path = s(t, "path"),
            host = s(t?.optJSONObject("headers"), "Host") ?: t?.optJSONArray("host")?.optString(0),
            serviceName = s(t, "service_name"),
        )
        return when (s(o, "type")) {
            "vless" -> vless(s(o, "uuid") ?: return null, server, port, name, s(o, "flow"), transport, security)
            "vmess" -> vmess(s(o, "uuid") ?: return null, server, port, name, o.optInt("alter_id", 0).toString(), s(o, "security") ?: "auto", transport, security)
            "trojan" -> trojan(s(o, "password") ?: return null, server, port, name, transport, security)
            "shadowsocks" -> shadowsocks(s(o, "method") ?: return null, s(o, "password") ?: return null, server, port, name)
            "hysteria2" -> {
                val obfs = o.optJSONObject("obfs")
                hysteria2(s(o, "password") ?: return null, server, port, name, security.sni, security.insecure, s(obfs, "type"), s(obfs, "password"))
            }
            else -> null
        }
    }

    // ---------- share-link builders ----------

    private data class Transport(val type: String, val path: String?, val host: String?, val serviceName: String?)

    private data class Security(
        val type: String,
        val sni: String?,
        val fingerprint: String?,
        val publicKey: String?,
        val shortId: String?,
        val insecure: Boolean,
    )

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    private fun query(vararg pairs: Pair<String, String?>) =
        pairs.filter { !it.second.isNullOrBlank() }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }

    private fun hostPort(server: String, port: String) = if (server.contains(':')) "[$server]:$port" else "$server:$port"

    private fun streamParams(t: Transport, sec: Security) = arrayOf(
        "type" to (if (t.type == "http") "tcp" else t.type),
        "path" to t.path,
        "host" to t.host,
        "serviceName" to t.serviceName,
        "security" to sec.type,
        "sni" to sec.sni,
        "fp" to sec.fingerprint,
        "pbk" to sec.publicKey,
        "sid" to sec.shortId,
        "allowInsecure" to (if (sec.insecure) "1" else null),
    )

    private fun vless(uuid: String, server: String, port: String, name: String, flow: String?, t: Transport, sec: Security) =
        "vless://$uuid@${hostPort(server, port)}?" + query("encryption" to "none", "flow" to flow, *streamParams(t, sec)) + "#" + enc(name)

    private fun trojan(password: String, server: String, port: String, name: String, t: Transport, sec: Security) =
        "trojan://${enc(password)}@${hostPort(server, port)}?" + query(*streamParams(t, sec)) + "#" + enc(name)

    private fun shadowsocks(method: String, password: String, server: String, port: String, name: String): String {
        val userInfo = Base64.encodeToString("$method:$password".toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        return "ss://$userInfo@${hostPort(server, port)}#${enc(name)}"
    }

    private fun hysteria2(auth: String, server: String, port: String, name: String, sni: String?, insecure: Boolean, obfs: String?, obfsPassword: String?) =
        "hysteria2://${enc(auth)}@${hostPort(server, port)}?" +
            query("sni" to sni, "insecure" to (if (insecure) "1" else "0"), "obfs" to obfs, "obfs-password" to obfsPassword) +
            "#" + enc(name)

    private fun vmess(uuid: String, server: String, port: String, name: String, aid: String, cipher: String, t: Transport, sec: Security): String {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", name)
            put("add", server)
            put("port", port)
            put("id", uuid)
            put("aid", aid)
            put("scy", cipher)
            put("net", if (t.type == "http") "tcp" else t.type)
            put("type", "none")
            put("host", t.host ?: "")
            put("path", t.path ?: t.serviceName ?: "")
            put("tls", if (sec.type == "none") "" else "tls")
            put("sni", sec.sni ?: "")
            put("fp", sec.fingerprint ?: "")
        }
        return "vmess://" + Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
    }

    @Suppress("unused")
    private fun JSONArray.strings() = (0 until length()).map { optString(it) }
}
