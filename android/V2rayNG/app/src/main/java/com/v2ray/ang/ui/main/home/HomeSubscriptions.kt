package com.v2ray.ang.ui.main.home

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
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
    onReorder: (List<String>) -> Unit,
) {
    BackHandler(onBack = onClose)
    val subs = groups.filter { it.subscription != null }
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    // Order on screen: follows the stored order, and while a row is dragged it swaps places live.
    val ids = subs.map { it.id }
    val order = remember { mutableStateListOf<String>().apply { addAll(ids) } }
    LaunchedEffect(ids) { if (order.toList() != ids) { order.clear(); order.addAll(ids) } }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    val byId = subs.associateBy { it.id }
    Box(Modifier.fillMaxSize().screenBase(solid = true)) {
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
                    order.toList().forEach { id ->
                        val g = byId[id] ?: return@forEach
                        androidx.compose.runtime.key(g.id) {
                        val sub = g.subscription!!
                        val on = enabled[g.id] ?: sub.enabled
                        val dragging = draggingId == g.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 68.dp)
                                .onSizeChanged { if (it.height > 0) rowHeight = it.height.toFloat() }
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) dragOffset else 0f; scaleX = if (dragging) 1.02f else 1f; scaleY = if (dragging) 1.02f else 1f }
                                .then(if (dragging) Modifier.shadow(12.dp, RoundedCornerShape(HomeStyle.r(16))).background(homeSurface(selected = true)) else Modifier)
                                .semantics { role = Role.Button }
                                .clickable { onOpen(g) }
                                .pointerInput(g.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggingId = g.id; dragOffset = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                        onDragEnd = { draggingId = null; dragOffset = 0f; onReorder(order.toList()) },
                                        onDragCancel = { draggingId = null; dragOffset = 0f },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragOffset += delta.y
                                            val h = rowHeight
                                            if (h > 0f) {
                                                val i = order.indexOf(g.id)
                                                if (dragOffset > h / 2 && i < order.lastIndex) {
                                                    order.add(i + 1, order.removeAt(i)); dragOffset -= h
                                                } else if (dragOffset < -h / 2 && i > 0) {
                                                    order.add(i - 1, order.removeAt(i)); dragOffset += h
                                                }
                                            }
                                        },
                                    )
                                }
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
                }
                Text(stringResource(R.string.subs_hint) + " " + stringResource(R.string.subs_hint_drag), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 8.dp))
            }
        }
    }
}
