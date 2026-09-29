package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    val left = if (used != null && total != null) (total - used).coerceAtLeast(0L) else null
    val daysLeft = expireAt?.let { ((it - System.currentTimeMillis()) / DAY_MILLIS).coerceAtLeast(0L) }
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(serverCardColor(selected = false))
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 10.dp)
    ) {
        // Title with small actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!subscription.supportUrl.isNullOrBlank()) {
                CircleIconButton(
                    iconRes = R.drawable.ic_telegram_24dp,
                    contentDescription = stringResource(R.string.sub_action_support),
                    onClick = { onOpenSupport(subscription.supportUrl!!) },
                    size = 32.dp,
                    tint = accent
                )
            }
            CircleIconButton(
                iconRes = R.drawable.ic_speed_24dp,
                contentDescription = stringResource(R.string.sub_action_check),
                onClick = onTestAll,
                size = 32.dp,
                tint = accent,
                enabled = !isTesting
            )
            CircleIconButton(
                iconRes = R.drawable.ic_refresh_24dp,
                contentDescription = stringResource(R.string.sub_action_update),
                onClick = onRefresh,
                size = 32.dp,
                tint = accent
            )
            Box {
                CircleIconButton(
                    iconRes = R.drawable.ic_more_vert_24dp,
                    contentDescription = stringResource(R.string.main_sub_menu),
                    onClick = { showMenu = true },
                    size = 32.dp,
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

        // Two framed facts, each a label + big value + small detail, nothing truncated
        if (used != null || expireAt != null) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (used != null) {
                    InfoFrame(
                        label = stringResource(R.string.sub_frame_traffic),
                        value = if (total != null) {
                            stringResource(R.string.sub_frame_used_of, formatBytes(used), formatBytes(total))
                        } else {
                            stringResource(R.string.sub_frame_used_of, formatBytes(used), "∞")
                        },
                        detail = if (left != null) {
                            stringResource(R.string.main_sub_left, formatBytes(left))
                        } else {
                            stringResource(R.string.sub_frame_unlimited_short)
                        },
                        color = accent,
                        progress = progress?.let { 1f - it },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (expireAt != null) {
                    val expiring = daysLeft != null && daysLeft <= 3
                    InfoFrame(
                        label = stringResource(R.string.sub_frame_subscription),
                        value = stringResource(
                            R.string.sub_frame_until_short,
                            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(Date(expireAt))
                        ),
                        detail = stringResource(R.string.sub_frame_days_left, (daysLeft ?: 0L).toInt()),
                        color = if (expiring) PingBad else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Provider announcement, always shown in full
        if (!subscription.announce.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = subscription.announce!!,
                style = MaterialTheme.typography.bodySmall,
                color = subtle,
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp)
            )
        }
    }
}

@Composable
private fun InfoFrame(
    label: String,
    value: String,
    detail: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val subtle = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.40f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = subtle, maxLines = 1)
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = color,
                trackColor = color.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(text = detail, style = MaterialTheme.typography.labelSmall, color = subtle, maxLines = 1)
    }
}
