package com.v2ray.ang.ui.main.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.ui.compose.HomeTokens
import com.v2ray.ang.ui.main.mainOnAccentColor

/** What the bottom panel shows; worked out by the screen from the view model state. */
internal enum class DockState { IDLE, CONNECTING, CONNECTED, ERROR, BLOCKED }

/**
 * Fixed bottom panel: the chosen server, the connection state and the one main button.
 * [title] and [subtitle] are already localised by the caller.
 */
@Composable
internal fun ConnectDock(
    state: DockState,
    title: String,
    subtitle: String,
    buttonText: String,
    onButton: () -> Unit,
    onBody: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = homeAccent()
    val onAccent = mainOnAccentColor()
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(30.dp)
    val border by animateColorAsState(
        when (state) {
            DockState.CONNECTED -> accent
            DockState.ERROR -> HomeTokens.bad
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        },
        label = "dockBorder"
    )
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .shadow(if (state == DockState.CONNECTED) 18.dp else 10.dp, shape, ambientColor = accent, spotColor = if (state == DockState.CONNECTED) accent else surface)
            .clip(shape)
            .background(surface.copy(alpha = if (HomeStyle.glass) 0.82f else 1f))
            .border(if (state == DockState.CONNECTED || state == DockState.ERROR) 1.5.dp else 1.dp, border, shape)
            .clickable(onClick = onBody)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (state == DockState.ERROR) HomeTokens.bad else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == DockState.CONNECTED) {
                    PulsingDot(accent, 7.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        val stop = state == DockState.CONNECTED || state == DockState.CONNECTING
        val enabled = state != DockState.BLOCKED
        val bg = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
            state == DockState.ERROR -> HomeTokens.bad
            stop -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> accent
        }
        val fg = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            state == DockState.ERROR -> HomeTokens.onCard
            stop -> MaterialTheme.colorScheme.onSurface
            else -> onAccent
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .heightIn(min = 52.dp)
                .widthIn(min = 128.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(bg)
                .semantics { role = Role.Button }
                .clickable(enabled = enabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onButton()
                }
                .padding(horizontal = 20.dp)
        ) {
            Text(buttonText, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
        }
    }
}

/** Thin strip above the list: update offer and similar one-line notices. */
@Composable
internal fun HomeBanner(text: String, action: String, onAction: () -> Unit, dismiss: String, onDismiss: () -> Unit) {
    val accent = homeAccent()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            dismiss, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onDismiss).padding(10.dp)
        )
        Text(
            action, color = accent, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onAction).padding(10.dp)
        )
    }
}

@Composable
internal fun DockSpacer() = Spacer(Modifier.height(112.dp))
