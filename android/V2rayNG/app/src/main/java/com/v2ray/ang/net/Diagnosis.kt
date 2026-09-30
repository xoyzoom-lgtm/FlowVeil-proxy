package com.v2ray.ang.net

/*
 * "Why does it not work?" — the pure part (no Android classes, so it runs in JVM unit tests).
 * One list of cause codes shared with the Windows client (ServiceLib/Handler/Diagnosis.cs): the ids
 * are the same on both sides, the texts live in each client's own strings. Everything that reaches a
 * user-visible sentence goes through [DiagCause]; unknown situations are logged, not guessed.
 */

/** What is wrong, in the order the decision goes (see [Diagnosis.diagnose]). */
enum class DiagCause(val id: String) {
    OK("ok"),
    NO_NETWORK("no_network"),
    CAPTIVE_PORTAL("captive_portal"),
    WRONG_TIME("wrong_time"),
    DNS_FAILED("dns_failed"),
    SUB_EXPIRED("sub_expired"),
    SUB_TRAFFIC_OVER("sub_traffic_over"),
    SUB_DEVICE_LIMIT("sub_device_limit"),
    SUB_BLOCKED("sub_blocked"),
    SUB_ACCESS_DENIED("sub_access_denied"),
    SUB_LINK_UNKNOWN("sub_link_unknown"),
    SUB_WEB_PAGE("sub_web_page"),
    SUB_HAPP_CRYPT("sub_happ_crypt"),
    SUB_NO_SERVERS("sub_no_servers"),
    SUB_RATE_LIMITED("sub_rate_limited"),
    SUB_PROVIDER_DOWN("sub_provider_down"),
    SUB_UNREACHABLE("sub_unreachable"),
    NO_SERVER("no_server"),
    NOT_CONNECTED("not_connected"),
    SERVER_DOWN("server_down"),
    SERVER_NOT_PASSING("server_not_passing"),
    MOBILE_RESTRICTED("mobile_restricted"),

    /** Not a reason for "no connection", an advisory shown next to the verdict. */
    BATTERY_RESTRICTED("battery_restricted");

    companion object {
        fun fromId(id: String?): DiagCause? = entries.firstOrNull { it.id == id }
    }
}

enum class StepStatus { OK, WARN, FAIL, SKIPPED }

enum class DiagStepId { NETWORK, TIME, DNS, SUBSCRIPTION, SERVER, END_TO_END, RESTRICTION, BATTERY }

data class DiagStep(val id: DiagStepId, val status: StepStatus, val cause: DiagCause? = null)

data class DiagResult(val steps: List<DiagStep>, val cause: DiagCause, val warnings: List<DiagCause>)

/** A subscription problem as far as it can be told from what the provider sent. */
enum class SubIssue(val cause: DiagCause?) {
    NONE(null),
    EXPIRED(DiagCause.SUB_EXPIRED),
    TRAFFIC_OVER(DiagCause.SUB_TRAFFIC_OVER),
    DEVICE_LIMIT(DiagCause.SUB_DEVICE_LIMIT),
    BLOCKED(DiagCause.SUB_BLOCKED),
    ACCESS_DENIED(DiagCause.SUB_ACCESS_DENIED),
    LINK_UNKNOWN(DiagCause.SUB_LINK_UNKNOWN),
    WEB_PAGE(DiagCause.SUB_WEB_PAGE),
    HAPP_CRYPT(DiagCause.SUB_HAPP_CRYPT),
    NO_SERVERS(DiagCause.SUB_NO_SERVERS),
    RATE_LIMITED(DiagCause.SUB_RATE_LIMITED),
    PROVIDER_DOWN(DiagCause.SUB_PROVIDER_DOWN),
    UNREACHABLE(DiagCause.SUB_UNREACHABLE);

    companion object {
        fun fromId(id: String?): SubIssue = entries.firstOrNull { it.name == id } ?: NONE
    }
}

/**
 * Reads the state of a subscription from the de-facto standard signals. All of it is heuristics
 * (panels differ); what cannot be told is [SubIssue.NONE] and gets logged by the caller.
 * Never touches any device identifier: only what the provider sent us.
 */
object SubscriptionHealth {
    /** Names of the "fake" servers panels put into a subscription to say something to the user. */
    private val markers: List<Pair<SubIssue, List<String>>> = listOf(
        SubIssue.DEVICE_LIMIT to listOf("device limit", "devices limit", "limit of devices", "hwid limit", "лимит устройств", "лимит девайсов", "превышен лимит", "limit reached", "too many devices"),
        SubIssue.EXPIRED to listOf("expired", "subscription ended", "истёк", "истек", "закончилась", "срок действия"),
        SubIssue.TRAFFIC_OVER to listOf("traffic limit", "traffic exhausted", "no traffic", "трафик закончился", "трафик исчерпан", "исчерпан трафик", "лимит трафика"),
        SubIssue.BLOCKED to listOf("blocked", "banned", "disabled", "заблокирован", "отключена", "отключён", "отключен", "приостановлена"),
    )

    /** [names] are the server names of the subscription. A marker counts when the list is a notice (≤ 3 entries) or consists of markers only. */
    fun byServerNames(names: List<String>): SubIssue {
        if (names.isEmpty()) return SubIssue.NONE
        val matches = names.map { name -> markers.firstOrNull { (_, words) -> words.any { name.contains(it, ignoreCase = true) } }?.first }
        val first = matches.firstOrNull { it != null } ?: return SubIssue.NONE
        return if (names.size <= 3 || matches.all { it != null }) first else SubIssue.NONE
    }

    /** From `subscription-userinfo`: [expireAtMs] and [total]/[used] in bytes (null = unknown/unlimited). */
    fun byInfo(expireAtMs: Long?, used: Long?, total: Long?, nowMs: Long): SubIssue = when {
        expireAtMs != null && expireAtMs in 1 until nowMs -> SubIssue.EXPIRED
        total != null && total > 0 && used != null && used >= total -> SubIssue.TRAFFIC_OVER
        else -> SubIssue.NONE
    }

    /** From the status of the last failed download. */
    fun byHttpStatus(code: Int): SubIssue = when (code) {
        404, 410 -> SubIssue.LINK_UNKNOWN
        401, 403 -> SubIssue.ACCESS_DENIED
        429 -> SubIssue.RATE_LIMITED
        in 500..599 -> SubIssue.PROVIDER_DOWN
        else -> SubIssue.UNREACHABLE
    }

    /** From a downloaded body that gave no servers. */
    fun byBody(body: String): SubIssue {
        val text = body.trimStart()
        return when {
            text.startsWith("happ://", ignoreCase = true) -> SubIssue.HAPP_CRYPT
            text.startsWith("<") -> SubIssue.WEB_PAGE
            else -> SubIssue.NO_SERVERS
        }
    }

    /** The most telling issue first: what the provider says in words, then numbers, then the failed download. */
    fun combine(byNames: SubIssue, byInfo: SubIssue, lastError: SubIssue): SubIssue =
        listOf(byNames, byInfo, lastError).firstOrNull { it != SubIssue.NONE } ?: SubIssue.NONE
}

/** What the checks found. `null` = the check could not be made (never counts against the server). */
data class DiagInputs(
    val net: NetType,
    val captive: Boolean = false,
    /** Phone clock minus the time the internet reports; null when unknown. */
    val clockSkewMs: Long? = null,
    /** Name resolution on the real network worked. */
    val dnsOk: Boolean? = null,
    val sub: SubIssue = SubIssue.NONE,
    val hasServer: Boolean = true,
    val running: Boolean = false,
    /** The server accepted a TCP connection (null: UDP-based or not measurable). */
    val serverReachable: Boolean? = null,
    /** gstatic 204 through the running core; null when not connected. */
    val endToEnd: ProbeStep? = null,
    /** On the real network directly: a domestic site / a foreign site answered. */
    val domesticDirect: Boolean? = null,
    val foreignDirect: Boolean? = null,
    val batteryIgnored: Boolean? = null,
)

object Diagnosis {
    /** A clock that differs by more than this breaks TLS and Reality handshakes. */
    const val MAX_CLOCK_SKEW_MS = 5 * 60_000L

    fun diagnose(i: DiagInputs): DiagResult {
        val steps = ArrayList<DiagStep>()
        fun add(id: DiagStepId, status: StepStatus, cause: DiagCause? = null) {
            steps += DiagStep(id, status, cause)
        }

        val noNet = i.net == NetType.NONE
        add(DiagStepId.NETWORK, if (noNet) StepStatus.FAIL else if (i.captive) StepStatus.FAIL else StepStatus.OK,
            if (noNet) DiagCause.NO_NETWORK else if (i.captive) DiagCause.CAPTIVE_PORTAL else null)

        val skewBad = i.clockSkewMs != null && Math.abs(i.clockSkewMs) > MAX_CLOCK_SKEW_MS
        add(DiagStepId.TIME, when { i.clockSkewMs == null -> StepStatus.SKIPPED; skewBad -> StepStatus.FAIL; else -> StepStatus.OK },
            if (skewBad) DiagCause.WRONG_TIME else null)

        val dnsBad = i.dnsOk == false
        add(DiagStepId.DNS, when { i.dnsOk == null -> StepStatus.SKIPPED; dnsBad -> StepStatus.FAIL; else -> StepStatus.OK },
            if (dnsBad) DiagCause.DNS_FAILED else null)

        add(DiagStepId.SUBSCRIPTION, if (i.sub == SubIssue.NONE) StepStatus.OK else StepStatus.FAIL, i.sub.cause)

        val serverStatus = when {
            !i.hasServer -> StepStatus.FAIL
            i.serverReachable == null -> StepStatus.SKIPPED
            i.serverReachable -> StepStatus.OK
            else -> StepStatus.FAIL
        }
        add(DiagStepId.SERVER, serverStatus, when {
            !i.hasServer -> DiagCause.NO_SERVER
            i.serverReachable == false -> DiagCause.SERVER_DOWN
            else -> null
        })

        val e2e = i.endToEnd
        val e2eOk = e2e != null && e2e.kind == ProbeStep.Kind.OK && e2e.code == 204
        val restricted = i.domesticDirect == true && i.foreignDirect == false
        add(DiagStepId.END_TO_END,
            when { !i.running || e2e == null -> StepStatus.SKIPPED; e2eOk -> StepStatus.OK; else -> StepStatus.FAIL },
            if (i.running && e2e != null && !e2eOk) (if (restricted) DiagCause.MOBILE_RESTRICTED else DiagCause.SERVER_NOT_PASSING) else null)
        add(DiagStepId.RESTRICTION,
            when { i.domesticDirect == null || i.foreignDirect == null -> StepStatus.SKIPPED; restricted -> StepStatus.WARN; else -> StepStatus.OK },
            if (restricted) DiagCause.MOBILE_RESTRICTED else null)

        val batteryBad = i.batteryIgnored == false
        add(DiagStepId.BATTERY, when { i.batteryIgnored == null -> StepStatus.SKIPPED; batteryBad -> StepStatus.WARN; else -> StepStatus.OK },
            if (batteryBad) DiagCause.BATTERY_RESTRICTED else null)

        val cause = when {
            noNet -> DiagCause.NO_NETWORK
            i.captive -> DiagCause.CAPTIVE_PORTAL
            skewBad -> DiagCause.WRONG_TIME
            dnsBad -> DiagCause.DNS_FAILED
            i.sub != SubIssue.NONE -> i.sub.cause!!
            !i.hasServer -> DiagCause.NO_SERVER
            i.serverReachable == false -> DiagCause.SERVER_DOWN
            !i.running -> DiagCause.NOT_CONNECTED
            e2e != null && !e2eOk -> if (restricted) DiagCause.MOBILE_RESTRICTED else DiagCause.SERVER_NOT_PASSING
            else -> DiagCause.OK
        }
        val warnings = buildList {
            if (batteryBad) add(DiagCause.BATTERY_RESTRICTED)
            if (restricted && cause != DiagCause.MOBILE_RESTRICTED) add(DiagCause.MOBILE_RESTRICTED)
        }
        return DiagResult(steps, cause, warnings)
    }
}

/** Where a phone maker hides the "run in background" switch, for the battery step. */
object BatteryGuide {
    enum class Maker(val key: String) { XIAOMI("xiaomi"), HUAWEI("huawei"), SAMSUNG("samsung"), ONEPLUS("oneplus"), VIVO("vivo"), OPPO("oppo"), OTHER("other") }

    fun maker(manufacturer: String?, brand: String? = null): Maker {
        val text = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco").any { it in text } -> Maker.XIAOMI
            listOf("huawei", "honor").any { it in text } -> Maker.HUAWEI
            "samsung" in text -> Maker.SAMSUNG
            "oneplus" in text -> Maker.ONEPLUS
            listOf("vivo", "iqoo").any { it in text } -> Maker.VIVO
            listOf("oppo", "realme").any { it in text } -> Maker.OPPO
            else -> Maker.OTHER
        }
    }
}

/**
 * Hides what must not end up in a copied report: subscription and other web links, share links of
 * servers, tokens and keys of the pairing, UUIDs, IP addresses. Text only; the caller adds nothing secret.
 */
object ReportMask {
    private val proxyLink = Regex("""\b(vless|vmess|trojan|ss|hysteria2|hy2|tuic|wireguard|anytls)://\S+""", RegexOption.IGNORE_CASE)
    private val webLink = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)
    private val pairFragment = Regex("""#t=[^\s&]+(&k=[^\s&]+)?(&v=\d+)?""")
    private val uuid = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")
    private val ipv4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    private val ipv6 = Regex("""\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b""")
    private val keyValue = Regex("""(?i)\b(token|key|password|pass|secret|hwid|ssid)\s*[=:]\s*\S+""")

    fun apply(text: String): String {
        var out = text
        out = proxyLink.replace(out) { it.groupValues[1] + "://***" }
        out = pairFragment.replace(out, "#***")
        out = webLink.replace(out, "https://***")
        out = uuid.replace(out) { it.value.substring(0, 8) + "-****" }
        out = ipv6.replace(out, "[ip]")
        out = ipv4.replace(out, "[ip]")
        out = keyValue.replace(out) { it.groupValues[1] + "=***" }
        return out
    }
}

/** The `Date` header of an HTTP answer (RFC 1123), as epoch milliseconds; null when it is missing or odd. */
object HttpDate {
    fun parse(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            format.isLenient = false
            format.parse(value.trim())?.time
        }.getOrNull()
    }
}
