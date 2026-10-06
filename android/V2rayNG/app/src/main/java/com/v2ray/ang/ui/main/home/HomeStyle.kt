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
 * Look of the new screens, chosen by the user in Settings → Look:
 *  - glass: surfaces are translucent over the themed background, sheets blur what is behind them (Android 12+ when the phone allows it);
 *  - solid: plain opaque surfaces, no blur.
 */
object HomeStyle {
    var glass by mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_GLASS, true))
        private set

    fun chooseGlass(on: Boolean) {
        glass = on
        MmkvManager.encodeSettings(AppConfig.PREF_GLASS, on)
    }

    /** Opacity of cards, rows and the bottom panel. */
    val surfaceAlpha: Float get() = if (glass) 0.74f else 1f
}

/** Blur behind the window of a sheet (glass look only). The system may refuse it; then the sheet is simply translucent. */
@Composable
internal fun BlurBehindDialog() {
    if (!HomeStyle.glass || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
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
    return if (happ != null) {
        // The theme's own gradient, over a solid colour in case the gradient has transparent stops.
        this.background(fallback).background(happ.backgroundBrush())
    } else {
        this.background(fallback)
    }
}
