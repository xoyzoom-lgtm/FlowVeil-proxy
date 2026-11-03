package com.v2ray.ang.handler

import com.v2ray.ang.net.BypassLevel
import com.v2ray.ang.net.BypassOfferLogic

/** The one-time suggestion on the home screen to turn on the automatic switch (see [BypassOfferLogic]). */
object BypassOffer {
    private const val PREF_DISMISSED = "pref_bypass_offer_dismissed"

    /** True when the subscription [groupId] holds servers marked for restricted mobile networks and the user was not asked yet. */
    fun shouldOffer(groupId: String): Boolean {
        if (WhitelistBypass.isEnabled() || MmkvManager.decodeSettingsBool(PREF_DISMISSED, false)) return false
        val strong = MmkvManager.decodeServerList(groupId).count { guid ->
            BypassRating.rate(guid, bucket = null)?.rating?.level == BypassLevel.STRONG
        }
        return BypassOfferLogic.shouldOffer(enabled = false, dismissed = false, strongServers = strong)
    }

    fun accept() {
        MmkvManager.encodeSettings(WhitelistBypass.PREF_ENABLED, true)
        MmkvManager.encodeSettings(PREF_DISMISSED, true)
    }

    fun dismiss() {
        MmkvManager.encodeSettings(PREF_DISMISSED, true)
    }
}
