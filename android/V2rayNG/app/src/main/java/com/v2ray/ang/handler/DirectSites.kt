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

    private const val DEFAULTS_KEY = "pref_direct_sites_defaults_v1"

    /** Popular Russian services that often refuse foreign IPs; subdomains are included. */
    val DEFAULTS = listOf(
        // marketplaces and shops
        "ozon.ru", "ozone.ru", "wildberries.ru", "wb.ru", "wbbasket.ru", "megamarket.ru", "avito.ru",
        "lamoda.ru", "dns-shop.ru", "mvideo.ru", "eldorado.ru", "citilink.ru", "detmir.ru", "lenta.com",
        // banks and payments
        "sberbank.ru", "sber.ru", "sberbank.com", "tbank.ru", "tinkoff.ru", "tinkoff.com", "alfabank.ru",
        "vtb.ru", "gazprombank.ru", "raiffeisen.ru", "pochtabank.ru", "sovcombank.ru", "open.ru",
        "rshb.ru", "mkb.ru", "psbank.ru", "nspk.ru", "sbp.nspk.ru", "mir-pay.ru", "yoomoney.ru",
        // Yandex (Go, Taxi, Maps, Market, Lavka, Music, Kinopoisk) and VK
        "yandex.ru", "yandex.com", "yandex.net", "ya.ru", "yastatic.net", "yandex-team.ru", "kinopoisk.ru",
        "vk.com", "vk.ru", "userapi.com", "vk-cdn.net", "mail.ru", "ok.ru", "dzen.ru", "rutube.ru",
        // maps, taxi, delivery
        "2gis.ru", "2gis.com", "citymobil.ru", "samokat.ru", "vkusvill.ru", "kuper.ru", "eda.ru",
        "delivery-club.ru", "magnit.ru", "5ka.ru", "perekrestok.ru", "x5.ru",
        // government, transport, operators, post
        "gosuslugi.ru", "nalog.gov.ru", "mos.ru", "pfr.gov.ru", "sfr.gov.ru", "rzd.ru", "aeroflot.ru",
        "pobeda.aero", "tutu.ru", "pochta.ru", "cdek.ru", "mts.ru", "beeline.ru", "megafon.ru", "t2.ru",
        "tele2.ru", "rostelecom.ru", "hh.ru",
    )

    /** First run: fill the list with [DEFAULTS] once; later the user's edits are kept. */
    fun ensureDefaults() {
        if (MmkvManager.decodeSettingsBool(DEFAULTS_KEY, false)) return
        val current = text().lines().filter { it.isNotBlank() }
        save((current + DEFAULTS).distinct().joinToString("\n"))
        MmkvManager.encodeSettings(DEFAULTS_KEY, true)
    }

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
        // Ad blocking and "Telegram through the server" stay above the user's direct sites.
        ExtraRules.apply()
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
