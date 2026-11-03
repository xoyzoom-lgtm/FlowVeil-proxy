package com.v2ray.ang.net

/**
 * Whether to suggest the automatic switch for restricted mobile networks: only when it is off, the user has not said "no thanks"
 * before, and the subscription really has servers that are made for it (strong marks, see [BypassClassifier]). Pure, so it can be tested.
 */
object BypassOfferLogic {
    fun shouldOffer(enabled: Boolean, dismissed: Boolean, strongServers: Int): Boolean =
        !enabled && !dismissed && strongServers > 0
}
