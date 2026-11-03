package com.v2ray.ang.handler

import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.net.PlaceholderNode

/** Tells real servers from the info lines of a subscription (see [PlaceholderNode]). */
object ServerUsable {
    /** True when the saved profile [guid] is an info line and not a server. Groups, chains and unreadable JSON count as servers. */
    fun isPlaceholder(guid: String): Boolean {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return false
        return when (profile.configType) {
            EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN -> false
            EConfigType.CUSTOM -> {
                val endpoint = BypassRating.proxyAddress(MmkvManager.decodeServerRaw(guid).orEmpty()) ?: return false
                PlaceholderNode.isPlaceholder(endpoint.host, endpoint.port)
            }
            else -> PlaceholderNode.isPlaceholder(profile.server, profile.serverPort)
        }
    }

    /**
     * The address a plain TCP connect can be tried against, or null when TCP says nothing about the server (UDP-based
     * protocols, groups, chains, JSON that cannot be read, info lines). Used as a cheap pre-check before a full test.
     */
    fun tcpTarget(guid: String): Pair<String, Int>? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        val (host, port, udp) = when (profile.configType) {
            EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN -> return null
            EConfigType.CUSTOM -> {
                val e = BypassRating.proxyAddress(MmkvManager.decodeServerRaw(guid).orEmpty()) ?: return null
                Triple(e.host, e.port, e.udp)
            }
            EConfigType.HYSTERIA, EConfigType.HYSTERIA2, EConfigType.TUIC, EConfigType.WIREGUARD ->
                Triple(profile.server.orEmpty(), profile.serverPort.orEmpty(), true)
            else -> Triple(
                profile.server.orEmpty(), profile.serverPort.orEmpty(),
                profile.alpn?.split(',')?.all { it.trim().startsWith("h3") } == true,
            )
        }
        if (udp || PlaceholderNode.isPlaceholder(host, port)) return null
        return host.trim() to port.trim().toInt()
    }
}
