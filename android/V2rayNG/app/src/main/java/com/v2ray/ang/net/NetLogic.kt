package com.v2ray.ang.net

import java.net.URLDecoder
import java.net.URLEncoder

/*
 * Pure decisions of the network-aware features (no Android classes here, so they run in plain JVM
 * unit tests): choosing the physical network, reading an IP out of a reply, judging a check of a
 * server, and deciding what to do when the phone is back on a normal network.
 */

// ---------------------------------------------------------------------------------------------
// Physical network
// ---------------------------------------------------------------------------------------------

enum class NetType(val id: String) {
    NONE("none"),
    CELLULAR("cellular"),
    WIFI("wifi"),
    ETHERNET("ethernet");

    /** Wi-Fi and Ethernet are "normal" networks; the mobile network is where restrictions live. */
    val isLan: Boolean get() = this == WIFI || this == ETHERNET

    companion object {
        fun fromId(id: String?): NetType = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** One real (non-tunnel) network as reported by the system. [key] identifies the handle. */
data class NetCandidate(
    val key: Long,
    val type: NetType,
    val validated: Boolean,
    val hasIpv6: Boolean = false,
    /** The system flagged this network as behind a login page (hotel / cafe Wi-Fi). */
    val captive: Boolean = false,
)

object NetworkPicker {

    /**
     * Type of a network from its capabilities, or null when it is not a real internet network:
     * a tunnel (VPN), no internet, or a transport that is none of the three we care about.
     */
    fun classify(internet: Boolean, vpn: Boolean, cellular: Boolean, wifi: Boolean, ethernet: Boolean): NetType? {
        if (!internet || vpn) return null
        return when {
            ethernet -> NetType.ETHERNET
            wifi -> NetType.WIFI
            cellular -> NetType.CELLULAR
            else -> null
        }
    }

    data class Selection(val primary: NetCandidate?, val others: List<NetCandidate>)

    /**
     * The network traffic really leaves through. A validated network wins (Android itself prefers
     * it), then Ethernet > Wi-Fi > mobile, like the system does. "Validated" is only a priority,
     * never a truth: under a mobile whitelist Android often marks the working network as not
     * validated, so an unvalidated network is still chosen when it is the only one.
     */
    fun pick(candidates: List<NetCandidate>): Selection {
        val sorted = candidates
            .filter { it.type != NetType.NONE }
            .sortedWith(
                compareBy<NetCandidate>(
                    { if (it.validated) 0 else 1 },
                    { rank(it.type) },
                    { it.key },
                )
            )
        return Selection(sorted.firstOrNull(), sorted.drop(1))
    }

    private fun rank(type: NetType) = when (type) {
        NetType.ETHERNET -> 0
        NetType.WIFI -> 1
        NetType.CELLULAR -> 2
        NetType.NONE -> 3
    }
}

// ---------------------------------------------------------------------------------------------
// IP addresses in replies
// ---------------------------------------------------------------------------------------------

object IpParser {
    private val ipv4 = Regex("""(?<![\d.])((?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})(?![\d.])""")
    private val ipv6Token = Regex("""[0-9a-fA-F:.]{2,45}""")

    /** The first IP address in [text] (an IPv4 is preferred over an IPv6), or null. */
    fun parse(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val head = text.take(2048)
        ipv4.find(head)?.let { return it.groupValues[1] }
        return ipv6Token.findAll(head).map { it.value }.firstOrNull(::isIpv6)
    }

    /** True for a plain IPv6 literal (compressed forms included). No zone id, no brackets. */
    fun isIpv6(value: String): Boolean {
        if (value.count { it == ':' } < 2 || value.length > 45) return false
        if (value.contains(":::")) return false
        val doubleColon = value.indexOf("::")
        if (doubleColon >= 0 && value.indexOf("::", doubleColon + 1) >= 0) return false
        var groups = value.split(':')
        // A trailing IPv4 part ("::ffff:1.2.3.4") counts as two groups.
        var extra = 0
        val last = groups.last()
        if (last.contains('.')) {
            if (ipv4.matchEntire(last) == null) return false
            groups = groups.dropLast(1) + listOf("0")
            extra = 1
        }
        val nonEmpty = groups.filter { it.isNotEmpty() }
        if (nonEmpty.any { it.length > 4 || !it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }) return false
        val count = nonEmpty.size + extra
        return if (doubleColon >= 0) count < 8 else count == 8 && groups.none { it.isEmpty() }
    }

    fun isIp(value: String?): Boolean = !value.isNullOrBlank() && (ipv4.matchEntire(value) != null || isIpv6(value))
}

// ---------------------------------------------------------------------------------------------
// Judging a check of a server (whether it really carries traffic)
// ---------------------------------------------------------------------------------------------

/** What one request through the server came back as. */
data class ProbeStep(val kind: Kind, val code: Int = 0, val bytes: Int = 0) {
    enum class Kind { OK, BAD_STATUS, TIMEOUT, REFUSED, IO }

    companion object {
        val OK_204 = ProbeStep(Kind.OK, 204)
        fun ok(code: Int, bytes: Int = 0) = ProbeStep(Kind.OK, code, bytes)
    }
}

enum class FailReason { PROXY_DOWN, NO_GSTATIC, TAMPERED, NO_DATA, IP_SAME, UNSTABLE }

enum class Outcome { OK, FAIL, UNKNOWN }

data class Verdict(val outcome: Outcome, val reason: FailReason? = null, val ipUnknown: Boolean = false)

object BypassVerdict {
    /** A body smaller than this proves nothing about data flowing (a 204 has none at all). */
    const val MIN_CONTENT_BYTES = 256

    /**
     * Combines the steps of a check.
     *  1. [gstatic]: exactly HTTP 204 (a 200/30x means a captive page or a rewrite, not the real answer)
     *  2. [content]: a small real download completes (null = step not run)
     *  3. IP: [exitIp] through the proxy is known and differs from the [realIp] on the physical
     *     network. Unknown is not a failure (reported as [Verdict.ipUnknown]); equal is.
     *  4. [stable]: the same request repeated a few seconds later (null = not run)
     * [cancelled] (the phone changed network or the connection stopped mid-check) gives UNKNOWN,
     * never a verdict about the server.
     */
    fun decide(
        gstatic: ProbeStep,
        content: ProbeStep?,
        exitIp: String?,
        realIp: String?,
        stable: ProbeStep?,
        cancelled: Boolean = false,
    ): Verdict {
        if (cancelled) return Verdict(Outcome.UNKNOWN)
        gstaticFailure(gstatic)?.let { return Verdict(Outcome.FAIL, it) }
        if (content != null && !(content.kind == ProbeStep.Kind.OK && content.code in 200..299 && content.bytes >= MIN_CONTENT_BYTES)) {
            return Verdict(Outcome.FAIL, FailReason.NO_DATA)
        }
        if (exitIp != null && realIp != null && exitIp == realIp) return Verdict(Outcome.FAIL, FailReason.IP_SAME)
        if (stable != null && gstaticFailure(stable) != null) return Verdict(Outcome.FAIL, FailReason.UNSTABLE)
        return Verdict(Outcome.OK, ipUnknown = exitIp == null)
    }

    private fun gstaticFailure(step: ProbeStep): FailReason? = when (step.kind) {
        ProbeStep.Kind.OK -> if (step.code == 204) null else FailReason.TAMPERED
        ProbeStep.Kind.BAD_STATUS -> if (step.code in 200..399) FailReason.TAMPERED else FailReason.NO_GSTATIC
        ProbeStep.Kind.REFUSED -> FailReason.PROXY_DOWN
        ProbeStep.Kind.TIMEOUT, ProbeStep.Kind.IO -> FailReason.NO_GSTATIC
    }
}

// ---------------------------------------------------------------------------------------------
// Going back after the mobile network is gone
// ---------------------------------------------------------------------------------------------

/**
 * The server the user was on before the first switch of an episode. [mode] is "server" (a chosen
 * server) or "best" (the user pressed "Best": a new best is picked on return instead).
 */
data class BypassSnapshot(
    val mode: String,
    val guid: String,
    val name: String,
    val addr: String,
    val country: String,
    val netType: String,
    val at: Long,
) {
    fun encode(): String = listOf(mode, guid, name, addr, country, netType, at.toString())
        .joinToString("&") { URLEncoder.encode(it, "UTF-8") }

    companion object {
        const val MODE_SERVER = "server"
        const val MODE_BEST = "best"

        fun decode(text: String?): BypassSnapshot? {
            if (text.isNullOrBlank()) return null
            val parts = text.split('&').map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: return null }
            if (parts.size != 7) return null
            val at = parts[6].toLongOrNull() ?: return null
            return BypassSnapshot(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], at)
        }

        /** One snapshot per episode: a chain of bypass servers never overwrites the first. */
        fun shouldCreate(existing: BypassSnapshot?): Boolean = existing == null
    }
}

/** How the server from a snapshot looked when it was tested again. */
data class OriginState(val exists: Boolean, val alive: Boolean, val pingMs: Long)

enum class ReturnAction { STAY, RETURN, SEARCH_REPLACEMENT, RUN_BEST }

object ReturnLogic {
    const val DEFAULT_PING_LIMIT_MS = 400L
    const val DEFAULT_STABLE_SECONDS = 8

    /** "Very bad": gone, does not pass the check, or slower than [limitMs]. */
    fun isVeryBad(origin: OriginState?, limitMs: Long): Boolean =
        origin == null || !origin.exists || !origin.alive || origin.pingMs <= 0 || origin.pingMs > limitMs

    /**
     * What to do about the server from the snapshot.
     * [allowReplacement] is true on a normal network (Wi-Fi/Ethernet), false while still on the
     * mobile network, where only "the old server works again" is worth acting on.
     */
    fun decide(
        snapshotMode: String,
        currentIsOrigin: Boolean,
        origin: OriginState?,
        limitMs: Long,
        allowReplacement: Boolean,
    ): ReturnAction {
        if (currentIsOrigin) return ReturnAction.STAY
        if (allowReplacement && snapshotMode == BypassSnapshot.MODE_BEST) return ReturnAction.RUN_BEST
        if (isVeryBad(origin, limitMs)) return if (allowReplacement) ReturnAction.SEARCH_REPLACEMENT else ReturnAction.STAY
        return ReturnAction.RETURN
    }

    /**
     * Order for a replacement search: favorites, then servers of the same country as the old one,
     * then the rest; fastest known first inside each group. With [excludeRussia] (the default for
     * a return) Russian servers are never candidates; a bypass search on the mobile network passes
     * false because there Russian servers are often the only ones that work.
     */
    fun orderReplacement(
        guids: List<String>,
        favorites: Set<String>,
        sameCountry: (String) -> Boolean,
        isRussian: (String) -> Boolean,
        knownDelay: (String) -> Long,
        excludeRussia: Boolean = true,
    ): List<String> = guids.distinct()
        .filterNot { excludeRussia && isRussian(it) }
        .sortedWith(
            compareBy<String>(
                { if (it in favorites) 0 else if (sameCountry(it)) 1 else 2 },
                { knownDelay(it).let { d -> if (d > 0) d else Long.MAX_VALUE } },
            )
        )
}

/** Servers that failed a check are skipped for a while. */
object BadMarks {
    fun isBad(marks: Map<String, Long>, guid: String, now: Long, ttlMs: Long): Boolean =
        marks[guid]?.let { now - it in 0 until ttlMs } == true

    fun encode(marks: Map<String, Long>): String = marks.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun decode(text: String?, now: Long, ttlMs: Long): Map<String, Long> =
        text.orEmpty().split(';').mapNotNull { entry ->
            val idx = entry.lastIndexOf('=')
            if (idx <= 0) return@mapNotNull null
            val ts = entry.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            if (now - ts in 0 until ttlMs) entry.substring(0, idx) to ts else null
        }.toMap()
}

/**
 * The DNS for sites that go directly (not through the server): the upstream default was a Chinese public resolver, slow and
 * wrong-region from Russia. An untouched old default is replaced once; anything the user typed stays.
 */
object DirectDns {
    const val DEFAULT = "77.88.8.8"
    private val OLD_DEFAULTS = setOf("223.5.5.5", "223.6.6.6", "119.29.29.29")

    fun migrate(stored: String?): String? {
        val v = stored?.trim().orEmpty()
        return when {
            v.isEmpty() -> DEFAULT
            v in OLD_DEFAULTS -> DEFAULT
            else -> null
        }
    }
}

/** Migration of the ping URL: only the untouched old default is replaced, a user's own URL stays. */
object PingUrls {
    const val PRIMARY = "https://www.gstatic.com/generate_204"
    const val FALLBACK = "https://cp.cloudflare.com/generate_204"

    /**
     * Servers used on mobile whitelists are checked with this address only (as before the gstatic default): such servers
     * often pass little more than the big whitelisted sites, so gstatic or cloudflare would fail a working server.
     * Everything else keeps the ordinary ping address.
     */
    const val WHITELIST = "https://www.google.com/generate_204"

    private val oldDefaults = setOf(
        "https://www.google.com/generate_204",
        "http://www.google.com/generate_204",
        "https://google.com/generate_204",
    )

    /** The URL to store, or null to keep [stored] as it is. */
    fun migrate(stored: String?): String? {
        val value = stored?.trim().orEmpty()
        return when {
            value.isEmpty() -> PRIMARY
            value in oldDefaults -> PRIMARY
            else -> null
        }
    }
}
