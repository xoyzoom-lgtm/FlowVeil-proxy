package com.v2ray.ang.ui.main

import com.google.gson.JsonObject
import com.google.gson.JsonParser

private val NON_PROXY_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

/**
 * Summarises a full Xray JSON config as "PROTOCOL / TRANSPORT / SECURITY | JSON" using its
 * proxy outbound, or returns null when the JSON has no recognisable proxy outbound.
 */
internal fun describeCustomConfig(json: String?): String? {
    if (json.isNullOrBlank()) return null
    val root = runCatching { JsonParser.parseString(json) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val outbounds = root.getAsJsonArray("outbounds")
        ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        ?: return null
    val proxy = outbounds.firstOrNull { it.stringOrNull("tag") == "proxy" && it.isProxy() }
        ?: outbounds.firstOrNull { it.isProxy() }
        ?: return null

    val parts = mutableListOf(proxy.stringOrNull("protocol")!!.uppercase())
    val stream = proxy.get("streamSettings")?.takeIf { it.isJsonObject }?.asJsonObject
    stream?.stringOrNull("network")?.let { network ->
        parts += when (network.lowercase()) {
            "raw" -> "TCP"
            else -> network.uppercase()
        }
    }
    stream?.stringOrNull("security")
        ?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
        ?.let { parts += it.uppercase() }
    return parts.joinToString(" / ") + " | JSON"
}

private fun JsonObject.isProxy(): Boolean {
    val protocol = stringOrNull("protocol") ?: return false
    return protocol.lowercase() !in NON_PROXY_PROTOCOLS
}

private fun JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
