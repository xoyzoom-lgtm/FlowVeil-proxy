package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.dto.GroupMapItem

@Composable
internal fun GroupChips(
    groups: List<GroupMapItem>,
    selectedIndex: Int,
    mainViewModel: MainViewModel,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = mainAccentColor()
    val onAccent = mainOnAccentColor()
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(groups, key = { _, group -> group.id }) { index, group ->
            val serverFlow = remember(group.id, mainViewModel) { mainViewModel.serversForGroup(group.id) }
            val servers by serverFlow.collectAsStateWithLifecycle()
            val selected = index == selectedIndex
            val title = group.subscription?.profileTitle?.takeIf { it.isNotBlank() } ?: group.remarks
            val text = if (group.id.isEmpty()) title else "$title · ${servers.size}"
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) onAccent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) accent else serverCardColor(selected = false))
                    .semantics { role = Role.Tab }
                    .clickable { onClick(index) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}
