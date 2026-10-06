package com.v2ray.ang.net

import kotlin.math.pow

/**
 * Builds a theme code (the JSON the colour-theme loader understands, colours as #RRGGBBAA) from five colours the user picked:
 * background top and bottom, accent, cards and text. Pure, so it can be tested; the app turns the code into a theme.
 */
object ThemeBuilder {
    data class Colors(
        val backgroundTop: String,
        val backgroundBottom: String,
        val accent: String,
        val card: String,
        val text: String,
    )

    private val HEX6 = Regex("^[0-9A-Fa-f]{6}$")

    /** "#3B82F6", "3b82f6" or "#3B82F6FF" → "3B82F6"; null when it is not a colour. */
    fun hex6(input: String?): String? {
        val t = input.orEmpty().trim().removePrefix("#")
        val six = if (t.length == 8) t.substring(0, 6) else t
        return six.takeIf { HEX6.matches(it) }?.uppercase()
    }

    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(hex: String): Double {
        val n = hex.toInt(16)
        return 0.2126 * channel((n shr 16) and 0xFF) + 0.7152 * channel((n shr 8) and 0xFF) + 0.0722 * channel(n and 0xFF)
    }

    /** WCAG contrast ratio of two colours (1..21). */
    fun contrast(a: String, b: String): Double {
        val la = luminance(hex6(a) ?: return 1.0)
        val lb = luminance(hex6(b) ?: return 1.0)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** White or near-black, whichever reads better on [background]. */
    fun onColor(background: String): String = if (contrast(background, "FFFFFF") >= contrast(background, "111111")) "FFFFFF" else "111111"

    /** True when the text on the cards and the background reads clearly (4.5:1, the usual minimum). */
    fun readable(c: Colors): Boolean = contrast(c.text, c.card) >= 4.5 && contrast(c.text, c.backgroundTop) >= 4.5 && contrast(c.text, c.backgroundBottom) >= 4.5

    private fun cleanName(name: String): String = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().take(30).ifEmpty { "Custom" }

    /** The theme code, or null when a colour is not valid. */
    fun build(c: Colors, name: String = "Custom"): String? {
        val top = hex6(c.backgroundTop) ?: return null
        val bottom = hex6(c.backgroundBottom) ?: return null
        val accent = hex6(c.accent) ?: return null
        val card = hex6(c.card) ?: return null
        val text = hex6(c.text) ?: return null
        val onAccent = onColor(accent)
        fun q(hex: String, alpha: String = "FF") = "\"#$hex$alpha\""
        val fields = listOf(
            "id" to "\"custom\"",
            "name" to "\"${cleanName(name)}\"",
            "backgroundColors" to "[${q(top)},${q(bottom)}]",
            "serverRowBackgroundColor" to q(card),
            "selectedServerRowColor" to q(accent, "55"),
            "subsHeaderColor" to q(card),
            "buttonColor" to q(accent),
            "buttonTextColor" to q(onAccent),
            "powerIconColor" to q(accent),
            "serverRowTitleTextColor" to q(text),
            "serverRowSubTitleTextColor" to q(text, "B3"),
            "topBarButtonsColor" to q(accent),
            "supportIconColor" to q(accent),
            "subHeaderButtonColor" to q(accent),
            "settingsControlsTintColor" to q(accent),
            "subscriptionInfoBackgroundColor" to q(card),
            "subscriptionTrafficBackgroundColor" to q(card),
            "subscriptionInfoTextColor" to q(text),
            "disclosureHeaderTextColor" to q(text),
            "disclosureSubHeaderTextColor" to q(text, "B3"),
            "serverRowChevronColor" to q(accent),
            "additionalOptionsButtonColor" to q(accent),
            "buttonTimerColor" to q(onAccent),
            "elipseColors" to "[${q(accent)},${q(top)}]",
            "backgroundImageType" to "\"system\"",
            "buttonImageType" to "\"${if (onAccent == "FFFFFF") "light" else "dark"}\"",
        )
        return fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
    }
}
