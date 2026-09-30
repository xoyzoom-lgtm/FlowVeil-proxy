package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import java.net.Proxy

/**
 * Where the running core listens for local proxy clients, so a check can go through the server.
 *
 * Ordinary profiles get FlowVeil's own inbound (the port from the settings). A custom JSON profile
 * runs exactly as written: its inbounds are the provider's, so the port may differ, may be a SOCKS
 * one, or there may be none at all (only the TUN). The check must use what really exists.
 */
object LocalProxy {
    data class Route(val type: Proxy.Type, val port: Int, val user: String?, val pass: String?) {
        val label: String get() = "${type.name.lowercase()}:$port"
    }

    /** The route for the server [guid]; null when the config has no local HTTP/SOCKS inbound to use. */
    fun resolve(guid: String): Route? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        if (profile.configType != EConfigType.CUSTOM) {
            return Route(
                Proxy.Type.HTTP, SettingsManager.getHttpPort(),
                SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword()
            )
        }
        val raw = MmkvManager.decodeServerRaw(guid) ?: return null
        val root = JsonUtil.parseString(raw)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val inbounds = root.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return pick(inbounds)
    }

    /** HTTP first (authentication works), then SOCKS without a password; both only on the phone itself. */
    internal fun pick(inbounds: JsonArray): Route? {
        var socks: Route? = null
        for (element in inbounds) {
            val inbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val protocol = inbound.get("protocol")?.asString?.lowercase() ?: continue
            if (protocol != "http" && protocol != "socks") continue
            if (!isLocal(inbound.get("listen")?.asString)) continue
            val port = portOf(inbound.get("port")) ?: continue
            val account = inbound.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("accounts")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject
            val user = account?.get("user")?.asString
            val pass = account?.get("pass")?.asString
            if (protocol == "http") return Route(Proxy.Type.HTTP, port, user, pass)
            if (socks == null && user == null) socks = Route(Proxy.Type.SOCKS, port, null, null)
        }
        return socks
    }

    private fun isLocal(listen: String?): Boolean = listen.isNullOrBlank() || listen in setOf("127.0.0.1", "localhost", "0.0.0.0", "::", "::1")

    private fun portOf(element: com.google.gson.JsonElement?): Int? =
        runCatching { element?.asString?.trim()?.toIntOrNull() }.getOrNull()?.takeIf { it in 1..65535 }
}
