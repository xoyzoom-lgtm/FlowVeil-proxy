package com.v2ray.ang.ui.main.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.net.HomeCard
import com.v2ray.ang.ui.compose.HomeTokens
import com.v2ray.ang.ui.main.ServerRowUiModel
import com.v2ray.ang.ui.main.mainAccentColor
import com.v2ray.ang.ui.main.serverCardColor
import com.v2ray.ang.ui.main.splitFlag

@Composable
internal fun homeAccent(): Color = mainAccentColor()

@Composable
internal fun homeSurface(selected: Boolean = false): Color = serverCardColor(selected)

/** 46dp round button of the server section. */
@Composable
internal fun RoundAction(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    spinning: Boolean = false,
    pulsing: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 46.dp,
) {
    val accent = homeAccent()
    val transition = rememberInfiniteTransition(label = "roundAction")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin"
    )
    val pulse by transition.animateFloat(
        1f, 0.45f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse"
    )
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.18f) else homeSurface())
            .border(1.dp, if (active) accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = label
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(iconRes), null,
            tint = if (active || spinning || pulsing) accent else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .size(size * 0.46f)
                .graphicsLayer {
                    if (spinning) rotationZ = angle
                    if (pulsing) alpha = pulse
                }
        )
    }
}

@Composable
internal fun ServersHeader(
    title: String,
    searchOn: Boolean,
    refreshing: Boolean,
    testing: Boolean,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
    onPing: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 10.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundAction(R.drawable.ic_search_24dp, stringResource(R.string.home_search), onSearch, active = searchOn)
            RoundAction(R.drawable.ic_refresh_24dp, stringResource(R.string.home_refresh), onRefresh, spinning = refreshing)
            RoundAction(R.drawable.ic_flash_on_24dp, stringResource(R.string.home_ping_all), onPing, pulsing = testing)
        }
    }
}

@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(homeSurface())
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(painterResource(R.drawable.ic_search_24dp), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.home_search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(homeAccent()),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
            )
        }
    }
}

@Composable
private fun FlagTile(flag: String?, name: String, accent: Color) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        if (flag != null) {
            Text(flag, fontSize = 22.sp)
        } else {
            Text(name.trim().take(1).uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
    }
}

@Composable
internal fun PingTag(delayMillis: Long, alive: Boolean?, availabilityOnly: Boolean, best: Boolean) {
    val level = HomeCard.ping(delayMillis)
    val dead = delayMillis < 0L || (delayMillis == 0L && alive == false)
    val aliveOnly = (delayMillis > 0L && availabilityOnly) || (delayMillis == 0L && alive == true)
    val color = when {
        dead -> HomeTokens.bad
        aliveOnly -> HomeTokens.good
        level == HomeCard.Ping.GOOD -> homeAccent()
        level == HomeCard.Ping.MEDIUM -> HomeTokens.warn
        level == HomeCard.Ping.BAD -> HomeTokens.bad
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = when {
        dead -> "—"
        aliveOnly -> "✓"
        delayMillis > 0L -> stringResource(R.string.server_test_delay_value, delayMillis)
        else -> return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .then(if (best) Modifier.border(1.dp, color, RoundedCornerShape(50)) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (best) Text("★ ", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun SkeletonPill() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val a by transition.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "skeletonAlpha")
    Box(
        Modifier
            .width(54.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a * 0.4f))
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ServerRowV4(
    row: ServerRowUiModel,
    selected: Boolean,
    best: Boolean,
    alive: Boolean?,
    availabilityOnly: Boolean,
    testing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val accent = homeAccent()
    val (flag, name) = remember(row.remarks) { splitFlag(row.remarks) }
    val gaming = remember(row.remarks) { HomeCard.isGaming(row.remarks) }
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(
                if (selected) Brush.horizontalGradient(listOf(accent.copy(alpha = 0.20f), homeSurface()))
                else SolidColor(homeSurface())
            )
            .border(1.5.dp, if (selected) accent else Color.Transparent, shape)
            .semantics { this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        FlagTile(flag, name, accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (row.isFavorite) "★ $name" else name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (gaming) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.home_gaming) + " 🔥",
                        color = HomeTokens.warn,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(HomeTokens.warn.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                row.typeDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        if (testing && row.testDelayMillis == 0L && alive == null) {
            SkeletonPill()
        } else {
            PingTag(row.testDelayMillis, alive, availabilityOnly, best)
        }
    }
}

@Composable
internal fun AutoBestRow(bestName: String?, bestDelay: Long, onClick: () -> Unit) {
    val accent = homeAccent()
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(homeSurface())
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(R.drawable.ic_flash_on_24dp), null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.home_auto_best),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            if (bestName != null) {
                Text(
                    bestName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (bestDelay > 0L) {
            Spacer(Modifier.width(8.dp))
            PingTag(bestDelay, null, false, false)
        }
    }
}
