package com.v2ray.ang.ui.main.home

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.v2ray.ang.ui.compose.backgroundBrush
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager

/**
 * Look of the new screens, chosen by the user in Settings → Appearance:
 *  - GLASS: translucent surfaces over the themed background; sheets ask the system to blur behind them (Android 12+, if the phone allows);
 *  - BLUR: translucent surfaces, and the whole home screen is blurred while a sheet or a full-screen layer is open (Android 12+; older phones get a plain dim);
 *  - SOLID: plain opaque surfaces, nothing shows through.
 */
object HomeStyle {
    enum class Mode(val key: String) { GLASS("glass"), BLUR("blur"), SOLID("solid") }

    private fun load(): Mode {
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_STYLE)?.let { stored -> Mode.entries.firstOrNull { it.key == stored }?.let { return it } }
        // Older installs only knew the glass / solid switch; new ones start with the blur where the phone can do it.
        return when {
            !MmkvManager.decodeSettingsBool(AppConfig.PREF_GLASS, true) -> Mode.SOLID
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Mode.BLUR
            else -> Mode.GLASS
        }
    }

    var mode by mutableStateOf(load())
        private set

    fun choose(next: Mode) {
        mode = next
        MmkvManager.encodeSettings(AppConfig.PREF_UI_STYLE, next.key)
    }

    /** Surfaces let the background shine through (glass and blur). */
    val glass: Boolean get() = mode != Mode.SOLID

    /** Opacity of cards, rows and the bottom panel. */
    val surfaceAlpha: Float get() = if (glass) 0.74f else 1f
}

/** Blur behind the window of a sheet (glass look only). The system may refuse it; then the sheet is simply translucent. */
@Composable
internal fun BlurBehindDialog() {
    if (HomeStyle.mode != HomeStyle.Mode.GLASS || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    val window = ((view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider))?.window ?: return
    runCatching {
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = 28 }
    }
}

/** Opaque base for full-screen layers that slide over the home screen (settings, subscriptions): the screen below must not show through. */
@Composable
internal fun Modifier.screenBase(): Modifier {
    val happ = com.v2ray.ang.ui.compose.LocalHappTheme.current
    val fallback = androidx.compose.material3.MaterialTheme.colorScheme.background
    // In the blur style the (blurred) home screen shows through; otherwise the layer is fully opaque.
    val veil = if (HomeStyle.mode == HomeStyle.Mode.BLUR) 0.62f else 1f
    return if (happ != null) {
        this.background(fallback.copy(alpha = veil)).background(happ.backgroundBrush(), alpha = veil)
    } else {
        this.background(fallback.copy(alpha = veil))
    }
}
