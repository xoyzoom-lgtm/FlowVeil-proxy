package com.v2ray.ang.ui.main.home

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.net.ThemeBuilder
import com.v2ray.ang.ui.compose.HappThemeManager
import com.v2ray.ang.util.Utils

private fun colorOf(hex: String): Color = ThemeBuilder.hex6(hex)?.let { Color(0xFF000000.toInt() or it.toInt(16)) } ?: Color.Gray

/**
 * Full customisation: five colours (background top and bottom, accent, cards, text) that are turned into a theme at once,
 * so every change is visible straight away. A theme can be copied as a code and pasted back (also from another phone).
 */
@Composable
internal fun ThemeEditor() {
    val context = LocalContext.current
    val start = remember { HappThemeManager.selected.value }
    fun first(hex: String?, fallback: String) = ThemeBuilder.hex6(hex) ?: fallback
    var top by remember { mutableStateOf(first(start?.backgroundColors?.firstOrNull(), "0B0E14")) }
    var bottom by remember { mutableStateOf(first(start?.backgroundColors?.getOrNull(1) ?: start?.backgroundColors?.firstOrNull(), "151A23")) }
    var accent by remember { mutableStateOf(first(start?.buttonColor, "5FF0C4")) }
    var card by remember { mutableStateOf(first(start?.serverRowBackgroundColor, "1C2330")) }
    var text by remember { mutableStateOf(first(start?.serverRowTitleTextColor, "F2F5FA")) }
    val colors = ThemeBuilder.Colors(top, bottom, accent, card, text)

    fun apply(next: ThemeBuilder.Colors) {
        ThemeBuilder.build(next)?.let { HappThemeManager.applyCustomCode(it) }
    }

    Column {
        ColorField(stringResource(R.string.te_bg_top), top) { top = it; apply(colors.copy(backgroundTop = it)) }
        ColorField(stringResource(R.string.te_bg_bottom), bottom) { bottom = it; apply(colors.copy(backgroundBottom = it)) }
        ColorField(stringResource(R.string.te_accent), accent) { accent = it; apply(colors.copy(accent = it)) }
        ColorField(stringResource(R.string.te_card), card) { card = it; apply(colors.copy(card = it)) }
        ColorField(stringResource(R.string.te_text), text) { text = it; apply(colors.copy(text = it)) }
        if (!ThemeBuilder.readable(colors)) {
            Text(stringResource(R.string.te_unreadable), color = com.v2ray.ang.ui.compose.HomeTokens.warn, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
        Spacer(Modifier.size(8.dp))
        SheetRow(R.drawable.ic_copy, stringResource(R.string.te_copy), stringResource(R.string.te_copy_sub)) {
            ThemeBuilder.build(colors)?.let {
                Utils.setClipboard(context, it)
                context.toastSuccess(R.string.toast_success)
            }
        }
        SheetRow(R.drawable.ic_description_24dp, stringResource(R.string.te_paste), stringResource(R.string.te_paste_sub)) {
            val code = runCatching { Utils.getClipboard(context) }.getOrDefault("")
            if (code.isNotBlank() && HappThemeManager.applyCustomCode(code)) {
                context.toastSuccess(R.string.toast_success)
            } else {
                context.toastError(R.string.te_paste_failed)
            }
        }
    }
}

@Composable
private fun ColorField(label: String, hex: String, onHex: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var field by remember(hex) { mutableStateOf(hex) }
    val hsv = remember(hex) { FloatArray(3).also { AndroidColor.colorToHSV(colorOf(hex).toArgb(), it) } }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(homeSurface())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable { open = !open }.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(colorOf(hex)).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), CircleShape))
            Spacer(Modifier.width(12.dp))
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            BasicTextField(
                value = field,
                onValueChange = { v ->
                    field = v.take(8).uppercase()
                    ThemeBuilder.hex6(field)?.takeIf { field.length == 6 }?.let(onHex)
                },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(homeAccent()),
                modifier = Modifier.width(78.dp)
            )
        }
        if (open) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                fun change(h: Float = hsv[0], s: Float = hsv[1], v: Float = hsv[2]) {
                    val argb = AndroidColor.HSVToColor(floatArrayOf(h, s, v))
                    onHex(String.format("%06X", argb and 0xFFFFFF))
                }
                Slider(value = hsv[0], onValueChange = { change(h = it) }, valueRange = 0f..360f)
                Slider(value = hsv[1], onValueChange = { change(s = it) }, valueRange = 0f..1f)
                Slider(value = hsv[2], onValueChange = { change(v = it) }, valueRange = 0f..1f)
            }
        }
    }
}

/** Fine tuning of the look: how clear the glass is, how strong the blur, how round the corners, how bright the background blobs. */
@Composable
internal fun StyleTuning() {
    @Composable
    fun Row2(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(homeSurface()).padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(shown, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
            Slider(value = value, onValueChange = onChange, valueRange = range)
        }
    }
    Row2(stringResource(R.string.tune_alpha), HomeStyle.glassAlpha, 0.30f..0.95f, "${(HomeStyle.glassAlpha * 100).toInt()}%") { HomeStyle.tuneAlpha(it) }
    Row2(stringResource(R.string.tune_blur), HomeStyle.blurDp, 8f..40f, "${HomeStyle.blurDp.toInt()}") { HomeStyle.tuneBlur(it) }
    Row2(stringResource(R.string.tune_corner), HomeStyle.corner, 0.5f..1.5f, "${(HomeStyle.corner * 100).toInt()}%") { HomeStyle.tuneCorner(it) }
    Row2(stringResource(R.string.tune_blobs), HomeStyle.blobs, 0f..1.5f, "${(HomeStyle.blobs * 100).toInt()}%") { HomeStyle.tuneBlobs(it) }
    SheetRow(R.drawable.ic_restore_24dp, stringResource(R.string.tune_reset), danger = true) { HomeStyle.resetTuning() }
}
