package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.LocalHappTheme
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.toHappColor
import kotlinx.coroutines.delay

private val PingGood = Color(0xFF22C55E)
private val PingMedium = Color(0xFFF59E0B)
internal val PingBad = Color(0xFFEF4444)

@Composable
internal fun mainAccentColor(): Color =
    LocalHappTheme.current?.powerIconColor?.toHappColor() ?: colorFabActive

@Composable
internal fun mainOnAccentColor(): Color =
    LocalHappTheme.current?.buttonTextColor?.toHappColor() ?: Color.White

@Composable
internal fun serverCardColor(selected: Boolean): Color {
    val happ = LocalHappTheme.current
    val scheme = MaterialTheme.colorScheme
    return if (happ != null) {
        (if (selected) happ.selectedServerRowColor else happ.serverRowBackgroundColor).toHappColor()
    } else if (selected) {
        mainAccentColor().copy(alpha = 0.16f).compositeOver(scheme.surfaceContainerHigh)
    } else {
        scheme.surfaceContainerHigh.copy(alpha = 0.85f)
    }
}

/** Full-screen backdrop: theme gradient plus softly drifting colour blobs. */
@Composable
internal fun MainBackground(modifier: Modifier = Modifier) {
    val happ = LocalHappTheme.current
    val scheme = MaterialTheme.colorScheme
    val accent = mainAccentColor()
    val blobColors = happ?.elipseColors?.take(3)?.map { it.toHappColor() }?.takeIf { it.isNotEmpty() }
        ?: listOf(accent, scheme.tertiary, scheme.secondary)
    val baseBrush = if (happ == null) {
        Brush.verticalGradient(listOf(scheme.background, scheme.surfaceContainerLow))
    } else {
        null
    }

    // Static on purpose: an animated full-screen canvas redraws behind the list every frame and stutters scrolling.
    val drift = 0.5f

    Canvas(modifier = modifier.fillMaxSize()) {
        if (baseBrush != null) drawRect(baseBrush)
        val w = size.width
        val h = size.height
        val spots = listOf(
            Triple(Offset(w * (0.10f + 0.10f * drift), h * 0.08f), w * 0.70f, 0.30f),
            Triple(Offset(w * (0.95f - 0.12f * drift), h * (0.22f + 0.05f * drift)), w * 0.60f, 0.24f),
            Triple(Offset(w * (0.35f + 0.15f * drift), h * 0.55f), w * 0.75f, 0.14f),
        )
        spots.forEachIndexed { index, (center, radius, alpha) ->
            val color = blobColors[index % blobColors.size]
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

@Composable
internal fun PowerButton(
    isRunning: Boolean,
    connectedSince: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 176.dp,
) {
    val accent = mainAccentColor()
    val onAccent = mainOnAccentColor()
    val idleFill = MaterialTheme.colorScheme.surfaceContainerHigh
    val fill by animateColorAsState(if (isRunning) accent else idleFill, label = "powerFill")
    val contentColor by animateColorAsState(if (isRunning) onAccent else accent, label = "powerContent")
    val label = stringResource(if (isRunning) R.string.acc_stop else R.string.acc_start)
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "powerPress")

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning, connectedSince) {
        while (isRunning) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Box(
        modifier = modifier.size(buttonSize * 1.25f),
        contentAlignment = Alignment.Center
    ) {
        if (isRunning) {
            PulseRings(accent = accent, buttonSize = buttonSize)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(color = accent.copy(alpha = 0.08f), radius = buttonSize.toPx() / 2f * 1.16f)
            }
        }
        Column(
            modifier = Modifier
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .clip(CircleShape)
                .background(fill)
                .border(BorderStroke(1.5.dp, accent.copy(alpha = if (isRunning) 0f else 0.45f)), CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                }
                .clickable(interactionSource = interactionSource, indication = null) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            PowerGlyph(color = contentColor, modifier = Modifier.size(buttonSize * 0.26f))
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(if (isRunning) R.string.main_power_connected else R.string.main_power_connect),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = contentColor.copy(alpha = 0.85f)
            )
            if (isRunning && connectedSince > 0L) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatElapsed(now - connectedSince),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.11f
        val inset = stroke / 2f
        val diameter = w * 0.86f
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset((w - diameter) / 2f, h - diameter - inset),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(w / 2f, inset),
            end = Offset(w / 2f, h * 0.48f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Server status pill. Step 1 of a check shows only "works" (check) or "doesn't work" (cross);
 * once real ping is measured the number is added. [alive] keeps the step-1 verdict while ping is pending.
 */
@Composable
internal fun PingPill(
    delayMillis: Long,
    modifier: Modifier = Modifier,
    availabilityOnly: Boolean = false,
    alive: Boolean? = null,
) {
    val dead = delayMillis < 0L || (delayMillis == 0L && alive == false)
    val aliveOnly = (delayMillis > 0L && availabilityOnly) || (delayMillis == 0L && alive == true)
    if (dead || aliveOnly) {
        val color = if (dead) PingBad else PingGood
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.15f))
                .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(16.dp).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (dead) R.drawable.ic_status_cross else R.drawable.ic_action_done),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(if (dead) R.string.server_status_dead else R.string.server_status_alive),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1
            )
        }
        return
    }
    if (delayMillis == 0L) return
    val color = when {
        delayMillis < 300L -> PingGood
        delayMillis < 800L -> PingMedium
        else -> PingBad
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_action_done),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = stringResource(R.string.server_test_delay_value, delayMillis),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1
        )
    }
}

/** Soft round icon button used across the main screen. */
@Composable
internal fun CircleIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(serverCardColor(selected = false))
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

private fun isRegionalIndicator(codePoint: Int) = codePoint in 0x1F1E6..0x1F1FF

/** Splits a leading country-flag emoji (two regional indicators) off a server name. */
internal fun splitFlag(remarks: String): Pair<String?, String> {
    val trimmed = remarks.trimStart()
    if (trimmed.isEmpty()) return null to remarks
    val first = trimmed.codePointAt(0)
    if (!isRegionalIndicator(first)) return null to remarks
    val secondIndex = Character.charCount(first)
    if (secondIndex >= trimmed.length) return null to remarks
    val second = trimmed.codePointAt(secondIndex)
    if (!isRegionalIndicator(second)) return null to remarks
    val end = secondIndex + Character.charCount(second)
    val rest = trimmed.substring(end).trimStart(' ', '|', '-', '·', ':')
    return trimmed.substring(0, end) to rest.ifBlank { trimmed.substring(0, end) }
}

@Composable
internal fun ServerBadge(remarks: String, flag: String?, modifier: Modifier = Modifier) {
    val accent = mainAccentColor()
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (flag != null) Color.Transparent else accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        if (flag != null) {
            Text(text = flag, fontSize = 26.sp)
        } else {
            Text(
                text = remarks.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accent
            )
        }
    }
}

@Composable
internal fun ConnectionHero(
    isRunning: Boolean,
    connectedSince: Long,
    statusText: String?,
    selectedRow: ServerRowUiModel?,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = 176.dp,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PowerButton(
            isRunning = isRunning,
            connectedSince = connectedSince,
            onClick = onToggle,
            buttonSize = buttonSize
        )
        if (selectedRow != null) {
            val (flag, name) = splitFlag(selectedRow.remarks)
            Text(
                text = if (flag != null && flag != name) "$flag  $name" else name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = statusText ?: stringResource(R.string.main_check_connection),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(50))
                .background(serverCardColor(selected = false))
                .clickable(onClick = onTest)
                .padding(horizontal = 16.dp, vertical = 7.dp)
        )
    }
}

@Composable
internal fun EmptyServersState(
    onPaste: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = mainAccentColor()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(serverCardColor(selected = false))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.main_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.main_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onPaste,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = mainOnAccentColor())
        ) {
            Icon(painterResource(R.drawable.ic_add_24dp), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.main_paste_subscription), fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
        ) {
            Text(stringResource(R.string.main_scan_qr), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Pulsing rings around the power button; composed only while connected so idle screens don't redraw every frame. */
@Composable
private fun PulseRings(accent: Color, buttonSize: Dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseProgress"
    )
    Canvas(Modifier.fillMaxSize()) {
        val base = buttonSize.toPx() / 2f
        for (i in 0..1) {
            val t = (pulse + i * 0.5f) % 1f
            drawCircle(color = accent.copy(alpha = 0.30f * (1f - t)), radius = base * (1f + 0.38f * t))
        }
    }
}
