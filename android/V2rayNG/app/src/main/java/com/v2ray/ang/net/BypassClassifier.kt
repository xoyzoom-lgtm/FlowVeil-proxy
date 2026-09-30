package com.v2ray.ang.net

/**
 * "Does this look like a server built for mobile networks under a whitelist?" A pure scoring model
 * (no Android, no network): the name, the transport and masking, the history of the server.
 *
 * It only orders and filters candidates. The one thing that proves a server works on the street is
 * the real test on the mobile network, so a high score never skips it and a low one is not a ban
 * (the user may still pick any server by hand).
 */
enum class BypassLevel { STRONG, LIKELY, WEAK, UNLIKELY }

/** What the classifier knows about a server; the caller fills it from a profile or a custom JSON. */
data class ServerFacts(
    val name: String,
    /** vless, vmess, trojan, shadowsocks, hysteria2, tuic, wireguard, socks, http, custom (unparsed), other. */
    val protocol: String,
    /** tcp, ws, grpc, xhttp, httpupgrade, h2, kcp, quic, or "" when unknown. */
    val transport: String = "",
    /** none, tls, reality. */
    val security: String = "none",
    val sni: String = "",
    val host: String = "",
    val flow: String = "",
    /** The name says the server is in Russia (whitelisted infrastructure lives there). */
    val russian: Boolean = false,
    val position: Int = 0,
    val listSize: Int = 1,
)

/** Facts about the past of one server on a mobile network (see [BypassHistoryLogic]). */
data class HistoryFacts(val lastOkAgeMs: Long?, val badNow: Boolean)

/** Read from the subscription announcement: the bypass servers are listed last. */
data class ListHint(val bypassAtTail: Boolean)

data class Reason(val code: String, val points: Int)

data class Rating(val score: Int, val level: BypassLevel, val reasons: List<Reason>) {
    fun explain(): String = reasons.joinToString(", ") { "${it.code}${if (it.points >= 0) "+" else ""}${it.points}" }
}

/** All the numbers in one place. Bump [VERSION] when they change; there is no remote update. */
object BypassData {
    const val VERSION = 1

    // ---- level thresholds ----
    const val STRONG_AT = 60
    const val LIKELY_AT = 30
    const val WEAK_AT = 5

    // ---- name markers: a provider label beats any guess, so it alone makes a server STRONG ----
    const val NAME_LABEL = 60
    const val NAME_MOBILE = 25
    const val NAME_BRIDGE = 20
    const val NAME_ONLY_WIFI = -40
    const val NAME_GAMING = -15

    // ---- transport / security (hypotheses from public reports, see the project notes) ----
    /** VLESS + Reality over TCP with XTLS Vision: the most reported scheme that passes. */
    const val REALITY_VISION = 30
    const val REALITY_OTHER = 26
    /** XHTTP: looks like ordinary web traffic, often behind a CDN. */
    const val XHTTP = 22
    /** WebSocket / gRPC / HTTPUpgrade / HTTP2 under TLS: usable behind a CDN with an allowed host. */
    const val CDN_TRANSPORT = 18
    const val TLS_TCP = 8
    const val SHADOWSOCKS = 5
    /** UDP protocols are often cut or throttled on mobile networks, but Hysteria2 is reported to pass at times: a light penalty. */
    const val UDP_BASED = -5
    /** Proxy protocol with no TLS at all: easy to recognise. */
    const val NO_TLS = -10
    /** A provider's own JSON: usually built with care, the details are not parsed. */
    const val CUSTOM_JSON = 8

    // ---- masking as an allowed domain ----
    const val MASK_SNI = 35
    const val MASK_HOST = 20
    const val RUSSIAN_SERVER = 10
    const val LIST_TAIL = 12

    // ---- history ----
    const val HISTORY_OK_RECENT = 60
    const val HISTORY_OK_OLD = 25
    const val HISTORY_BAD = -50

    /** A "worked" result is recent for this long. */
    const val RECENT_MS = 24 * 60 * 60_000L

    /** Provider labels: "white lists", "bypass". Matched on the normalised name. */
    val LABEL_MARKERS = listOf(
        "белые списки", "белый список", "белых списков", "белым спискам", "белые", "whitelist", "white list", "white lists",
        "belye spiski", "belyj spisok", "belie spiski", "обход", "obhod", "bypass",
    )
    val LABEL_WORDS = listOf("wl", "вл")

    /** The network the server is meant for: mobile, an operator. */
    val MOBILE_MARKERS = listOf(
        "мобильн", "мобайл", "mobile", "сотов", "cellular", "operator", "оператор",
        "мтс", "билайн", "мегафон", "теле2", "tele2", "yota", "йота", "мотив", "ростелеком мобайл", "beeline", "megafon",
    )
    val MOBILE_WORDS = listOf("lte", "4g", "5g", "mts", "мтс")

    /** A relay or a bridge with a domestic entry. */
    val BRIDGE_MARKERS = listOf("мост", "bridge", "relay", "ретранслятор", "ретранс", "через россию", "российский вход", "вход рф", "вход ru")
    val BRIDGE_RAW = listOf("ru→", "→ru", "ru->", "->ru", "ru⇒", "⇒ru", "ru➜", "➜ru", "ru>>", ">>ru")

    val ONLY_WIFI_MARKERS = listOf(
        "только wi fi", "только wifi", "только вайфай", "только вай фай", "только дом", "не для мобильн", "wifi only", "wi fi only", "home only",
    )
    val GAMING_MARKERS = listOf("игров", "gaming")
    val GAMING_WORDS = listOf("game", "games", "gamer")
}

object BypassClassifier {

    /** Lower case, "ё" as "е", no emoji or punctuation, single spaces. Cyrillic and Latin stay. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var lastSpace = true
        for (ch in text.lowercase()) {
            val c = if (ch == 'ё') 'е' else ch
            if (c.isLetterOrDigit() && c.code < 0x2000) {
                sb.append(c)
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    private fun hasMarker(padded: String, markers: List<String>): Boolean = markers.any { padded.contains(it) }
    private fun hasWord(padded: String, words: List<String>): Boolean = words.any { padded.contains(" $it ") }

    /** True when [value] is one of [masks] or a subdomain of one. */
    fun matchesDomain(value: String, masks: Collection<String>): Boolean {
        val v = value.trim().lowercase().trimEnd('.')
        if (v.isEmpty()) return false
        return masks.any { m ->
            val d = m.trim().lowercase().trimStart('.').trimEnd('.')
            d.isNotEmpty() && (v == d || v.endsWith(".$d"))
        }
    }

    /** Name signals: the strongest positive one plus every negative one. */
    private fun nameReasons(name: String, extraLabels: List<String>): List<Reason> {
        val padded = " ${normalize(name)} "
        val raw = name.lowercase()
        val out = ArrayList<Reason>()
        val extra = extraLabels.map { normalize(it) }.filter { it.isNotEmpty() }
        val positive = when {
            hasMarker(padded, BypassData.LABEL_MARKERS) || hasWord(padded, BypassData.LABEL_WORDS) ||
                extra.any { padded.contains(it) } -> Reason("name_label", BypassData.NAME_LABEL)
            hasMarker(padded, BypassData.MOBILE_MARKERS) || hasWord(padded, BypassData.MOBILE_WORDS) ->
                Reason("name_mobile", BypassData.NAME_MOBILE)
            hasMarker(padded, BypassData.BRIDGE_MARKERS) || BypassData.BRIDGE_RAW.any { raw.contains(it) } ->
                Reason("name_bridge", BypassData.NAME_BRIDGE)
            else -> null
        }
        if (positive != null) out += positive
        if (hasMarker(padded, BypassData.ONLY_WIFI_MARKERS)) out += Reason("name_only_wifi", BypassData.NAME_ONLY_WIFI)
        if (hasMarker(padded, BypassData.GAMING_MARKERS) || hasWord(padded, BypassData.GAMING_WORDS)) {
            out += Reason("name_gaming", BypassData.NAME_GAMING)
        }
        return out
    }

    private fun transportReason(f: ServerFacts): Reason? {
        val protocol = f.protocol.lowercase()
        val transport = f.transport.lowercase()
        val security = f.security.lowercase()
        return when {
            protocol == "custom" -> Reason("custom_json", BypassData.CUSTOM_JSON)
            protocol in UDP_PROTOCOLS || transport in UDP_TRANSPORTS -> Reason("udp_based", BypassData.UDP_BASED)
            security == "reality" && f.flow.contains("vision", ignoreCase = true) -> Reason("reality_vision", BypassData.REALITY_VISION)
            security == "reality" -> Reason("reality", BypassData.REALITY_OTHER)
            transport == "xhttp" && security != "none" -> Reason("xhttp", BypassData.XHTTP)
            transport in CDN_TRANSPORTS && security == "tls" -> Reason("cdn_transport", BypassData.CDN_TRANSPORT)
            security == "tls" -> Reason("tls_tcp", BypassData.TLS_TCP)
            protocol == "shadowsocks" -> Reason("shadowsocks", BypassData.SHADOWSOCKS)
            protocol in PROXY_PROTOCOLS && security == "none" -> Reason("no_tls", BypassData.NO_TLS)
            else -> null
        }
    }

    private val UDP_PROTOCOLS = setOf("hysteria2", "hysteria", "tuic", "wireguard")
    private val UDP_TRANSPORTS = setOf("kcp", "mkcp", "quic")
    private val CDN_TRANSPORTS = setOf("ws", "websocket", "grpc", "httpupgrade", "h2", "http")
    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan")

    /**
     * Scores one server. [masks]: domains that are on the allowed list (the SNI/Host of a mask is a
     * strong signal). [extraLabels]: the user's own name markers.
     */
    fun rate(
        facts: ServerFacts,
        masks: Collection<String>,
        extraLabels: List<String> = emptyList(),
        hint: ListHint = ListHint(false),
        history: HistoryFacts? = null,
    ): Rating {
        val reasons = ArrayList<Reason>()
        reasons += nameReasons(facts.name, extraLabels)
        transportReason(facts)?.let { reasons += it }
        val secure = facts.security.equals("tls", true) || facts.security.equals("reality", true)
        if (secure && matchesDomain(facts.sni, masks)) {
            reasons += Reason("mask_sni", BypassData.MASK_SNI)
        } else if (matchesDomain(facts.host, masks)) {
            reasons += Reason("mask_host", BypassData.MASK_HOST)
        }
        if (facts.russian) reasons += Reason("ru_server", BypassData.RUSSIAN_SERVER)
        if (hint.bypassAtTail && facts.listSize >= 4 && facts.position >= (facts.listSize * 7) / 10) {
            reasons += Reason("list_tail", BypassData.LIST_TAIL)
        }
        if (history != null) {
            val age = history.lastOkAgeMs
            when {
                history.badNow -> reasons += Reason("history_bad", BypassData.HISTORY_BAD)
                age != null && age < BypassData.RECENT_MS -> reasons += Reason("history_ok", BypassData.HISTORY_OK_RECENT)
                age != null -> reasons += Reason("history_old_ok", BypassData.HISTORY_OK_OLD)
            }
        }
        val score = reasons.sumOf { it.points }
        return Rating(score, levelOf(score), reasons)
    }

    fun levelOf(score: Int): BypassLevel = when {
        score >= BypassData.STRONG_AT -> BypassLevel.STRONG
        score >= BypassData.LIKELY_AT -> BypassLevel.LIKELY
        score >= BypassData.WEAK_AT -> BypassLevel.WEAK
        else -> BypassLevel.UNLIKELY
    }

    /** True when the announcement says the bypass servers are at the end / in a separate group. */
    fun announceHint(announce: String?): ListHint {
        if (announce.isNullOrBlank()) return ListHint(false)
        val n = " ${normalize(announce)} "
        val label = hasMarker(n, BypassData.LABEL_MARKERS)
        val tail = listOf("в конце", "внизу", "последн", "в самом низу", "at the end", "at the bottom").any { n.contains(it) }
        return ListHint(label && tail)
    }
}
