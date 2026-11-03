package com.v2ray.ang.ui.main.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.net.ModePresets

/** The three one-tap modes (games + Discord, video and social networks, everything through the server). */
@Composable
internal fun ModeRow(selected: ModePresets.Mode?, onPick: (ModePresets.Mode) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        ModeChip(R.string.mode_games, selected == ModePresets.Mode.GAMES, Modifier.weight(1f)) { onPick(ModePresets.Mode.GAMES) }
        ModeChip(R.string.mode_social, selected == ModePresets.Mode.SOCIAL, Modifier.weight(1f)) { onPick(ModePresets.Mode.SOCIAL) }
        ModeChip(R.string.mode_full, selected == ModePresets.Mode.FULL, Modifier.weight(1f)) { onPick(ModePresets.Mode.FULL) }
    }
}

@Composable
private fun ModeChip(label: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = homeAccent()
    val shape = RoundedCornerShape(16.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.16f) else homeSurface())
            .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape)
            .semantics { role = Role.RadioButton; this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp)
    ) {
        Text(
            stringResource(label), fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
