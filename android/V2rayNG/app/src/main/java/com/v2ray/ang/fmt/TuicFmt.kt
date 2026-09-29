package com.v2ray.ang.fmt

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.idnHost
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import java.net.URI

/** `tuic://uuid:password@host:port?congestion_control=bbr&udp_relay_mode=native&alpn=h3&sni=...#name` */
object TuicFmt : FmtBase() {

    val CONGESTION_CONTROLS = listOf("bbr", "cubic", "new_reno")
    val UDP_RELAY_MODES = listOf("native", "quic")

    fun parse(str: String): ProfileItem? {
        val config = ProfileItem.create(EConfigType.TUIC)
        val uri = URI(Utils.fixIllegalUrl(str))

        config.remarks = Utils.decodeURIComponent(uri.rawFragment.orEmpty()).ifEmpty { "none" }
        config.server = uri.idnHost
        config.serverPort = (if (uri.port > 0) uri.port else 443).toString()

        // uuid:password, the password may itself contain ':'
        val userInfo = uri.userInfo.orEmpty()
        val colon = userInfo.indexOf(':')
        config.username = if (colon >= 0) userInfo.substring(0, colon) else userInfo
        config.password = if (colon >= 0) userInfo.substring(colon + 1) else ""
        if (config.username.isNullOrBlank() || config.server.isNullOrBlank()) return null

        val query = parseQuery(uri.rawQuery)
        config.security = AppConfig.TLS
        config.sni = query["sni"]
        config.alpn = query["alpn"].nullIfBlank() ?: "h3"
        config.insecure = listOf("allow_insecure", "allowinsecure", "insecure", "skip-cert-verify")
            .any { query[it] == "1" || query[it].equals("true", ignoreCase = true) }
        config.congestionControl = (query["congestion_control"] ?: query["congestion-control"] ?: query["congestion_controller"])
            ?.lowercase()?.takeIf { it in CONGESTION_CONTROLS } ?: CONGESTION_CONTROLS.first()
        config.udpRelayMode = (query["udp_relay_mode"] ?: query["udp-relay-mode"])
            ?.lowercase()?.takeIf { it in UDP_RELAY_MODES } ?: UDP_RELAY_MODES.first()
        return config
    }

    fun toUri(config: ProfileItem): String {
        val query = linkedMapOf<String, String>()
        config.sni.nullIfBlank()?.let { query["sni"] = it }
        config.alpn.nullIfBlank()?.let { query["alpn"] = it }
        if (config.insecure == true) query["allow_insecure"] = "1"
        config.congestionControl.nullIfBlank()?.let { query["congestion_control"] = it }
        config.udpRelayMode.nullIfBlank()?.let { query["udp_relay_mode"] = it }

        val queryString = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            "${it.key}=${Utils.encodeURIComponent(it.value)}"
        }
        val host = Utils.getIpv6Address(HttpUtil.toIdnDomain(config.server.orEmpty()))
        return "${config.username.orEmpty()}:${Utils.encodeURIComponent(config.password.orEmpty())}" +
            "@$host:${config.serverPort}$queryString#${Utils.encodeURIComponent(config.remarks)}"
    }

    /** Tolerant query parser: keeps '=' inside values and skips parameters without a value. */
    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val result = HashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            result[pair.substring(0, idx).lowercase()] = Utils.decodeURIComponent(pair.substring(idx + 1))
        }
        return result
    }
}
