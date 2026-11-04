package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.net.ModePresets

/** Applies one of the three home-screen modes (see [ModePresets]) on top of a rule profile. */
object ModePreset {
    const val PREF = "pref_mode_preset"

    /** The three buttons on the home screen are off until the user turns them on in settings. */
    const val PREF_SHOW = "pref_show_modes"

    /** A mode chosen before this switch existed (build 241) keeps its buttons visible, so it can still be seen and changed. */
    fun shown(): Boolean = MmkvManager.decodeSettingsBool(PREF_SHOW, false) || current() != null

    /** Turning the buttons off also ends the mode: its extra rules must not keep working with no way to see or undo them. */
    fun setShown(on: Boolean) {
        MmkvManager.encodeSettings(PREF_SHOW, on)
        if (!on && current() != null) {
            MmkvManager.encodeSettings(PREF, "")
            ExtraRules.apply()
        }
    }

    fun current(): ModePresets.Mode? = ModePresets.byKey(MmkvManager.decodeSettingsString(PREF))

    fun apply(context: Context, mode: ModePresets.Mode) {
        val base = when (mode) {
            ModePresets.Mode.FULL -> RuleProfiles.Profile.ALL_PROXY
            ModePresets.Mode.GAMES -> RuleProfiles.Profile.RU_DIRECT
            ModePresets.Mode.SOCIAL -> RuleProfiles.Profile.ALL_DIRECT
        }
        // The profile clears the mode, so the mode is stored after it and the rules are rebuilt once more.
        RuleProfiles.apply(context, base)
        MmkvManager.encodeSettings(PREF, mode.key)
        ExtraRules.apply()
    }

    /** The mode's rules as locked ruleset items for [ExtraRules]. */
    fun items(): List<RulesetItem> = ModePresets.rules(current()).map { r ->
        RulesetItem(
            id = r.id, remarks = r.remarks, domain = r.domain, protocol = r.protocol,
            outboundTag = if (r.outbound == ModePresets.PROXY) AppConfig.TAG_PROXY else AppConfig.TAG_DIRECT,
            locked = true,
        )
    }
}
