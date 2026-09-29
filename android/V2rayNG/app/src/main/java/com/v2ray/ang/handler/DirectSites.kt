package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem

/**
 * The user's own "open directly" list: sites (and IPs) that always bypass the server.
 * Stored as one locked routing rule at the top, so switching routing presets keeps it.
 */
object DirectSites {
    private const val RULE_ID = "flowveil-direct-sites"
    private const val KEY = "pref_direct_sites"

    /** The list as the user typed it (one entry per line). */
    fun text(): String = MmkvManager.decodeSettingsString(KEY).orEmpty()

    fun count(): Int = parse(text()).let { it.first.size + it.second.size }

    /** Saves the list and rewrites the routing rule; returns how many entries were understood. */
    fun save(raw: String): Int {
        val (domains, ips) = parse(raw)
        MmkvManager.encodeSettings(KEY, (domains.map { it.removePrefix("domain:") } + ips).joinToString("\n"))
        val rules = MmkvManager.decodeRoutingRulesets() ?: mutableListOf()
        rules.removeAll { it.id == RULE_ID }
        if (domains.isNotEmpty() || ips.isNotEmpty()) {
            rules.add(
                0,
                RulesetItem(
                    id = RULE_ID,
                    remarks = "FlowVeil: мои сайты напрямую",
                    domain = domains.takeIf { it.isNotEmpty() },
                    ip = ips.takeIf { it.isNotEmpty() },
                    outboundTag = AppConfig.TAG_DIRECT,
                    locked = true,
                )
            )
        }
        MmkvManager.encodeRoutingRulesets(rules)
        return domains.size + ips.size
    }

    private val ipLike = Regex("""^[0-9a-fA-F:.]+(/\d{1,3})?$""")

    /** "https://www.sberbank.ru/path" -> "domain:sberbank.ru"; IPs and CIDRs go to the IP list. */
    private fun parse(raw: String): Pair<List<String>, List<String>> {
        val domains = mutableListOf<String>()
        val ips = mutableListOf<String>()
        raw.split('\n', ',', ';', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val lower = entry.lowercase()
                when {
                    lower.startsWith("geosite:") || lower.startsWith("domain:") || lower.startsWith("full:") ||
                        lower.startsWith("regexp:") || lower.startsWith("keyword:") -> domains += entry
                    lower.startsWith("geoip:") -> ips += entry
                    ipLike.matches(lower) && (lower.contains('.') || lower.contains(':')) && lower.any { it.isDigit() } &&
                        !lower.any { it in 'g'..'z' } -> ips += lower
                    else -> {
                        val host = lower.substringAfter("://").substringBefore('/').substringBefore('?')
                            .substringBefore(':').removePrefix("www.").trim('.')
                        if (host.contains('.')) domains += "domain:$host"
                    }
                }
            }
        return domains.distinct() to ips.distinct()
    }
}
