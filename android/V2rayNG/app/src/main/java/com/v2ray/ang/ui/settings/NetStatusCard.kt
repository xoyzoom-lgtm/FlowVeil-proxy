package com.v2ray.ang.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.BypassState
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NetInfoCache
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.net.FailReason
import com.v2ray.ang.net.NetType
import com.v2ray.ang.ui.compose.PreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsGroupCard
import com.v2ray.ang.ui.compose.SettingsMenuItem
import kotlinx.coroutines.delay

/**
 * "Network": the real network under the tunnel, the real address and the address through the
 * proxy (hidden until tapped), and, when the automatic switching is on, its state. The data comes
 * from the core process through [NetInfoCache]; nothing here talks to the network itself.
 */
@Composable
fun NetStatusCard(devMode: Boolean) {
    val context = LocalContext.current
    val info by produceState(initialValue = NetInfoCache.read()) {
        while (true) {
            value = NetInfoCache.read()
            delay(2_000L)
        }
    }
    val connected = MmkvManager.decodeSettingsLong(AppConfig.CACHE_CONNECTED_SINCE, 0L) > 0L
    var reveal by rememberSaveable { mutableStateOf(false) }
    val bypassOn = WhitelistBypass.isEnabled()

    PreferenceGroupHeader(title = stringResource(R.string.net_card_title))
    SettingsGroupCard {
        if (!connected) {
            SettingsMenuItem(
                title = stringResource(R.string.net_card_title),
                subtitle = stringResource(R.string.net_card_not_connected),
                onClick = {}
            )
            return@SettingsGroupCard
        }

        // joinToString takes a plain lambda: the names are resolved first (map is inline).
        val otherNames = info.others.map { netTypeText(it) }
        SettingsMenuItem(
            title = netTypeText(info.type),
            subtitle = if (otherNames.isEmpty()) null else stringResource(R.string.net_also, otherNames.joinToString(", ")),
            onClick = {}
        )
        IpRow(R.string.net_real_ip, info.realIp ?: info.realIp6, reveal) { reveal = !reveal }
        IpRow(R.string.net_exit_ip, info.exitIp, reveal) { reveal = !reveal }
        if (info.realIp != null && info.realIp == info.exitIp) {
            SettingsMenuItem(
                title = stringResource(R.string.net_ip_same_warning),
                onClick = {}
            )
        }
        if (bypassOn) {
            SettingsMenuItem(
                title = bypassText(info.bypass, info.reason, info.returnTo),
                subtitle = returnText(info.returnTo),
                onClick = {}
            )
        }
        SettingsMenuItem(
            title = stringResource(R.string.net_check_now),
            onClick = { MessageHelper.sendMsg2Service(context, AppConfig.MSG_NET_CHECK_NOW, "") }
        )
        if (devMode && bypassOn) {
            val diag = WhitelistBypass.lastDiagnosis
            if (diag.isNotBlank()) {
                SettingsMenuItem(
                    title = stringResource(R.string.whitelist_bypass_last_check, diag.substringBefore(':')),
                    subtitle = diag + (info.reason?.let { " · ${it.name}" } ?: ""),
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun IpRow(titleRes: Int, ip: String?, reveal: Boolean, onToggle: () -> Unit) {
    val value = when {
        ip == null -> stringResource(R.string.net_ip_unknown)
        reveal -> ip
        else -> stringResource(R.string.net_ip_hidden)
    }
    SettingsMenuItem(
        title = stringResource(titleRes, value),
        subtitle = if (ip != null) stringResource(if (reveal) R.string.net_ip_tap_hide else R.string.net_ip_tap_show) else null,
        onClick = onToggle
    )
}

@Composable
private fun netTypeText(type: NetType): String = stringResource(
    when (type) {
        NetType.CELLULAR -> R.string.net_type_cellular
        NetType.WIFI -> R.string.net_type_wifi
        NetType.ETHERNET -> R.string.net_type_ethernet
        NetType.NONE -> R.string.net_type_none
    }
)

@Composable
private fun bypassText(state: BypassState, reason: FailReason?, returnTo: String?): String = when (state) {
    BypassState.OFF, BypassState.IDLE -> stringResource(R.string.net_bypass_idle)
    BypassState.SEARCHING -> stringResource(R.string.net_bypass_searching)
    BypassState.CHECKING -> stringResource(R.string.net_bypass_checking)
    BypassState.OK -> stringResource(if (returnTo != null) R.string.net_bypass_ok else R.string.net_conn_ok)
    BypassState.NO_NETWORK -> stringResource(R.string.net_bypass_no_network)
    BypassState.FAIL -> stringResource(R.string.net_bypass_fail, reason?.let { reasonText(it) } ?: stringResource(R.string.fail_no_gstatic))
}

@Composable
private fun reasonText(reason: FailReason): String = stringResource(
    when (reason) {
        FailReason.PROXY_DOWN -> R.string.fail_proxy_down
        FailReason.NO_GSTATIC -> R.string.fail_no_gstatic
        FailReason.TAMPERED -> R.string.fail_tampered
        FailReason.NO_DATA -> R.string.fail_no_data
        FailReason.IP_SAME -> R.string.fail_ip_same
        FailReason.UNSTABLE -> R.string.fail_unstable
    }
)

@Composable
private fun returnText(returnTo: String?): String? = when (returnTo) {
    null -> null
    NetInfoCache.RETURN_BEST -> stringResource(R.string.net_return_to_best)
    else -> stringResource(R.string.net_return_to, returnTo)
}
