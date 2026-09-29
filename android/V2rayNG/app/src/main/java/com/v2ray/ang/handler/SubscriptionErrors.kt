package com.v2ray.ang.handler

import java.util.concurrent.ConcurrentHashMap

/** Why the last update of a subscription got no servers, in plain words for the UI. */
object SubscriptionErrors {
    private val errors = ConcurrentHashMap<String, String>()

    fun record(subId: String, reason: String) {
        // Name the subscription, so with several of them the user knows which one failed.
        val name = MmkvManager.decodeSubscription(subId)?.let { it.profileTitle?.takeIf { t -> t.isNotBlank() } ?: it.remarks }
        errors[subId] = if (name.isNullOrBlank()) reason else "«$name»: $reason"
    }

    fun clear(subId: String) {
        errors.remove(subId)
    }

    fun get(subId: String): String? = errors[subId]

    fun latest(subIds: Collection<String>): String? = subIds.firstNotNullOfOrNull { errors[it] }
}
