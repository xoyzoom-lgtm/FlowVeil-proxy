package com.v2ray.ang.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.BypassHistory
import com.v2ray.ang.handler.BypassRating
import com.v2ray.ang.handler.FavoriteServers
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.net.BypassLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BadgeKind { MARKED, FITS, WORKED, FAILED, UNTESTED }

private data class Badge(val kind: BadgeKind, val at: Long = 0L)

private data class PickerServer(
    val guid: String,
    val name: String,
    val type: String,
    val delay: Long,
    val badges: List<Badge>,
    val unlikely: Boolean,
)
private data class PickerGroup(val title: String, val servers: List<PickerServer>)

/**
 * Multi-select of the manual bypass servers: search, favorites on top, then one group per
 * subscription. No drag ordering: the watchdog always takes the fastest working one anyway.
 */
@Composable
fun BypassServerPickerDialog(onDismiss: () -> Unit, onSaved: (Int) -> Unit) {
    val favoritesTitle = stringResource(R.string.whitelist_bypass_favorites)
    val groups = remember { loadGroups(favoritesTitle) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(WhitelistBypass.servers()) } }
    var query by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    stringResource(R.string.title_whitelist_bypass_servers),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(4.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.whitelist_bypass_search_hint)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                val filtered = groups.map { g ->
                    g.copy(servers = g.servers.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) })
                }.filter { it.servers.isNotEmpty() }

                if (groups.isEmpty()) {
                    Text(stringResource(R.string.whitelist_bypass_no_servers), modifier = Modifier.padding(8.dp).weight(1f))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        filtered.forEachIndexed { index, group ->
                            // Keys by position: two subscriptions may share a name.
                            item(key = "h_$index") {
                                Text(
                                    group.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(group.servers, key = { "${index}_${it.guid}" }) { server ->
                                val checked = server.guid in selected
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { if (checked) selected.remove(server.guid) else selected.add(server.guid) }
                                        .padding(vertical = 2.dp)
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { if (it) selected.add(server.guid) else selected.remove(server.guid) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            if (server.delay > 0) "${server.type} · ${server.delay} ms" else server.type,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        server.badges.forEach { badge ->
                                            Text(
                                                badgeText(badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = when (badge.kind) {
                                                    BadgeKind.WORKED, BadgeKind.MARKED -> MaterialTheme.colorScheme.primary
                                                    BadgeKind.FAILED -> MaterialTheme.colorScheme.error
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                        if (checked && server.unlikely) {
                                            Text(
                                                stringResource(R.string.bypass_warn_unlikely),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    TextButton(onClick = {
                        WhitelistBypass.setServers(selected.toList())
                        onSaved(selected.size)
                    }) { Text(stringResource(R.string.action_ok)) }
                }
            }
        }
    }
}

@Composable
private fun badgeText(badge: Badge): String = when (badge.kind) {
    BadgeKind.MARKED -> stringResource(R.string.bypass_badge_marked)
    BadgeKind.FITS -> stringResource(R.string.bypass_badge_fits)
    BadgeKind.WORKED -> stringResource(R.string.bypass_badge_worked, formatDate(badge.at))
    BadgeKind.FAILED -> stringResource(R.string.bypass_badge_failed, formatDate(badge.at))
    BadgeKind.UNTESTED -> stringResource(R.string.bypass_badge_untested)
}

private fun formatDate(at: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(at))

/** What the classifier and the history of tests on the mobile network say about a server. */
private fun badgesOf(guid: String): Pair<List<Badge>, Boolean> {
    val rated = BypassRating.rate(guid, bucket = null) ?: return emptyList<Badge>() to false
    val codes = rated.rating.reasons.map { it.code }.toSet()
    val badges = ArrayList<Badge>()
    if (codes.any { it == "name_label" || it == "name_mobile" || it == "name_bridge" }) badges += Badge(BadgeKind.MARKED)
    if (codes.any { it in setOf("reality_vision", "reality", "xhttp", "cdn_transport", "mask_sni", "mask_host") }) badges += Badge(BadgeKind.FITS)
    val history = BypassHistory.latest(rated.fingerprint)
    when {
        history == null || (history.lastOkAt == 0L && history.lastFailAt == 0L) -> badges += Badge(BadgeKind.UNTESTED)
        history.lastOkAt >= history.lastFailAt -> badges += Badge(BadgeKind.WORKED, history.lastOkAt)
        else -> badges += Badge(BadgeKind.FAILED, history.lastFailAt)
    }
    return badges to (rated.rating.level == BypassLevel.UNLIKELY)
}

private fun loadGroups(favoritesTitle: String): List<PickerGroup> {
    fun toServer(guid: String): PickerServer? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        if (profile.configType == EConfigType.POLICYGROUP || profile.configType == EConfigType.PROXYCHAIN) return null
        val (badges, unlikely) = badgesOf(guid)
        return PickerServer(
            guid = guid,
            name = profile.remarks.ifBlank { guid.take(8) },
            type = profile.configType.name,
            delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L,
            badges = badges,
            unlikely = unlikely,
        )
    }

    val favorites = FavoriteServers.all()
    val groups = mutableListOf<PickerGroup>()
    val favoriteServers = MmkvManager.decodeAllServerList().filter { it in favorites }.mapNotNull(::toServer)
    if (favoriteServers.isNotEmpty()) groups += PickerGroup(favoritesTitle, favoriteServers)

    val subscriptions = MmkvManager.decodeSubscriptions()
    val known = subscriptions.map { it.guid }.toSet()
    subscriptions.forEach { sub ->
        val servers = MmkvManager.decodeServerList(sub.guid).mapNotNull(::toServer)
        if (servers.isNotEmpty()) {
            val title = sub.subscription.profileTitle?.takeIf { it.isNotBlank() } ?: sub.subscription.remarks
            groups += PickerGroup(title, servers)
        }
    }
    // Servers added by hand live outside any subscription.
    val loose = MmkvManager.decodeAllServerList()
        .filter { guid -> MmkvManager.decodeServerConfig(guid)?.subscriptionId.let { it.isNullOrEmpty() || it !in known } }
        .mapNotNull(::toServer)
    if (loose.isNotEmpty()) groups += PickerGroup("—", loose)
    return groups
}
