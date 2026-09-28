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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** iOS-style summary card for a subscription: title, traffic bar, expiry, refresh and ping shortcuts. */
@Composable
internal fun SubscriptionCard(
    subscription: SubscriptionItem,
    isTesting: Boolean,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onOpenSupport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = mainAccentColor()
    val title = subscription.profileTitle?.takeIf { it.isNotBlank() } ?: subscription.remarks
    val used = subscription.trafficUsed
    val total = subscription.trafficTotal
    val expireAt = subscription.expireAt
    val hasQuota = used != null
    val progress = if (hasQuota && total != null && total > 0L) {
        (used!!.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(serverCardColor(selected = false))
            .padding(16.dp)
    ) {
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
                    contentDescription = stringResource(R.string.main_sub_support),
                    onClick = { onOpenSupport(subscription.supportUrl!!) },
                    size = 36.dp,
                    tint = accent
                )
                Spacer(Modifier.width(8.dp))
            }
            CircleIconButton(
                iconRes = R.drawable.ic_speed_24dp,
                contentDescription = stringResource(R.string.title_real_ping_all_server),
                onClick = onTestAll,
                size = 36.dp,
                tint = accent,
                enabled = !isTesting
            )
            Spacer(Modifier.width(8.dp))
            CircleIconButton(
                iconRes = R.drawable.ic_refresh_24dp,
                contentDescription = stringResource(R.string.title_sub_update),
                onClick = onRefresh,
                size = 36.dp,
                tint = accent
            )
        }

        if (hasQuota) {
            Spacer(Modifier.height(10.dp))
            if (progress != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = accent,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val trafficText = if (total != null) {
                    "${formatBytes(used ?: 0L)} / ${formatBytes(total)}"
                } else {
                    formatBytes(used ?: 0L)
                }
                Text(
                    text = trafficText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (expireAt != null) {
                    Text(
                        text = stringResource(
                            R.string.main_sub_expires,
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expireAt))
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (!subscription.announce.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subscription.announce!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
