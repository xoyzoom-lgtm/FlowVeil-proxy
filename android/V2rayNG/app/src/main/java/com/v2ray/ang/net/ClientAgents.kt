package com.v2ray.ang.net

/**
 * Many subscription panels answer by the client name in the User-Agent: one gets the list, another gets an error,
 * a web page or a redirect to an app link. When our own name is refused, the same address is asked again as other
 * well-known clients, in this order (the first one that gives a list wins).
 */
object ClientAgents {
    val FALLBACK = listOf("v2rayN/7.25.2", "clash-verge/v2.2.3", "HiddifyNext/2.5.7", "sing-box/1.12.0")

    /** True when it is worth asking again under another name: the provider refused or sent us elsewhere, not "no network". */
    fun retryable(error: String?): Boolean {
        if (error.isNullOrBlank()) return false
        if (error.startsWith("redirect to an app link: ")) return true
        val code = Regex("status code (\\d{3})").find(error)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return code in 400..499 && code != 404 && code != 410 || code in 500..599
    }
}
