package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.SubscriptionItem
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val formatted = if (value < 10 && unitIndex > 0) "%.1f".format(value) else value.roundToInt().toString()
    return "$formatted ${units[unitIndex]}"
}

/** Everything the subscription card's menu can do; mirrors the v2rayNG group actions. */
internal enum class SubscriptionMenuAction(val labelRes: Int, val iconRes: Int) {
    Update(R.string.sub_menu_update, R.drawable.ic_refresh_24dp),
    TestRealPing(R.string.sub_menu_real_ping, R.drawable.ic_speed_24dp),
    TestTcping(R.string.sub_menu_tcping, R.drawable.ic_speed_24dp),
    SortByPing(R.string.sub_menu_sort, R.drawable.ic_expand_more_24dp),
    Edit(R.string.sub_menu_edit, R.drawable.ic_edit_24dp),
    CopyLink(R.string.sub_menu_copy, R.drawable.ic_copy),
    ExportAll(R.string.sub_menu_export, R.drawable.ic_share_24dp),
    RemoveDuplicate(R.string.sub_menu_remove_duplicate, R.drawable.ic_delete_24dp),
    RemoveInvalid(R.string.sub_menu_remove_invalid, R.drawable.ic_delete_24dp),
    Delete(R.string.sub_menu_delete, R.drawable.ic_delete_24dp),
    AllSubscriptions(R.string.sub_menu_all, R.drawable.ic_subscriptions_24dp),
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** iOS-style summary card for a subscription: title, traffic, expiry, quick actions and a full menu. */
@Composable
internal fun SubscriptionCard(
    subscription: SubscriptionItem,
    isTesting: Boolean,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onOpenSupport: (String) -> Unit,
    onMenuAction: (SubscriptionMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = mainAccentColor()
    val title = subscription.profileTitle?.takeIf { it.isNotBlank() } ?: subscription.remarks
    val used = subscription.trafficUsed
    val total = subscription.trafficTotal?.takeIf { it > 0L }
    val expireAt = subscription.expireAt
    val progress = if (used != null && total != null) {
        (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    var showMenu by remember { mutableStateOf(false) }
    var announceExpanded by remember { mutableStateOf(false) }
    val left = if (used != null && total != null) (total - used).coerceAtLeast(0L) else null
    val daysLeft = expireAt?.let { ((it - System.currentTimeMillis()) / DAY_MILLIS).coerceAtLeast(0L) }
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(serverCardColor(selected = false))
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)
    ) {
        // Title + menu
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                CircleIconButton(
                    iconRes = R.drawable.ic_more_vert_24dp,
                    contentDescription = stringResource(R.string.main_sub_menu),
                    onClick = { showMenu = true },
                    size = 36.dp,
                    tint = subtle
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    SubscriptionMenuAction.entries.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.labelRes)) },
                            leadingIcon = {
                                Icon(painterResource(action.iconRes), contentDescription = null, Modifier.size(20.dp))
                            },
                            onClick = {
                                showMenu = false
                                onMenuAction(action)
                            }
                        )
                    }
                }
            }
        }

        // Traffic and expiry: two clear figures instead of a line of small text
        if (used != null || expireAt != null) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (left != null) R.string.sub_left_label else R.string.sub_traffic_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = subtle
                    )
                    Text(
                        text = when {
                            left != null -> formatBytes(left)
                            used != null -> stringResource(R.string.sub_unlimited)
                            else -> "—"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    if (used != null) {
                        Text(
                            text = stringResource(R.string.sub_used_small, formatBytes(used)),
                            style = MaterialTheme.typography.bodySmall,
                            color = subtle
                        )
                    }
                }
                if (expireAt != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.sub_until_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = subtle
                        )
                        Text(
                            text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expireAt)),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (daysLeft != null) {
                            Text(
                                text = stringResource(R.string.main_sub_days_left, daysLeft.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (daysLeft <= 3) PingBad else subtle
                            )
                        }
                    }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { 1f - progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 10.dp)
                        .height(6.dp)
                        .clip(CircleShape),
                    color = accent,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                )
            }
        }

        // Provider announcement: short by default, tap to read all of it
        if (!subscription.announce.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = subscription.announce!!,
                style = MaterialTheme.typography.bodySmall,
                color = subtle,
                maxLines = if (announceExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 10.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .clickable { announceExpanded = !announceExpanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        // Labelled actions: clear for everyone, no guessing what an icon means
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardAction(R.drawable.ic_refresh_24dp, stringResource(R.string.sub_action_update), accent, onRefresh, Modifier.weight(1f))
            CardAction(R.drawable.ic_speed_24dp, stringResource(R.string.sub_action_check), accent, onTestAll, Modifier.weight(1f), enabled = !isTesting)
            if (!subscription.supportUrl.isNullOrBlank()) {
                CardAction(
                    R.drawable.ic_telegram_24dp,
                    stringResource(R.string.sub_action_support),
                    accent,
                    { onOpenSupport(subscription.supportUrl!!) },
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CardAction(
    iconRes: Int,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = if (enabled) 0.14f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
