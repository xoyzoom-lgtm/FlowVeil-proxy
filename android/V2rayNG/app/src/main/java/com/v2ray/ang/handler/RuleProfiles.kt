package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.enums.RoutingType

/**
 * Three ready sets of routing rules that are switched with one tap (the same three as on Windows):
 * Russian sites direct and the rest through the server; everything through the server; everything direct.
 * Technical routing screens stay in developer mode; the user's own list of direct sites is kept on top of any profile.
 */
object RuleProfiles {
    const val PREF = "pref_rule_profile"

    enum class Profile(val key: String) {
        RU_DIRECT("ru"),
        ALL_PROXY("all"),
        ALL_DIRECT("direct"),
    }

    private const val ALL_DIRECT_RULES = """[{"remarks":"All direct","outboundTag":"direct","port":"0-65535"}]"""

    /** The profile in use; for installs that only know the old "Russian sites direct" switch it follows that switch. */
    fun current(): Profile? {
        val stored = MmkvManager.decodeSettingsString(PREF)
        Profile.entries.firstOrNull { it.key == stored }?.let { return it }
        return if (MmkvManager.decodeSettingsBool(AppConfig.PREF_RU_DIRECT, false)) Profile.RU_DIRECT else null
    }

    fun apply(context: Context, profile: Profile) {
        when (profile) {
            Profile.RU_DIRECT -> SettingsManager.resetRoutingRulesetsFromPresets(context, RoutingType.WHITE_RUSSIA)
            Profile.ALL_PROXY -> SettingsManager.resetRoutingRulesetsFromPresets(context, RoutingType.GLOBAL)
            Profile.ALL_DIRECT -> SettingsManager.resetRoutingRulesets(ALL_DIRECT_RULES)
        }
        MmkvManager.encodeSettings(PREF, profile.key)
        MmkvManager.encodeSettings(AppConfig.PREF_RU_DIRECT, profile == Profile.RU_DIRECT)
        // A preset replaces the whole rule list: put the user's own direct sites back on top.
        runCatching { DirectSites.save(DirectSites.text()) }
    }
}
