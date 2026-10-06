package com.v2ray.ang.dto.entities

data class SubscriptionItem(
    var remarks: String = "",
    var url: String = "",
    var enabled: Boolean = true,
    val addedTime: Long = System.currentTimeMillis(),
    var lastUpdated: Long = -1,
    var autoUpdate: Boolean = false,
    var updateInterval: Long = 1440, // in minutes, default to 24 hours
    var prevProfile: String? = null,
    var nextProfile: String? = null,
    var filter: String? = null,
    var allowInsecureUrl: Boolean = false,
    var userAgent: String? = null,
    /** Also send the device ID in a Cookie header: some panels read it only from there. Off by default. */
    var sendHwidCookie: Boolean = false,
    var requestHeaders: String? = null,
    // Provider metadata from response headers; nullable because older stored items lack them.
    var trafficUsed: Long? = null,
    var trafficTotal: Long? = null,
    var expireAt: Long? = null,
    var profileTitle: String? = null,
    var announce: String? = null,
    var supportUrl: String? = null,
    var webPageUrl: String? = null,
)
