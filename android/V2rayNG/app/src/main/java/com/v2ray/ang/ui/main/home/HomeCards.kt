package com.v2ray.ang.ui.main.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.net.HomeCard
import com.v2ray.ang.net.ShortName
import com.v2ray.ang.ui.compose.HomeTokens
import com.v2ray.ang.ui.main.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

/** Full name of a group as the provider sent it; the built-in group gets a friendly name. */
@Composable
internal fun groupFullName(group: GroupMapItem): String =
    group.subscription?.profileTitle?.takeIf { it.isNotBlank() }
        ?: group.subscription?.remarks?.takeIf { it.isNotBlank() }
        ?: if (group.subscription == null) stringResource(R.string.home_local_servers) else group.remarks

internal fun cardBrush(group: GroupMapItem, now: Long): Brush {
    val look = com.v2ray.ang.handler.SubLookStore.get(group.id)
    val colors = when {
        // An ended subscription is always grey, whatever colour was chosen.
        HomeCard.expired(group.subscription?.expireAt, now) -> HomeTokens.cardExpired
        look.gradient in HomeTokens.cardGradients.indices -> HomeTokens.cardGradients[look.gradient]
        else -> HomeTokens.cardGradients[HomeCard.paletteIndex(group.id)]
    }
    return Brush.linearGradient(colors)
}

/** A picture file of the subscription's look, decoded off the main thread; null while loading or when there is none. */
@Composable
internal fun rememberLookImage(file: java.io.File, rev: Long, enabled: Boolean): androidx.compose.ui.graphics.ImageBitmap? {
    val state = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file.path, rev, enabled) {
        value = if (!enabled) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { android.graphics.BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    return state.value
}

/** The tile of a subscription: its picture, its emoji or the first letter of its name. */
@Composable
internal fun SubAvatar(
    group: GroupMapItem,
    size: androidx.compose.ui.unit.Dp,
    radius: androidx.compose.ui.unit.Dp,
    letterSp: Int,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val look = com.v2ray.ang.handler.SubLookStore.get(group.id)
    val photo = rememberLookImage(
        com.v2ray.ang.handler.SubLookStore.avatarFile(context, group.id), look.rev, look.avatar == com.v2ray.ang.net.SubLook.Avatar.PHOTO
    )
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(HomeTokens.onCard.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            photo != null -> androidx.compose.foundation.Image(
                bitmap = photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            look.avatar == com.v2ray.ang.net.SubLook.Avatar.EMOJI && look.emoji.isNotEmpty() ->
                Text(look.emoji, fontSize = (letterSp * 1.15f).sp)
            else -> Text(ShortName.initial(groupFullName(group)), color = HomeTokens.onCard, fontWeight = FontWeight.Bold, fontSize = letterSp.sp)
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val text = if (value < 10 && unit > 0) "%.1f".format(value) else Math.round(value).toString()
    return "$text ${units[unit]}"
}

internal fun shortDate(millis: Long): String = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(millis))

/** Swipeable subscription cards; one card fills the width, several show a peek of the next one. */
@Composable
internal fun SubscriptionCarousel(
    groups: List<GroupMapItem>,
    pagerState: PagerState,
    mainViewModel: MainViewModel,
    activeGroupId: String?,
    now: Long,
    onMore: (GroupMapItem) -> Unit,
) {
    val single = groups.size == 1
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val peek = minOf((maxWidth - 32.dp) * 0.90f, 420.dp)
    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        pageSpacing = 12.dp,
        pageSize = if (single) PageSize.Fill else PageSize.Fixed(peek),
        modifier = Modifier.fillMaxWidth(),
    ) { page ->
        val group = groups[page]
        val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
        SubscriptionCardV4(
            group = group,
            mainViewModel = mainViewModel,
            active = group.id == activeGroupId,
            now = now,
            onMore = { onMore(group) },
            modifier = Modifier.graphicsLayer {
                val scale = 1f - 0.05f * offset
                scaleX = scale
                scaleY = scale
                alpha = 1f - 0.12f * offset
            },
        )
    }
    }
    if (!single) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        ) {
            val accent = homeAccent()
            groups.indices.forEach { i ->
                val on = i == pagerState.currentPage
                Box(
                    Modifier
                        .height(6.dp)
                        .width(if (on) 18.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                )
            }
        }
    }
}

@Composable
private fun SubscriptionCardV4(
    group: GroupMapItem,
    mainViewModel: MainViewModel,
    active: Boolean,
    now: Long,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sub = group.subscription
    val servers by androidx.compose.runtime.remember(group.id, mainViewModel) { mainViewModel.serversForGroup(group.id) }
        .collectAsStateWithLifecycle()
    val fullName = groupFullName(group)
    val name = ShortName.of(fullName)
    val expired = HomeCard.expired(sub?.expireAt, now)
    val days = HomeCard.daysLeft(sub?.expireAt, now)
    val used = sub?.trafficUsed
    val total = sub?.trafficTotal?.takeIf { it > 0L }
    val share = HomeCard.usedShare(used, total)
    val onCard = HomeTokens.onCard
    val statusText = when {
        expired -> stringResource(R.string.home_status_expired)
        !active && com.v2ray.ang.handler.SubscriptionErrors.get(group.id) != null -> stringResource(R.string.home_status_error)
        active -> stringResource(R.string.home_status_connected)
        sub != null && !sub.enabled -> stringResource(R.string.home_status_off)
        sub != null && sub.lastUpdated > 0 && now - sub.lastUpdated in 0..60_000L -> stringResource(R.string.home_status_updated_now)
        else -> pluralStringResource(R.plurals.home_servers_n, servers.size, servers.size)
    }

    val a11y = buildString {
        append(fullName).append(", ").append(statusText)
        if (days != null) append(", ").append(days).append(' ').append(stringResource(R.string.home_days))
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .clip(RoundedCornerShape(HomeStyle.r(28)))
            .background(cardBrush(group, now))
            .background(HomeTokens.cardShade)
            .clickable(onClick = onMore)
    ) {
        val look = com.v2ray.ang.handler.SubLookStore.get(group.id)
        val cardContext = androidx.compose.ui.platform.LocalContext.current
        val backdrop = rememberLookImage(com.v2ray.ang.handler.SubLookStore.backgroundFile(cardContext, group.id), look.rev, look.background)
        if (backdrop != null) {
            // The user's picture under a dark veil, so the white text stays readable on any photo.
            androidx.compose.foundation.Image(
                bitmap = backdrop, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.40f), Color.Black.copy(alpha = 0.62f)))))
        }
        // Grows with large fonts instead of clipping; at normal size it is 196dp tall.
        Column(
            Modifier.fillMaxWidth().heightIn(min = 196.dp).padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SubAvatar(group, 40.dp, 14.dp, 18)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        name, color = onCard, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (active) {
                            PulsingDot(onCard)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(statusText, color = onCard.copy(alpha = 0.88f), fontSize = 13.sp, maxLines = 1)
                    }
                }
                val moreLabel = stringResource(R.string.home_sub_menu)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(onCard.copy(alpha = 0.18f))
                        .semantics { contentDescription = moreLabel }
                        .clickable(onClick = onMore),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_more_vert_24dp), null, tint = onCard, modifier = Modifier.size(20.dp))
                }
            }
            Column {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.weight(1f)) {
                    when {
                        days != null -> {
                            Text(days.toString(), color = onCard, fontSize = 46.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
                            Text(
                                " " + stringResource(R.string.home_days), color = onCard.copy(alpha = 0.85f),
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        sub == null -> {
                            Text(servers.size.toString(), color = onCard, fontSize = 46.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
                            Text(
                                " " + stringResource(R.string.home_stat_servers), color = onCard.copy(alpha = 0.85f),
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        else -> Text("∞", color = onCard, fontSize = 46.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)) {
                    sub?.expireAt?.takeIf { it > 0L }?.let {
                        Text(stringResource(R.string.home_until, shortDate(it)), color = onCard.copy(alpha = 0.9f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (sub != null) {
                        val traffic = when {
                            used != null && total != null -> "${formatBytes(used)} / ${formatBytes(total)}"
                            used != null -> "${formatBytes(used)} · ∞"
                            else -> stringResource(R.string.home_unlimited)
                        }
                        Text(traffic, color = onCard.copy(alpha = 0.9f), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            if (sub != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(onCard.copy(alpha = 0.22f))
                ) {
                    val fill = share ?: 1f
                    Box(
                        Modifier
                            .fillMaxWidth(fill.coerceAtLeast(0.02f))
                            .height(8.dp)
                            .clip(CircleShape)
                            .alpha(if (share == null) 0.5f else 1f)
                            .background(onCard)
                    )
                }
            }
            }
        }
    }
}

/** System "remove animations" (animator scale 0): no pulses or spinning. */
@Composable
internal fun animationsOff(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember {
        runCatching {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

@Composable
internal fun PulsingDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    if (animationsOff()) {
        Box(Modifier.size(size).clip(CircleShape).background(color))
        return
    }
    val transition = rememberInfiniteTransition(label = "dot")
    val a by transition.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dotAlpha"
    )
    Box(Modifier.size(size).alpha(a).clip(CircleShape).background(color))
}
