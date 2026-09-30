package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.net.BypassClassifier
import com.v2ray.ang.net.BypassHistoryLogic
import com.v2ray.ang.net.BypassLevel
import com.v2ray.ang.net.ListHint
import com.v2ray.ang.net.Rating
import com.v2ray.ang.net.ServerFacts
import com.v2ray.ang.util.JsonUtil

/**
 * Turns saved profiles into [ServerFacts] for the pure [BypassClassifier] and adds the parts that
 * live in this app: the editable list of "mask" domains, the user's own name markers, the
 * subscription announcement and the history on the mobile network.
 */
object BypassRating {
    /** Developer mode: allowed domains (one per line). Empty = the built-in list of Russian services. */
    const val PREF_MASKS = "pref_bypass_mask_domains"

    /** Developer mode: extra words in a server name that mean "made for the mobile network" (one per line). */
    const val PREF_LABELS = "pref_bypass_extra_labels"

    data class Rated(
        val guid: String,
        val name: String,
        val fingerprint: String,
        val rating: Rating,
        /** Position in the subscription (or in the user's list for a manual choice). */
        val order: Int,
    )

    fun masks(): List<String> = lines(PREF_MASKS).ifEmpty { DirectSites.DEFAULTS }

    fun extraLabels(): List<String> = lines(PREF_LABELS)

    private fun lines(key: String): List<String> =
        MmkvManager.decodeSettingsString(key).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Rates one server; null when the profile is gone or is a group / chain (never a bypass candidate). */
    fun rate(guid: String, bucket: String?, now: Long = System.currentTimeMillis(), order: Int? = null): Rated? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        if (profile.configType == EConfigType.POLICYGROUP || profile.configType == EConfigType.PROXYCHAIN) return null
        val list = MmkvManager.decodeServerList(profile.subscriptionId)
        val position = list.indexOf(guid).coerceAtLeast(0)
        val (facts, fingerprint) = factsOf(guid, profile, position, list.size.coerceAtLeast(1))
        val hint = subscriptionHint(profile.subscriptionId)
        val history = bucket?.let { BypassHistory.facts(fingerprint, it, now) }
        val rating = BypassClassifier.rate(facts, masks(), extraLabels(), hint, history)
        return Rated(guid, profile.remarks, fingerprint, rating, order ?: position)
    }

    fun fingerprintOf(guid: String): String? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        return factsOf(guid, profile, 0, 1).second
    }

    private fun subscriptionHint(subId: String): ListHint {
        if (subId.isBlank()) return ListHint(false)
        return BypassClassifier.announceHint(MmkvManager.decodeSubscription(subId)?.announce)
    }

    private fun factsOf(guid: String, p: ProfileItem, position: Int, size: Int): Pair<ServerFacts, String> {
        val russian = ServerCountry.isRussian(p.remarks)
        if (p.configType == EConfigType.CUSTOM) {
            val raw = MmkvManager.decodeServerRaw(guid).orEmpty()
            val facts = fromCustomJson(p.remarks, raw, russian, position, size)
            return facts to BypassHistoryLogic.fingerprint(listOf("custom", raw))
        }
        val protocol = when (p.configType) {
            EConfigType.SHADOWSOCKS -> "shadowsocks"
            EConfigType.HYSTERIA, EConfigType.HYSTERIA2 -> "hysteria2"
            else -> p.configType.name.lowercase()
        }
        val facts = ServerFacts(
            name = p.remarks, protocol = protocol,
            transport = p.network.orEmpty().lowercase(),
            security = p.security?.lowercase()?.takeIf { it.isNotEmpty() } ?: if (p.configType == EConfigType.HYSTERIA2 || p.configType == EConfigType.TUIC) "tls" else "none",
            sni = p.sni.orEmpty(), host = p.host.orEmpty(), flow = p.flow.orEmpty(),
            russian = russian, position = position, listSize = size,
        )
        val fp = BypassHistoryLogic.fingerprint(
            listOf(p.configType.name, p.server.orEmpty(), p.serverPort.orEmpty(), p.network.orEmpty(), p.security.orEmpty(), p.sni.orEmpty(), p.host.orEmpty(), p.path.orEmpty(), p.flow.orEmpty())
        )
        return facts to fp
    }

    /** The first real proxy outbound of a provider's JSON (Xray or sing-box style); "custom" when it cannot be read. */
    internal fun fromCustomJson(name: String, raw: String, russian: Boolean, position: Int, size: Int): ServerFacts {
        val unknown = ServerFacts(name, "custom", russian = russian, position = position, listSize = size)
        return runCatching {
            val root = JsonUtil.parseString(raw) ?: return unknown
            val outbounds = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: return unknown
            val proxy = pickProxyOutbound(outbounds) ?: return unknown
            if (proxy.has("type")) singBoxFacts(name, proxy, russian, position, size) else xrayFacts(name, proxy, russian, position, size)
        }.getOrDefault(unknown)
    }

    private val NOT_PROXY = setOf("freedom", "blackhole", "dns", "loopback", "direct", "block", "selector", "urltest")

    private fun pickProxyOutbound(outbounds: JsonArray): JsonObject? {
        val objects = outbounds.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
        fun protocolOf(o: JsonObject) = (o.get("protocol") ?: o.get("type"))?.asString?.lowercase().orEmpty()
        val proxies = objects.filter { protocolOf(it).isNotEmpty() && protocolOf(it) !in NOT_PROXY }
        return proxies.firstOrNull { it.get("tag")?.asString == "proxy" } ?: proxies.firstOrNull()
    }

    private fun str(o: JsonObject?, name: String): String = o?.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun obj(o: JsonObject?, name: String): JsonObject? = o?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun first(e: JsonElement?): JsonObject? = e?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject

    private fun xrayFacts(name: String, o: JsonObject, russian: Boolean, position: Int, size: Int): ServerFacts {
        val protocol = str(o, "protocol").lowercase().let { if (it == "hysteria") "hysteria2" else it }
        val stream = obj(o, "streamSettings")
        val network = str(stream, "network").lowercase().ifEmpty { "tcp" }
        val security = str(stream, "security").lowercase().ifEmpty { "none" }
        val sni = str(obj(stream, "realitySettings"), "serverName").ifEmpty { str(obj(stream, "tlsSettings"), "serverName") }
        val ws = obj(stream, "wsSettings")
        val host = str(ws, "host").ifEmpty { str(obj(ws, "headers"), "Host") }
            .ifEmpty { str(obj(stream, "xhttpSettings"), "host") }.ifEmpty { str(obj(stream, "httpupgradeSettings"), "host") }
        val vnextUser = first(first(obj(o, "settings")?.get("vnext"))?.get("users"))
        return ServerFacts(
            name = name, protocol = protocol, transport = network, security = security, sni = sni, host = host,
            flow = str(vnextUser, "flow").ifEmpty { str(obj(o, "settings"), "flow") }, russian = russian, position = position, listSize = size,
        )
    }

    private fun singBoxFacts(name: String, o: JsonObject, russian: Boolean, position: Int, size: Int): ServerFacts {
        val protocol = str(o, "type").lowercase().let { if (it == "hysteria") "hysteria2" else it }
        val tls = obj(o, "tls")
        val reality = obj(tls, "reality")?.get("enabled")?.asBoolean == true
        val security = when {
            reality -> "reality"
            tls?.get("enabled")?.asBoolean == true -> "tls"
            else -> "none"
        }
        val transport = obj(o, "transport")
        return ServerFacts(
            name = name, protocol = protocol, transport = str(transport, "type").lowercase().ifEmpty { "tcp" },
            security = security, sni = str(tls, "server_name"), host = str(obj(transport, "headers"), "Host").ifEmpty { str(transport, "host") },
            flow = str(o, "flow"), russian = russian, position = position, listSize = size,
        )
    }

    /** Rating labels for the UI: what kind of server this looks like. */
    fun looksLikeBypass(r: Rating): Boolean = r.level == BypassLevel.STRONG || r.level == BypassLevel.LIKELY
}
