package com.v2ray.ang.net

/**
 * Start errors of the Xray core that people hit through no fault of their own and that read as gibberish in English.
 * Only the kind is decided here (pure, testable); the Russian / English text lives in the string resources.
 */
object CoreErrors {
    enum class Kind { PLAIN_VLESS, PLAIN_TROJAN, OTHER }

    /**
     * Xray (since v26.7) refuses a VLESS or Trojan server without TLS / REALITY / encryption unless the address is private:
     * "vless without TLS or other encryption is prohibited unless the server address is a private IP or domain".
     */
    fun classify(raw: String?): Kind {
        val text = raw?.lowercase().orEmpty()
        return when {
            "vless without tls" in text && "prohibited" in text -> Kind.PLAIN_VLESS
            "trojan without tls" in text && "prohibited" in text -> Kind.PLAIN_TROJAN
            else -> Kind.OTHER
        }
    }
}
