package com.v2ray.ang.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Color theme model, compatible with the "theme code" JSON format used by Happ
 * (colors are 8-digit hex strings in #RRGGBBAA order).
 */
data class HappColorTheme(
    val id: String = "custom",
    val name: String = "Custom",
    val backgroundColors: List<String> = emptyList(),
    val serverRowBackgroundColor: String = "#FFFFFFFF",
    val selectedServerRowColor: String = "#E0E0E0FF",
    val subsHeaderColor: String = "#F0F0F0FF",
    val buttonColor: String = "#007AFFFF",
    val buttonTextColor: String = "#FFFFFFFF",
    val powerIconColor: String = "#007AFFFF",
    val serverRowTitleTextColor: String = "#111827FF",
    val serverRowSubTitleTextColor: String = "#64748BFF",
    val topBarButtonsColor: String = "#007AFFFF",
    val supportIconColor: String = "#007AFFFF",
    val subHeaderButtonColor: String = "#007AFFFF",
    val settingsControlsTintColor: String = "#007AFFFF",
    val subscriptionInfoBackgroundColor: String = "#FFFFFFAA",
    val subscriptionTrafficBackgroundColor: String = "#D8E9FFFF",
    val subscriptionInfoTextColor: String = "#111827FF",
    val disclosureHeaderTextColor: String = "#111827FF",
    val disclosureSubHeaderTextColor: String = "#64748BFF",
    val serverRowChevronColor: String = "#007AFFFF",
    val additionalOptionsButtonColor: String = "#007AFFFF",
    val buttonTimerColor: String = "#FFFFFFFF",
    val elipseColors: List<String> = emptyList(),
    val backgroundImageType: String = "system",
    val buttonImageType: String = "light"
)

/**
 * Mirrors [HappColorTheme] with every field nullable, since Gson bypasses Kotlin
 * constructors (and their defaults) when a JSON field is missing.
 */
private data class HappColorThemeJson(
    val id: String? = null,
    val name: String? = null,
    val backgroundColors: List<String>? = null,
    val serverRowBackgroundColor: String? = null,
    val selectedServerRowColor: String? = null,
    val subsHeaderColor: String? = null,
    val buttonColor: String? = null,
    val buttonTextColor: String? = null,
    val powerIconColor: String? = null,
    val serverRowTitleTextColor: String? = null,
    val serverRowSubTitleTextColor: String? = null,
    val topBarButtonsColor: String? = null,
    val supportIconColor: String? = null,
    val subHeaderButtonColor: String? = null,
    val settingsControlsTintColor: String? = null,
    val subscriptionInfoBackgroundColor: String? = null,
    val subscriptionTrafficBackgroundColor: String? = null,
    val subscriptionInfoTextColor: String? = null,
    val disclosureHeaderTextColor: String? = null,
    val disclosureSubHeaderTextColor: String? = null,
    val serverRowChevronColor: String? = null,
    val additionalOptionsButtonColor: String? = null,
    val buttonTimerColor: String? = null,
    val elipseColors: List<String>? = null,
    val backgroundImageType: String? = null,
    val buttonImageType: String? = null
) {
    fun toHappColorTheme(): HappColorTheme {
        val default = HappColorTheme()
        return HappColorTheme(
            id = id ?: default.id,
            name = name ?: default.name,
            backgroundColors = backgroundColors?.filterNotNull()?.takeIf { it.isNotEmpty() } ?: default.backgroundColors,
            serverRowBackgroundColor = serverRowBackgroundColor ?: default.serverRowBackgroundColor,
            selectedServerRowColor = selectedServerRowColor ?: default.selectedServerRowColor,
            subsHeaderColor = subsHeaderColor ?: default.subsHeaderColor,
            buttonColor = buttonColor ?: default.buttonColor,
            buttonTextColor = buttonTextColor ?: default.buttonTextColor,
            powerIconColor = powerIconColor ?: default.powerIconColor,
            serverRowTitleTextColor = serverRowTitleTextColor ?: default.serverRowTitleTextColor,
            serverRowSubTitleTextColor = serverRowSubTitleTextColor ?: default.serverRowSubTitleTextColor,
            topBarButtonsColor = topBarButtonsColor ?: default.topBarButtonsColor,
            supportIconColor = supportIconColor ?: default.supportIconColor,
            subHeaderButtonColor = subHeaderButtonColor ?: default.subHeaderButtonColor,
            settingsControlsTintColor = settingsControlsTintColor ?: default.settingsControlsTintColor,
            subscriptionInfoBackgroundColor = subscriptionInfoBackgroundColor ?: default.subscriptionInfoBackgroundColor,
            subscriptionTrafficBackgroundColor = subscriptionTrafficBackgroundColor ?: default.subscriptionTrafficBackgroundColor,
            subscriptionInfoTextColor = subscriptionInfoTextColor ?: default.subscriptionInfoTextColor,
            disclosureHeaderTextColor = disclosureHeaderTextColor ?: default.disclosureHeaderTextColor,
            disclosureSubHeaderTextColor = disclosureSubHeaderTextColor ?: default.disclosureSubHeaderTextColor,
            serverRowChevronColor = serverRowChevronColor ?: default.serverRowChevronColor,
            additionalOptionsButtonColor = additionalOptionsButtonColor ?: default.additionalOptionsButtonColor,
            buttonTimerColor = buttonTimerColor ?: default.buttonTimerColor,
            elipseColors = elipseColors?.filterNotNull() ?: default.elipseColors,
            backgroundImageType = backgroundImageType ?: default.backgroundImageType,
            buttonImageType = buttonImageType ?: default.buttonImageType
        )
    }
}

/**
 * Parses a hex color string in #RRGGBB or #RRGGBBAA order (the format Happ theme
 * codes use), as opposed to Compose's own #AARRGGBB order.
 */
fun String.toHappColor(fallback: Color = Color.Gray): Color {
    val hex = removePrefix("#").trim()
    return try {
        when (hex.length) {
            8 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                val a = hex.substring(6, 8).toInt(16)
                Color(red = r, green = g, blue = b, alpha = a)
            }

            6 -> {
                val r = hex.substring(0, 2).toInt(16)
                val g = hex.substring(2, 4).toInt(16)
                val b = hex.substring(4, 6).toInt(16)
                Color(red = r, green = g, blue = b, alpha = 255)
            }

            else -> fallback
        }
    } catch (e: NumberFormatException) {
        fallback
    }
}

fun HappColorTheme.backgroundBrush(): Brush {
    val colors = backgroundColors.map { it.toHappColor() }
    return if (colors.size >= 2) {
        Brush.verticalGradient(colors)
    } else {
        Brush.verticalGradient(listOf(colors.firstOrNull() ?: Color.Black, colors.firstOrNull() ?: Color.Black))
    }
}

val HappColorTheme.isLight: Boolean
    get() = (backgroundColors.firstOrNull() ?: "#000000FF").toHappColor().luminance() > 0.5f

fun HappColorTheme.toColorScheme(): ColorScheme {
    val buttonColor = buttonColor.toHappColor()
    val buttonTextColor = buttonTextColor.toHappColor()
    val background = (backgroundColors.firstOrNull() ?: "#000000FF").toHappColor()
    // Menus and dialogs draw on `surface`, so it must stay opaque even when row colors are translucent.
    val surface = serverRowBackgroundColor.toHappColor().compositeOver(background)
    val surfaceSelected = selectedServerRowColor.toHappColor().compositeOver(background)
    val onSurface = serverRowTitleTextColor.toHappColor()
    val onSurfaceVariant = serverRowSubTitleTextColor.toHappColor()
    val accent = topBarButtonsColor.toHappColor()
    val base = if (isLight) lightColorScheme() else darkColorScheme()

    return base.copy(
        primary = buttonColor,
        onPrimary = buttonTextColor,
        primaryContainer = surfaceSelected,
        onPrimaryContainer = onSurface,
        secondary = accent,
        onSecondary = buttonTextColor,
        secondaryContainer = subsHeaderColor.toHappColor(),
        onSecondaryContainer = onSurface,
        tertiary = supportIconColor.toHappColor(),
        onTertiary = buttonTextColor,
        background = background,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = subsHeaderColor.toHappColor(),
        onSurfaceVariant = onSurfaceVariant,
        outline = onSurfaceVariant,
        surfaceContainer = surface,
        surfaceContainerLow = surface,
        surfaceContainerHigh = surfaceSelected,
        surfaceContainerHighest = surfaceSelected,
        surfaceContainerLowest = background,
    )
}

val LocalHappTheme = compositionLocalOf<HappColorTheme?> { null }

/** Built-in theme presets, ported from the Happ (iOS) theme-code catalog. */
object HappThemeCatalog {

    val builtIn: List<HappColorTheme> = listOf(
        HappColorTheme(
            id = "ios27_glass",
            name = "iOS 27 Glass 🍎",
            backgroundColors = listOf("#EEF6FFFF", "#DCEBFFFF", "#C8DCFFFF"),
            serverRowBackgroundColor = "#FFFFFFCC",
            selectedServerRowColor = "#D2E5FFFF",
            subsHeaderColor = "#E5F0FFFF",
            buttonColor = "#007AFFFF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#007AFFFF",
            serverRowTitleTextColor = "#111827FF",
            serverRowSubTitleTextColor = "#64748BFF",
            topBarButtonsColor = "#007AFFFF",
            supportIconColor = "#007AFFFF",
            subHeaderButtonColor = "#007AFFFF",
            settingsControlsTintColor = "#007AFFFF",
            subscriptionInfoBackgroundColor = "#FFFFFFAA",
            subscriptionTrafficBackgroundColor = "#D8E9FFFF",
            subscriptionInfoTextColor = "#111827FF",
            disclosureHeaderTextColor = "#111827FF",
            disclosureSubHeaderTextColor = "#64748BFF",
            serverRowChevronColor = "#007AFFFF",
            additionalOptionsButtonColor = "#007AFFFF",
            buttonTimerColor = "#FFFFFFFF",
            elipseColors = listOf("#60A5FAFF", "#A5F3FCFF", "#C4B5FDFF"),
        ),
        HappColorTheme(
            id = "ios_fog",
            name = "iOS Fog 🌫",
            backgroundColors = listOf("#E5E7EBFF", "#D1D5DBFF", "#9CA3AFFF"),
            serverRowBackgroundColor = "#F9FAFBFF",
            selectedServerRowColor = "#DDE2E8FF",
            subsHeaderColor = "#EEF0F3FF",
            buttonColor = "#111827FF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#111827FF",
            serverRowTitleTextColor = "#111827FF",
            serverRowSubTitleTextColor = "#6B7280FF",
            topBarButtonsColor = "#111827FF",
            supportIconColor = "#111827FF",
            subHeaderButtonColor = "#111827FF",
            settingsControlsTintColor = "#111827FF",
            subscriptionInfoBackgroundColor = "#FFFFFFAA",
            subscriptionTrafficBackgroundColor = "#D1D5DBFF",
            subscriptionInfoTextColor = "#111827FF",
            disclosureHeaderTextColor = "#111827FF",
            disclosureSubHeaderTextColor = "#6B7280FF",
            serverRowChevronColor = "#111827FF",
            additionalOptionsButtonColor = "#111827FF",
            buttonTimerColor = "#FFFFFFFF",
            elipseColors = listOf("#FFFFFFCC", "#CBD5E1FF", "#94A3B8FF"),
        ),
        HappColorTheme(
            id = "liquid_glass",
            name = "Liquid Glass 🪞",
            backgroundColors = listOf("#F8FAFFFF", "#E0F2FEFF", "#DDD6FEFF"),
            serverRowBackgroundColor = "#FFFFFF88",
            selectedServerRowColor = "#BAE6FD99",
            subsHeaderColor = "#FFFFFF66",
            buttonColor = "#6366F1FF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#6366F1FF",
            serverRowTitleTextColor = "#111827FF",
            serverRowSubTitleTextColor = "#64748BFF",
            topBarButtonsColor = "#6366F1FF",
            supportIconColor = "#06B6D4FF",
            subHeaderButtonColor = "#6366F1FF",
            settingsControlsTintColor = "#6366F1FF",
            subscriptionInfoBackgroundColor = "#FFFFFF77",
            subscriptionTrafficBackgroundColor = "#CFFAFEFF",
            subscriptionInfoTextColor = "#111827FF",
            disclosureHeaderTextColor = "#111827FF",
            disclosureSubHeaderTextColor = "#64748BFF",
            serverRowChevronColor = "#6366F1FF",
            additionalOptionsButtonColor = "#06B6D4FF",
            buttonTimerColor = "#FFFFFFFF",
            elipseColors = listOf("#A5F3FCFF", "#C4B5FDFF", "#FBCFE8FF"),
        ),
        HappColorTheme(
            id = "apple_pro_black",
            name = "Apple Pro Black 🖤",
            backgroundColors = listOf("#000000FF", "#050505FF", "#101010FF"),
            serverRowBackgroundColor = "#090909FF",
            selectedServerRowColor = "#1C1C1EFF",
            subsHeaderColor = "#111111FF",
            buttonColor = "#FFFFFFFF",
            buttonTextColor = "#000000FF",
            powerIconColor = "#FFFFFFFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#8E8E93FF",
            topBarButtonsColor = "#FFFFFFFF",
            supportIconColor = "#0A84FFFF",
            subHeaderButtonColor = "#0A84FFFF",
            settingsControlsTintColor = "#0A84FFFF",
            subscriptionInfoBackgroundColor = "#0A0A0AFF",
            subscriptionTrafficBackgroundColor = "#1C1C1EFF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#8E8E93FF",
            serverRowChevronColor = "#0A84FFFF",
            additionalOptionsButtonColor = "#FFFFFFFF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#0A84FFFF", "#5E5CE6FF", "#FFFFFFFF"),
        ),
        HappColorTheme(
            id = "dynamic_island",
            name = "Dynamic Island 🌌",
            backgroundColors = listOf("#000000FF", "#090014FF", "#16002FFF"),
            serverRowBackgroundColor = "#0C0812FF",
            selectedServerRowColor = "#261447FF",
            subsHeaderColor = "#170B2AFF",
            buttonColor = "#BF5AFFFF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#BF5AFFFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#B8A6CCFF",
            topBarButtonsColor = "#FFFFFFFF",
            supportIconColor = "#BF5AFFFF",
            subHeaderButtonColor = "#BF5AFFFF",
            settingsControlsTintColor = "#BF5AFFFF",
            subscriptionInfoBackgroundColor = "#10081AFF",
            subscriptionTrafficBackgroundColor = "#2A1548FF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#B8A6CCFF",
            serverRowChevronColor = "#BF5AFFFF",
            additionalOptionsButtonColor = "#FFFFFFFF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#BF5AFFFF", "#5E5CE6FF", "#00C7BEFF"),
        ),
        HappColorTheme(
            id = "pixel_material_you",
            name = "Pixel Material You 🤖",
            backgroundColors = listOf("#FDF6FFFF", "#F1E8F5FF", "#E6D7EDFF"),
            serverRowBackgroundColor = "#FFFFFFCC",
            selectedServerRowColor = "#E8DEF8FF",
            subsHeaderColor = "#F7EEF9FF",
            buttonColor = "#6750A4FF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#6750A4FF",
            serverRowTitleTextColor = "#1D1B20FF",
            serverRowSubTitleTextColor = "#79747EFF",
            topBarButtonsColor = "#6750A4FF",
            supportIconColor = "#6750A4FF",
            subHeaderButtonColor = "#6750A4FF",
            settingsControlsTintColor = "#6750A4FF",
            subscriptionInfoBackgroundColor = "#FFFFFFAA",
            subscriptionTrafficBackgroundColor = "#EADDFFFF",
            subscriptionInfoTextColor = "#1D1B20FF",
            disclosureHeaderTextColor = "#1D1B20FF",
            disclosureSubHeaderTextColor = "#79747EFF",
            serverRowChevronColor = "#6750A4FF",
            additionalOptionsButtonColor = "#6750A4FF",
            buttonTimerColor = "#FFFFFFFF",
            elipseColors = listOf("#6750A4FF", "#D0BCFFFF", "#FFD8E4FF"),
        ),
        HappColorTheme(
            id = "one_ui_midnight",
            name = "One UI Midnight 🔵",
            backgroundColors = listOf("#020617FF", "#0F172AFF", "#172554FF"),
            serverRowBackgroundColor = "#111827FF",
            selectedServerRowColor = "#1E3A8AFF",
            subsHeaderColor = "#172554FF",
            buttonColor = "#3B82F6FF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#3B82F6FF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#94A3B8FF",
            topBarButtonsColor = "#60A5FAFF",
            supportIconColor = "#60A5FAFF",
            subHeaderButtonColor = "#3B82F6FF",
            settingsControlsTintColor = "#3B82F6FF",
            subscriptionInfoBackgroundColor = "#0F172AFF",
            subscriptionTrafficBackgroundColor = "#1E40AFFF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#94A3B8FF",
            serverRowChevronColor = "#60A5FAFF",
            additionalOptionsButtonColor = "#60A5FAFF",
            buttonTimerColor = "#FFFFFFFF",
            elipseColors = listOf("#3B82F6FF", "#06B6D4FF", "#2563EBFF"),
        ),
        HappColorTheme(
            id = "android_neon",
            name = "Android Neon 🟣",
            backgroundColors = listOf("#050014FF", "#12002BFF", "#24004FFF"),
            serverRowBackgroundColor = "#100024FF",
            selectedServerRowColor = "#3B0764FF",
            subsHeaderColor = "#2E1065FF",
            buttonColor = "#00FFAAFF",
            buttonTextColor = "#001A12FF",
            powerIconColor = "#00FFAAFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#C4B5FDFF",
            topBarButtonsColor = "#22D3EEFF",
            supportIconColor = "#22D3EEFF",
            subHeaderButtonColor = "#00FFAAFF",
            settingsControlsTintColor = "#00FFAAFF",
            subscriptionInfoBackgroundColor = "#120024FF",
            subscriptionTrafficBackgroundColor = "#4C1D95FF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#C4B5FDFF",
            serverRowChevronColor = "#22D3EEFF",
            additionalOptionsButtonColor = "#22D3EEFF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#00FFAAFF", "#22D3EEFF", "#A855F7FF"),
        ),
        HappColorTheme(
            id = "nothing_os_3",
            name = "Nothing OS 3 🟩",
            backgroundColors = listOf("#000000FF", "#0A0A0AFF", "#151515FF"),
            serverRowBackgroundColor = "#111111FF",
            selectedServerRowColor = "#222222FF",
            subsHeaderColor = "#181818FF",
            buttonColor = "#FFFFFFFF",
            buttonTextColor = "#000000FF",
            powerIconColor = "#FFFFFFFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#8A8A8AFF",
            topBarButtonsColor = "#FFFFFFFF",
            supportIconColor = "#FF003CFF",
            subHeaderButtonColor = "#FFFFFFFF",
            settingsControlsTintColor = "#FFFFFFFF",
            subscriptionInfoBackgroundColor = "#0D0D0DFF",
            subscriptionTrafficBackgroundColor = "#252525FF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#8A8A8AFF",
            serverRowChevronColor = "#FFFFFFFF",
            additionalOptionsButtonColor = "#FF003CFF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#FFFFFFFF", "#FF003CFF", "#555555FF"),
        ),
        HappColorTheme(
            id = "oxygen_os",
            name = "Oxygen OS ⚪",
            backgroundColors = listOf("#F8FAFCFF", "#E2E8F0FF", "#CBD5E1FF"),
            serverRowBackgroundColor = "#FFFFFFFF",
            selectedServerRowColor = "#FFE4E6FF",
            subsHeaderColor = "#F1F5F9FF",
            buttonColor = "#EB0029FF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#EB0029FF",
            serverRowTitleTextColor = "#0F172AFF",
            serverRowSubTitleTextColor = "#64748BFF",
            topBarButtonsColor = "#EB0029FF",
            supportIconColor = "#EB0029FF",
            subHeaderButtonColor = "#EB0029FF",
            settingsControlsTintColor = "#EB0029FF",
            subscriptionInfoBackgroundColor = "#FFFFFFAA",
            subscriptionTrafficBackgroundColor = "#FFE4E6FF",
            subscriptionInfoTextColor = "#0F172AFF",
            disclosureHeaderTextColor = "#0F172AFF",
            disclosureSubHeaderTextColor = "#64748BFF",
            serverRowChevronColor = "#EB0029FF",
            additionalOptionsButtonColor = "#EB0029FF",
            buttonTimerColor = "#FFFFFFFF",
            elipseColors = listOf("#EB0029FF", "#FF6B81FF", "#000000FF"),
        ),
        HappColorTheme(
            id = "neural_ai",
            name = "Neural AI 🧬",
            backgroundColors = listOf("#050014FF", "#10002BFF", "#240046FF"),
            serverRowBackgroundColor = "#120024FF",
            selectedServerRowColor = "#3C096CFF",
            subsHeaderColor = "#240046FF",
            buttonColor = "#00F5FFFF",
            buttonTextColor = "#001414FF",
            powerIconColor = "#00F5FFFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#C4B5FDFF",
            topBarButtonsColor = "#E879F9FF",
            supportIconColor = "#E879F9FF",
            subHeaderButtonColor = "#00F5FFFF",
            settingsControlsTintColor = "#00F5FFFF",
            subscriptionInfoBackgroundColor = "#100020FF",
            subscriptionTrafficBackgroundColor = "#4C1D95FF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#C4B5FDFF",
            serverRowChevronColor = "#00F5FFFF",
            additionalOptionsButtonColor = "#E879F9FF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#00F5FFFF", "#E879F9FF", "#7C3AEDFF"),
        ),
        HappColorTheme(
            id = "mr_robot",
            name = "Mr Robot 🕶",
            backgroundColors = listOf("#000000FF", "#050505FF", "#0A0A0AFF"),
            serverRowBackgroundColor = "#080808FF",
            selectedServerRowColor = "#151515FF",
            subsHeaderColor = "#111111FF",
            buttonColor = "#00FF41FF",
            buttonTextColor = "#000000FF",
            powerIconColor = "#00FF41FF",
            serverRowTitleTextColor = "#00FF41FF",
            serverRowSubTitleTextColor = "#008F11FF",
            topBarButtonsColor = "#00FF41FF",
            supportIconColor = "#00FF41FF",
            subHeaderButtonColor = "#00FF41FF",
            settingsControlsTintColor = "#00FF41FF",
            subscriptionInfoBackgroundColor = "#050505FF",
            subscriptionTrafficBackgroundColor = "#003B00FF",
            subscriptionInfoTextColor = "#00FF41FF",
            disclosureHeaderTextColor = "#00FF41FF",
            disclosureSubHeaderTextColor = "#008F11FF",
            serverRowChevronColor = "#00FF41FF",
            additionalOptionsButtonColor = "#00FF41FF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#00FF41FF", "#008F11FF", "#003B00FF"),
        ),
        HappColorTheme(
            id = "satellite_control",
            name = "Satellite Control 🛰",
            backgroundColors = listOf("#020617FF", "#071A33FF", "#0C355FFF"),
            serverRowBackgroundColor = "#091A2FFF",
            selectedServerRowColor = "#124E78FF",
            subsHeaderColor = "#0F355AFF",
            buttonColor = "#38BDF8FF",
            buttonTextColor = "#001018FF",
            powerIconColor = "#38BDF8FF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#93C5FDFF",
            topBarButtonsColor = "#38BDF8FF",
            supportIconColor = "#38BDF8FF",
            subHeaderButtonColor = "#38BDF8FF",
            settingsControlsTintColor = "#38BDF8FF",
            subscriptionInfoBackgroundColor = "#071426FF",
            subscriptionTrafficBackgroundColor = "#164E63FF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#93C5FDFF",
            serverRowChevronColor = "#38BDF8FF",
            additionalOptionsButtonColor = "#38BDF8FF",
            buttonTimerColor = "#001018FF",
            elipseColors = listOf("#38BDF8FF", "#0EA5E9FF", "#FFFFFFFF"),
        ),
        HappColorTheme(
            id = "alien_interface",
            name = "Alien Interface 🛸",
            backgroundColors = listOf("#020006FF", "#10001FFF", "#1A0033FF"),
            serverRowBackgroundColor = "#0B0614FF",
            selectedServerRowColor = "#35105AFF",
            subsHeaderColor = "#22003FFF",
            buttonColor = "#7CFF00FF",
            buttonTextColor = "#071000FF",
            powerIconColor = "#7CFF00FF",
            serverRowTitleTextColor = "#F0FFE8FF",
            serverRowSubTitleTextColor = "#A6C98AFF",
            topBarButtonsColor = "#7CFF00FF",
            supportIconColor = "#C026D3FF",
            subHeaderButtonColor = "#7CFF00FF",
            settingsControlsTintColor = "#7CFF00FF",
            subscriptionInfoBackgroundColor = "#10001FFF",
            subscriptionTrafficBackgroundColor = "#3B0764FF",
            subscriptionInfoTextColor = "#F0FFE8FF",
            disclosureHeaderTextColor = "#F0FFE8FF",
            disclosureSubHeaderTextColor = "#A6C98AFF",
            serverRowChevronColor = "#7CFF00FF",
            additionalOptionsButtonColor = "#C026D3FF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#7CFF00FF", "#C026D3FF", "#00FFFFFF"),
        ),
        HappColorTheme(
            id = "quantum_core",
            name = "Quantum Core ⚡",
            backgroundColors = listOf("#020617FF", "#0F172AFF", "#1E1B4BFF"),
            serverRowBackgroundColor = "#111827FF",
            selectedServerRowColor = "#312E81FF",
            subsHeaderColor = "#1E1B4BFF",
            buttonColor = "#22D3EEFF",
            buttonTextColor = "#001018FF",
            powerIconColor = "#22D3EEFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#A5B4FCFF",
            topBarButtonsColor = "#A78BFAFF",
            supportIconColor = "#22D3EEFF",
            subHeaderButtonColor = "#22D3EEFF",
            settingsControlsTintColor = "#22D3EEFF",
            subscriptionInfoBackgroundColor = "#0B1220FF",
            subscriptionTrafficBackgroundColor = "#312E81FF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#A5B4FCFF",
            serverRowChevronColor = "#22D3EEFF",
            additionalOptionsButtonColor = "#A78BFAFF",
            buttonTimerColor = "#001018FF",
            elipseColors = listOf("#22D3EEFF", "#A78BFAFF", "#F472B6FF"),
        ),
        HappColorTheme(
            id = "roblox_studio",
            name = "Roblox Studio 🎮",
            backgroundColors = listOf("#1E1E1EFF", "#252526FF", "#333333FF"),
            serverRowBackgroundColor = "#252526FF",
            selectedServerRowColor = "#094771FF",
            subsHeaderColor = "#2D2D30FF",
            buttonColor = "#00A2FFFF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#00A2FFFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#A0A0A0FF",
            topBarButtonsColor = "#FFFFFFFF",
            supportIconColor = "#00A2FFFF",
            subHeaderButtonColor = "#00A2FFFF",
            settingsControlsTintColor = "#00A2FFFF",
            subscriptionInfoBackgroundColor = "#202020FF",
            subscriptionTrafficBackgroundColor = "#094771FF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#A0A0A0FF",
            serverRowChevronColor = "#00A2FFFF",
            additionalOptionsButtonColor = "#FFFFFFFF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#00A2FFFF", "#FFFFFFFF", "#555555FF"),
        ),
        HappColorTheme(
            id = "playstation_hud",
            name = "PlayStation HUD 🕹",
            backgroundColors = listOf("#020617FF", "#07152EFF", "#102A56FF"),
            serverRowBackgroundColor = "#0B1833FF",
            selectedServerRowColor = "#1D4ED8FF",
            subsHeaderColor = "#12306AFF",
            buttonColor = "#0070CCFF",
            buttonTextColor = "#FFFFFFFF",
            powerIconColor = "#00A8FFFF",
            serverRowTitleTextColor = "#FFFFFFFF",
            serverRowSubTitleTextColor = "#93C5FDFF",
            topBarButtonsColor = "#00A8FFFF",
            supportIconColor = "#00A8FFFF",
            subHeaderButtonColor = "#0070CCFF",
            settingsControlsTintColor = "#00A8FFFF",
            subscriptionInfoBackgroundColor = "#09152CFF",
            subscriptionTrafficBackgroundColor = "#123B7AFF",
            subscriptionInfoTextColor = "#FFFFFFFF",
            disclosureHeaderTextColor = "#FFFFFFFF",
            disclosureSubHeaderTextColor = "#93C5FDFF",
            serverRowChevronColor = "#00A8FFFF",
            additionalOptionsButtonColor = "#00A8FFFF",
            buttonTimerColor = "#000000FF",
            elipseColors = listOf("#00A8FFFF", "#0070CCFF", "#FFFFFFFF"),
        ),
    )

    fun findById(id: String?): HappColorTheme? = builtIn.find { it.id == id }
}

/** Persists and exposes the currently active Happ-style color theme, if any. */
object HappThemeManager {
    private const val DEFAULT_THEME_ID = "ios27_glass"
    private val gson = Gson()

    private fun loadInitial(): HappColorTheme? {
        val customJson = MmkvManager.decodeSettingsString(AppConfig.PREF_HAPP_THEME_CUSTOM_JSON, null)
        if (!customJson.isNullOrBlank()) {
            return runCatching { gson.fromJson(customJson, HappColorThemeJson::class.java)?.toHappColorTheme() }
                .getOrNull()
        }
        // Unset (first launch) means the default theme; an explicit "" means the user chose none.
        val id = MmkvManager.decodeSettingsString(AppConfig.PREF_HAPP_THEME_ID, null) ?: DEFAULT_THEME_ID
        return HappThemeCatalog.findById(id)
    }

    private val _selected = MutableStateFlow(loadInitial())
    val selected: StateFlow<HappColorTheme?> = _selected.asStateFlow()

    fun selectBuiltIn(id: String) {
        val theme = HappThemeCatalog.findById(id) ?: return
        MmkvManager.encodeSettings(AppConfig.PREF_HAPP_THEME_ID, id)
        MmkvManager.encodeSettings(AppConfig.PREF_HAPP_THEME_CUSTOM_JSON, "")
        _selected.value = theme
    }

    /** Parses a pasted Happ "theme code" (JSON) and applies it as a custom theme. */
    fun applyCustomCode(code: String): Boolean {
        val parsed = try {
            gson.fromJson(code.trim(), HappColorThemeJson::class.java)
        } catch (e: JsonSyntaxException) {
            null
        } ?: return false

        if (parsed.backgroundColors.isNullOrEmpty() && parsed.buttonColor.isNullOrBlank()) return false

        val theme = parsed.toHappColorTheme().copy(id = "custom")
        MmkvManager.encodeSettings(AppConfig.PREF_HAPP_THEME_ID, "")
        MmkvManager.encodeSettings(AppConfig.PREF_HAPP_THEME_CUSTOM_JSON, code.trim())
        _selected.value = theme
        return true
    }

    fun clear() {
        MmkvManager.encodeSettings(AppConfig.PREF_HAPP_THEME_ID, "")
        MmkvManager.encodeSettings(AppConfig.PREF_HAPP_THEME_CUSTOM_JSON, "")
        _selected.value = null
    }
}

@Composable
private fun HappThemeSwatch(
    theme: HappColorTheme?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dots = theme?.backgroundColors?.take(3)?.map { it.toHappColor() }
        ?: listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)

    Column(
        modifier = modifier
            .width(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) Modifier.border(
                    BorderStroke(2.dp, MaterialTheme.colorScheme.secondary),
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(40.dp)) {
            dots.forEachIndexed { index, color ->
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.size(40.dp - (index * 8).dp)
                ) {
                    drawCircle(color = color)
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Horizontal picker for the built-in Happ-style color themes, plus a "none" option. */
@Composable
fun HappThemePicker(
    selectedId: String?,
    onSelectNone: () -> Unit,
    onSelectTheme: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            HappThemeSwatch(
                theme = null,
                label = "—",
                selected = selectedId.isNullOrEmpty(),
                onClick = onSelectNone
            )
        }
        items(HappThemeCatalog.builtIn) { theme ->
            HappThemeSwatch(
                theme = theme,
                label = theme.name,
                selected = theme.id == selectedId,
                onClick = { onSelectTheme(theme.id) }
            )
        }
    }
}
