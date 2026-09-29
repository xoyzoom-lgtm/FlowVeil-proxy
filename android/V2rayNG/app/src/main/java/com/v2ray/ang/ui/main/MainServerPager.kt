package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem

internal class ServerRowActions(
    val select: (String) -> Unit,
    val more: (String, ProfileItem) -> Unit,
)

@Composable
internal fun ServerListItem(
    row: ServerRowUiModel,
    isSelected: Boolean,
    actions: ServerRowActions,
    alive: Boolean? = null,
    availabilityOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val accent = mainAccentColor()
    val selectedStateDescription = if (isSelected) stringResource(R.string.acc_selected_server) else null
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(serverCardColor(isSelected))
            .border(
                width = 1.5.dp,
                color = if (isSelected) accent else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .semantics {
                selected = isSelected
                if (selectedStateDescription != null) {
                    stateDescription = selectedStateDescription
                }
            }
            .clickable { actions.select(row.guid) }
            .padding(start = 12.dp, top = 9.dp, bottom = 9.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (flag, displayName) = remember(row.remarks) { splitFlag(row.remarks) }
        ServerBadge(remarks = row.remarks, flag = flag)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (row.isFavorite) "★ $displayName" else displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = row.typeDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        PingPill(row.testDelayMillis, availabilityOnly = availabilityOnly, alive = alive)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { actions.more(row.guid, row.profile) }, Modifier.size(36.dp)) {
            Icon(
                painterResource(R.drawable.ic_chevron_right_24dp),
                stringResource(R.string.acc_more),
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
