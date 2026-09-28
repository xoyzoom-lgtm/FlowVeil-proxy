package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.SubscriptionItem
import okio.ByteString.Companion.decodeBase64

/**
 * Reads the de-facto standard provider response headers (`subscription-userinfo`,
 * `profile-title`, `announce`, `support-url`) that most subscription panels send.
 */
object SubscriptionInfoParser {

    data class UserInfo(val used: Long?, val total: Long?, val expireAt: Long?)

    /** [headers] keys must be lower-case. */
    fun apply(item: SubscriptionItem, headers: Map<String, String>) {
        headers["subscription-userinfo"]?.let(::parseUserInfo)?.let { info ->
            item.trafficUsed = info.used
            item.trafficTotal = info.total
            item.expireAt = info.expireAt
        }
        headers["profile-title"]?.let(::decodeMaybeBase64)?.takeIf { it.isNotBlank() }?.let {
            item.profileTitle = it
        }
        headers["announce"]?.let(::decodeMaybeBase64)?.let {
            item.announce = it.ifBlank { null }
        }
        headers["support-url"]?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("tg://") }
            ?.let { item.supportUrl = it }
    }

    /** Parses `upload=..; download=..; total=..; expire=..`; total/expire of 0 mean unlimited/none. */
    fun parseUserInfo(value: String): UserInfo? {
        val fields = value.split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val number = part.substring(idx + 1).trim().toDoubleOrNull()?.toLong() ?: return@mapNotNull null
            part.substring(0, idx).trim().lowercase() to number
        }.toMap()
        if (fields.isEmpty()) return null
        val upload = fields["upload"]
        val download = fields["download"]
        val used = if (upload == null && download == null) null else (upload ?: 0L) + (download ?: 0L)
        val total = fields["total"]?.takeIf { it > 0L }
        val expireAt = fields["expire"]?.takeIf { it > 0L }?.times(1000L)
        return UserInfo(used, total, expireAt)
    }

    fun decodeMaybeBase64(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("base64:", ignoreCase = true)) return trimmed
        return trimmed.substring("base64:".length).trim().decodeBase64()?.utf8()?.trim().orEmpty()
    }
}
