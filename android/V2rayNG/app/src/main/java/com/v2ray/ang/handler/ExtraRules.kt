package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.net.RuleOrder

/**
 * Two switches on top of any rule profile (the same as on Windows, ServiceLib/Handler/ExtraRules.cs):
 *  - ad blocking: the category-ads-all list of the bundled geosite.dat goes to "block", before everything else;
 *  - "Telegram always through the server": Telegram's domains and addresses (geosite/geoip "telegram") go to the proxy.
 * Both off by default. Kept as locked rules at the top, rebuilt whenever the rule list is rebuilt.
 */
object ExtraRules {
    const val PREF_ADBLOCK = "pref_adblock_enabled"
    const val PREF_TELEGRAM_PROXY = "pref_telegram_proxy"

    private const val ID_ADS = "fv-adblock"
    private const val ID_TG_DOMAINS = "fv-telegram-domains"
    private const val ID_TG_IPS = "fv-telegram-ips"
    private val OWN = setOf(ID_ADS, ID_TG_DOMAINS, ID_TG_IPS)

    fun adBlock(): Boolean = MmkvManager.decodeSettingsBool(PREF_ADBLOCK, false)
    fun telegramProxy(): Boolean = MmkvManager.decodeSettingsBool(PREF_TELEGRAM_PROXY, false)

    /** Rewrites our rules at the top of the rule list according to the switches. */
    fun apply() {
        val top = buildList {
            if (adBlock()) add(RulesetItem(id = ID_ADS, remarks = "FlowVeil: блокировка рекламы", domain = listOf("geosite:category-ads-all"), outboundTag = AppConfig.TAG_BLOCKED, locked = true))
            if (telegramProxy()) {
                add(RulesetItem(id = ID_TG_DOMAINS, remarks = "FlowVeil: Telegram через сервер", domain = listOf("geosite:telegram"), outboundTag = AppConfig.TAG_PROXY, locked = true))
                add(RulesetItem(id = ID_TG_IPS, remarks = "FlowVeil: Telegram через сервер", ip = listOf("geoip:telegram"), outboundTag = AppConfig.TAG_PROXY, locked = true))
            }
        }
        val rules = MmkvManager.decodeRoutingRulesets() ?: mutableListOf()
        MmkvManager.encodeRoutingRulesets(RuleOrder.withTop(rules, { it.id }, OWN, top).toMutableList())
    }
}
