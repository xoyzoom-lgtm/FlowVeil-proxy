package com.v2ray.ang.ui.main.home

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
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

    // Fine tuning ("Full customisation"): kept as plain numbers in the settings storage.
    private fun load(key: String, default: Float, range: ClosedFloatingPointRange<Float>): Float =
        (MmkvManager.decodeSettingsString(key)?.toFloatOrNull() ?: default).coerceIn(range)

    private const val KEY_ALPHA = "pref_ui_glass_alpha"
    private const val KEY_BLUR = "pref_ui_blur_dp"
    private const val KEY_CORNER = "pref_ui_corner"
    private const val KEY_BLOBS = "pref_ui_blobs"

    /** How much the surfaces let through, 0.30 (very clear) … 0.95 (almost solid). */
    var glassAlpha by mutableStateOf(load(KEY_ALPHA, 0.62f, 0.30f..0.95f))
        private set
    /** Blur radius under sheets and layers, in dp. */
    var blurDp by mutableStateOf(load(KEY_BLUR, 22f, 8f..40f))
        private set
    /** 1.0 = the designed corners; smaller is sharper, larger is rounder. */
    var corner by mutableStateOf(load(KEY_CORNER, 1f, 0.5f..1.5f))
        private set
    /** Brightness of the soft colour blobs in the background, 0 = none. */
    var blobs by mutableStateOf(load(KEY_BLOBS, 1f, 0f..1.5f))
        private set

    fun tuneAlpha(v: Float) { glassAlpha = v.coerceIn(0.30f, 0.95f); MmkvManager.encodeSettings(KEY_ALPHA, glassAlpha.toString()) }
    fun tuneBlur(v: Float) { blurDp = v.coerceIn(8f, 40f); MmkvManager.encodeSettings(KEY_BLUR, blurDp.toString()) }
    fun tuneCorner(v: Float) { corner = v.coerceIn(0.5f, 1.5f); MmkvManager.encodeSettings(KEY_CORNER, corner.toString()) }
    fun tuneBlobs(v: Float) { blobs = v.coerceIn(0f, 1.5f); MmkvManager.encodeSettings(KEY_BLOBS, blobs.toString()) }

    fun resetTuning() { tuneAlpha(0.62f); tuneBlur(22f); tuneCorner(1f); tuneBlobs(1f) }

    /** Opacity of cards, rows and the bottom panel. */
    val surfaceAlpha: Float get() = if (glass) glassAlpha else 1f

    /** A designed corner radius of [dp] scaled by the user's choice. */
    fun r(dp: Int): androidx.compose.ui.unit.Dp = (dp * corner).dp
}

/** The light edge of glass: a thin bright line, stronger on the top-left like light falling on a pane. Nothing on the solid style. */
@Composable
internal fun Modifier.glassEdge(shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (HomeStyle.glass) this.border(
        1.dp,
        androidx.compose.ui.graphics.Brush.linearGradient(listOf(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.26f), androidx.compose.ui.graphics.Color.White.copy(alpha = 0.04f))),
        shape
    ) else this

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
internal fun Modifier.screenBase(solid: Boolean = false): Modifier {
    val happ = com.v2ray.ang.ui.compose.LocalHappTheme.current
    val fallback = androidx.compose.material3.MaterialTheme.colorScheme.background
    // In the blur style the (blurred) home screen shows through; otherwise the layer is fully opaque.
    val veil = if (HomeStyle.mode == HomeStyle.Mode.BLUR && !solid) 0.62f else 1f
    return if (happ != null) {
        this.background(fallback.copy(alpha = veil)).background(happ.backgroundBrush(), alpha = veil)
    } else {
        this.background(fallback.copy(alpha = veil))
    }
}
