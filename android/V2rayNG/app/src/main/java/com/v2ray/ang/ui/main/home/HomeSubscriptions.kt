package com.v2ray.ang.ui.main.home

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.net.ShortName
import com.v2ray.ang.ui.main.MainBackground

/**
 * The list of subscriptions in the new style (replaces the old "Groups" screen). A tap opens the same menu as the card's «⋯»
 * (update, check, share, appearance, edit, delete); the switch turns a subscription on or off.
 */
@Composable
internal fun HomeSubscriptions(
    groups: List<GroupMapItem>,
    now: Long,
    onClose: () -> Unit,
    onOpen: (GroupMapItem) -> Unit,
    onAdd: () -> Unit,
    onRefreshAll: () -> Unit,
    onToggle: (GroupMapItem, Boolean) -> Unit,
) {
    BackHandler(onBack = onClose)
    val subs = groups.filter { it.subscription != null }
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    Box(Modifier.fillMaxSize().screenBase()) {
        MainBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            ) {
                RoundAction(R.drawable.ic_arrow_back_24dp, stringResource(R.string.onb_back), onClose, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.hs_subscriptions), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                RoundAction(R.drawable.ic_refresh_24dp, stringResource(R.string.home_refresh), onRefreshAll, size = 42.dp)
                Spacer(Modifier.width(8.dp))
                RoundAction(R.drawable.ic_add_24dp, stringResource(R.string.home_add_title), onAdd, size = 42.dp)
            }
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (subs.isEmpty()) {
                    Text(stringResource(R.string.home_empty_text), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(HomeStyle.r(22))).background(homeSurface()).glassEdge(RoundedCornerShape(HomeStyle.r(22)))) {
                    subs.forEach { g ->
                        val sub = g.subscription!!
                        val on = enabled[g.id] ?: sub.enabled
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 68.dp)
                                .semantics { role = Role.Button }
                                .clickable { onOpen(g) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            SubAvatar(g, 44.dp, 14.dp, 19)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ShortName.of(groupFullName(g)), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val host = runCatching { java.net.URI(sub.url).host }.getOrNull().orEmpty()
                                val updated = if (sub.lastUpdated > 0) DateUtils.getRelativeTimeSpanString(sub.lastUpdated, now, DateUtils.MINUTE_IN_MILLIS).toString() else stringResource(R.string.home_updated_never)
                                Text(listOf(host, updated).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = on, onCheckedChange = { enabled[g.id] = it; onToggle(g, it) })
                        }
                        HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    }
                }
                Text(stringResource(R.string.subs_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
            }
        }
    }
}
