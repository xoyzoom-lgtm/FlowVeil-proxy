package com.v2ray.ang.dto

import com.v2ray.ang.dto.entities.SubscriptionItem

data class GroupMapItem(
    var id: String,
    var remarks: String,
    val subscription: SubscriptionItem? = null,
)
