package com.v2ray.ang.net

/**
 * How a subscription card looks, chosen by the user (Subscription → Appearance). Kept on the phone only.
 * Stored as one short text `avatar=emoji;emoji=🚀;grad=3;bg=1;rev=17`; unknown or broken parts fall back to the automatic look.
 */
data class SubLook(
    val avatar: Avatar = Avatar.NONE,
    val emoji: String = "",
    /** Index into [HomeCard.GRADIENTS], or -1 = automatic (by subscription id). */
    val gradient: Int = -1,
    val background: Boolean = false,
    /** Changes with every picture, so cached images are replaced. */
    val rev: Long = 0L,
) {
    enum class Avatar { NONE, PHOTO, EMOJI }

    fun encode(): String = buildString {
        append("avatar=").append(avatar.name.lowercase())
        if (avatar == Avatar.EMOJI && emoji.isNotEmpty()) append(";emoji=").append(emoji)
        if (gradient >= 0) append(";grad=").append(gradient)
        if (background) append(";bg=1")
        if (rev != 0L) append(";rev=").append(rev)
    }

    val isDefault: Boolean get() = avatar == Avatar.NONE && gradient < 0 && !background

    companion object {
        const val MAX_EMOJI = 8
        /** Ready choices of the emoji picker. */
        val EMOJIS = listOf("🚀", "🛡️", "⚡", "🦊", "🐺", "🌍", "🔒", "💎", "🔥", "🌙", "🎮", "☁️")

        fun cleanEmoji(raw: String?): String {
            val t = raw.orEmpty().trim()
            if (t.isEmpty() || t.length > MAX_EMOJI) return ""
            // Only pictographs and joiners: no letters, digits or separators that could break the stored text.
            return if (t.all { !it.isLetterOrDigit() && it != ';' && it != '=' && !it.isISOControl() && !it.isWhitespace() }) t else ""
        }

        fun decode(text: String?): SubLook {
            if (text.isNullOrBlank()) return SubLook()
            val map = text.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
            }.toMap()
            val avatar = when (map["avatar"]) {
                "photo" -> Avatar.PHOTO
                "emoji" -> Avatar.EMOJI
                else -> Avatar.NONE
            }
            val emoji = cleanEmoji(map["emoji"])
            return SubLook(
                avatar = if (avatar == Avatar.EMOJI && emoji.isEmpty()) Avatar.NONE else avatar,
                emoji = emoji,
                gradient = map["grad"]?.toIntOrNull()?.takeIf { it in 0 until HomeCard.GRADIENTS.size } ?: -1,
                background = map["bg"] == "1",
                rev = map["rev"]?.toLongOrNull() ?: 0L,
            )
        }
    }
}

/** Telegram profile pictures: from a link or @name the user typed; only Telegram's own picture hosts are accepted. */
object TelegramAvatar {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")

    /** `t.me/name`, `https://t.me/name`, `@name` or `name` → the user name; links with paths such as `t.me/+invite` or `t.me/c/123` are refused. */
    fun username(input: String?): String? {
        var t = input.orEmpty().trim()
        if (t.isEmpty() || t.length > 120 || t.any { it.isISOControl() || it.isWhitespace() }) return null
        t = t.removePrefix("@")
        val lower = t.lowercase()
        for (prefix in listOf("https://t.me/", "http://t.me/", "https://telegram.me/", "t.me/", "telegram.me/")) {
            if (lower.startsWith(prefix)) {
                t = t.substring(prefix.length)
                break
            }
        }
        t = t.substringBefore('?').substringBefore('#').trimEnd('/')
        return t.takeIf { NAME.matches(it) }
    }

    /** The picture address of a public Telegram page (`og:image`), or null when there is none or it is only Telegram's logo. */
    fun ogImage(html: String?): String? {
        if (html.isNullOrEmpty() || html.length > 600_000) return null
        val tag = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)?.value ?: return null
        val url = Regex("content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.trim() ?: return null
        return url.takeIf { allowedImageUrl(it) && !it.contains("t_logo") }
    }

    fun allowedImageUrl(url: String): Boolean {
        if (!url.startsWith("https://") || url.length > 600 || url.any { it.isISOControl() || it.isWhitespace() }) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        return host == "telegram.org" || host.endsWith(".telegram.org") || host == "telesco.pe" || host.endsWith(".telesco.pe") ||
            host == "t.me" || host.endsWith(".t.me") || host.endsWith(".cdn-telegram.org")
    }
}
