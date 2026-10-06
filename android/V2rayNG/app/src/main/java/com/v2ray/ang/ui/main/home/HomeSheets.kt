package com.v2ray.ang.ui.main.home

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.DevMode
import com.v2ray.ang.net.HomeCard
import com.v2ray.ang.net.ShortName
import com.v2ray.ang.ui.compose.HomeTokens
import com.v2ray.ang.util.QRCodeDecoder

/** Every subscription action the sheet can ask the screen to run. */
internal enum class SubSheetAction {
    Update, Check, Share, Edit, Look, Message, Support, CopyLink, AllSubscriptions,
    SortByPing, TestTcping, ExportAll, RemoveDuplicate, RemoveInvalid, Delete,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = when (HomeStyle.mode) { HomeStyle.Mode.BLUR -> 0.72f; HomeStyle.Mode.GLASS -> 0.94f; HomeStyle.Mode.SOLID -> 1f }),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        BlurBehindDialog()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
                .navigationBarsPadding()
        ) {
            content()
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
internal fun SheetRow(iconRes: Int?, title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) HomeTokens.bad else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .heightIn(min = 54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(homeSurface())
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (iconRes != null) {
            Icon(painterResource(iconRes), null, tint = if (danger) HomeTokens.bad else homeAccent(), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!danger) {
            Icon(
                painterResource(R.drawable.ic_chevron_right_24dp), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(homeSurface())
            .padding(vertical = 12.dp, horizontal = 6.dp)
    ) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ActionTile(iconRes: Int, label: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(homeAccent().copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(iconRes), null, tint = homeAccent(), modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SubscriptionSheet(
    group: GroupMapItem,
    serverCount: Int,
    now: Long,
    onAction: (SubSheetAction) -> Unit,
    onDismiss: () -> Unit,
    onEnabled: (Boolean) -> Unit = {},
) {
    val sub = group.subscription
    var enabled by remember(group.id) { mutableStateOf(sub?.enabled ?: true) }
    val fullName = groupFullName(group)
    var confirmDelete by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    val act: (SubSheetAction) -> Unit = { onAction(it) }
    HomeSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Box(Modifier.clip(RoundedCornerShape(20.dp)).background(cardBrush(group, now)).background(HomeTokens.cardShade)) {
                SubAvatar(group, 60.dp, 20.dp, 26)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(ShortName.of(fullName), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (ShortName.of(fullName) != fullName) {
                    Text(fullName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val days = HomeCard.daysLeft(sub?.expireAt, now)
            StatTile(days?.toString() ?: "∞", stringResource(R.string.home_stat_days), Modifier.weight(1f))
            val total = sub?.trafficTotal?.takeIf { it > 0L }
            val used = sub?.trafficUsed
            val traffic = when {
                total != null && used != null -> formatBytes((total - used).coerceAtLeast(0L))
                total != null -> formatBytes(total)
                else -> "∞"
            }
            StatTile(traffic, if (total == null) stringResource(R.string.home_unlimited) else stringResource(R.string.home_stat_traffic), Modifier.weight(1f))
            StatTile(serverCount.toString(), stringResource(R.string.home_stat_servers), Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            if (sub != null) ActionTile(R.drawable.ic_refresh_24dp, stringResource(R.string.home_act_update), { act(SubSheetAction.Update) }, Modifier.weight(1f))
            ActionTile(R.drawable.ic_flash_on_24dp, stringResource(R.string.home_act_check), { act(SubSheetAction.Check) }, Modifier.weight(1f))
            if (sub != null) {
                ActionTile(R.drawable.ic_share_24dp, stringResource(R.string.home_act_share), { act(SubSheetAction.Share) }, Modifier.weight(1f))
                ActionTile(R.drawable.ic_edit_24dp, stringResource(R.string.home_act_edit), { act(SubSheetAction.Edit) }, Modifier.weight(1f))
            }
            if (!sub?.supportUrl.isNullOrBlank()) {
                ActionTile(R.drawable.ic_telegram_24dp, stringResource(R.string.home_act_support), { act(SubSheetAction.Support) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (sub != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(homeSurface())
                    .clickable { enabled = !enabled; onEnabled(enabled) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_sub_use), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(stringResource(R.string.home_sub_use_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { enabled = it; onEnabled(it) })
            }
        }
        if (!sub?.announce.isNullOrBlank()) {
            SheetRow(R.drawable.ic_promotion_24dp, stringResource(R.string.home_provider_message), sub!!.announce) { act(SubSheetAction.Message) }
        }
        SheetRow(R.drawable.ic_image_24dp, stringResource(R.string.look_title), stringResource(R.string.look_sub)) { act(SubSheetAction.Look) }
        if (sub != null) {
            SheetRow(R.drawable.ic_copy, stringResource(R.string.sub_menu_copy)) { act(SubSheetAction.CopyLink) }
        }
        SheetRow(R.drawable.ic_subscriptions_24dp, stringResource(R.string.sub_menu_all)) { act(SubSheetAction.AllSubscriptions) }
        if (DevMode.isOn()) {
            SheetRow(R.drawable.ic_more_vert_24dp, stringResource(R.string.home_more)) { showMore = !showMore }
            if (showMore) {
                SheetRow(null, stringResource(R.string.sub_menu_sort)) { act(SubSheetAction.SortByPing) }
                SheetRow(null, stringResource(R.string.sub_menu_tcping)) { act(SubSheetAction.TestTcping) }
                SheetRow(null, stringResource(R.string.sub_menu_export)) { act(SubSheetAction.ExportAll) }
                SheetRow(null, stringResource(R.string.sub_menu_remove_duplicate)) { act(SubSheetAction.RemoveDuplicate) }
                SheetRow(null, stringResource(R.string.sub_menu_remove_invalid)) { act(SubSheetAction.RemoveInvalid) }
            }
        }
        if (sub != null) {
            Text(
                if (sub.lastUpdated > 0) {
                    stringResource(R.string.home_updated, DateUtils.getRelativeTimeSpanString(sub.lastUpdated, now, DateUtils.MINUTE_IN_MILLIS).toString())
                } else {
                    stringResource(R.string.home_updated_never)
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)
            )
            SheetRow(
                R.drawable.ic_delete_24dp,
                stringResource(if (confirmDelete) R.string.home_delete_again else R.string.home_delete_sub),
                danger = true
            ) {
                if (confirmDelete) act(SubSheetAction.Delete) else confirmDelete = true
            }
        }
    }
}

@Composable
internal fun AddSheet(
    onPaste: () -> Unit,
    onScan: () -> Unit,
    onTransfer: () -> Unit,
    onManual: () -> Unit,
    onFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    HomeSheet(onDismiss) {
        SheetTitle(stringResource(R.string.home_add_title))
        SheetRow(R.drawable.ic_copy, stringResource(R.string.home_add_paste), onClick = onPaste)
        SheetRow(R.drawable.ic_scan_24dp, stringResource(R.string.home_add_scan), onClick = onScan)
        SheetRow(R.drawable.ic_qu_switch_24dp, stringResource(R.string.home_add_transfer), onClick = onTransfer)
        SheetRow(R.drawable.ic_edit_24dp, stringResource(R.string.home_add_manual), onClick = onManual)
        SheetRow(R.drawable.ic_file_24dp, stringResource(R.string.home_add_file), onClick = onFile)
        Text(
            stringResource(R.string.home_add_caption),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)
        )
    }
}

/** Server types for "enter manually", same list as the old add menu. */
private val manualTypes = listOf(
    R.string.menu_item_import_config_manually_vless to EConfigType.VLESS,
    R.string.menu_item_import_config_manually_vmess to EConfigType.VMESS,
    R.string.menu_item_import_config_manually_trojan to EConfigType.TROJAN,
    R.string.menu_item_import_config_manually_ss to EConfigType.SHADOWSOCKS,
    R.string.menu_item_import_config_manually_hysteria2 to EConfigType.HYSTERIA2,
    R.string.menu_item_import_config_manually_tuic to EConfigType.TUIC,
    R.string.menu_item_import_config_manually_wireguard to EConfigType.WIREGUARD,
    R.string.menu_item_import_config_manually_socks to EConfigType.SOCKS,
    R.string.menu_item_import_config_manually_http to EConfigType.HTTP,
    R.string.menu_item_import_config_policy_group to EConfigType.POLICYGROUP,
    R.string.menu_item_import_config_proxy_chain to EConfigType.PROXYCHAIN,
)

@Composable
internal fun ManualSheet(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    HomeSheet(onDismiss) {
        SheetTitle(stringResource(R.string.home_manual_title))
        manualTypes.forEach { (label, type) ->
            SheetRow(null, stringResource(label)) { onPick(type.value) }
        }
    }
}

@Composable
internal fun NoteSheet(text: String, supportUrl: String?, onSupport: (String) -> Unit, onDismiss: () -> Unit) {
    HomeSheet(onDismiss) {
        SheetTitle(stringResource(R.string.home_provider_message))
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 16.dp))
        if (!supportUrl.isNullOrBlank()) {
            SheetRow(R.drawable.ic_telegram_24dp, stringResource(R.string.home_act_support)) { onSupport(supportUrl) }
        }
    }
}

/** Subscription QR: warning first, then the code on white so any camera reads it, and a copy button. */
@Composable
internal fun ShareSheet(url: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    HomeSheet(onDismiss) {
        SheetTitle(stringResource(R.string.sub_qr_warning_title))
        Text(
            stringResource(R.string.sub_qr_warning),
            fontSize = 14.sp,
            color = HomeTokens.warn,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(HomeTokens.warn.copy(alpha = 0.12f))
                .padding(14.dp)
        )
        Spacer(Modifier.height(14.dp))
        if (shown) {
            val bitmap = remember(url) { QRCodeDecoder.createQRCode(url) }
            if (bitmap != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(HomeTokens.qrBackground)
                            .padding(12.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
        } else {
            SheetRow(R.drawable.ic_image_24dp, stringResource(R.string.sub_menu_share_qr)) { shown = true }
        }
        SheetRow(R.drawable.ic_copy, stringResource(R.string.sub_menu_copy), onClick = onCopy)
    }
}

@Composable
internal fun serversCountText(n: Int): String = pluralStringResource(R.plurals.home_servers_n, n, n)
