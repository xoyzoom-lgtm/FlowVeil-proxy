package com.v2ray.ang.net

/**
 * Small decisions of the automatic switching on restricted networks, kept pure so they can be tested
 * (see com.v2ray.ang.core.BypassController).
 */
object BypassRules {
    /**
     * Addresses that prove the connected server still carries traffic, in order. A server made for mobile whitelists often
     * passes only some big sites: one answer from any of them means it works, so a working server is never left because
     * one address did not answer. A dead server answers none of them.
     */
    val LIVE_CHECK_URLS = listOf(PingUrls.WHITELIST, PingUrls.PRIMARY, PingUrls.TELEGRAM)

    /**
     * The user picked another server himself: true only when the server in use is neither the bypass server we chose
     * ([active]) nor the last one FlowVeil itself switched to ([lastOurs]), and no switch of ours is in progress.
     * A server we switched to for a check that was not confirmed is ours, not the user's choice.
     */
    fun isUserChoice(current: String?, active: String?, lastOurs: String?, switching: Boolean): Boolean =
        !switching && active != null && current != null && current != active && current != lastOurs

    /**
     * After a try to go back to the old server failed (or could not be confirmed): the server to switch to so the user is
     * not left on one that does not work: the bypass server that worked ([active]), else the one we were on before the try
     * ([before]); null when the phone is on it already.
     */
    fun backAfterFailedReturn(active: String?, before: String, nowOn: String?): String? =
        (active ?: before).takeIf { it != nowOn }
}
