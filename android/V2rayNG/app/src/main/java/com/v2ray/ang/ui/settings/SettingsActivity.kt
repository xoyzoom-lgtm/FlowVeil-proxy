package com.v2ray.ang.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleEventEffect
import com.v2ray.ang.handler.BatteryOptimization
import com.v2ray.ang.handler.BypassRating
import com.v2ray.ang.ui.compose.ConfirmDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.VPN
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.DeviceIdentity
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MmkvManager.rememberMmkvBool
import com.v2ray.ang.handler.MmkvManager.rememberMmkvString
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.main.MainDestination
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.CollapsiblePreferenceGroupHeader
import com.v2ray.ang.ui.compose.HappThemeManager
import com.v2ray.ang.ui.compose.HappThemePicker
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.PreferenceGroupHeader
import com.v2ray.ang.ui.compose.SettingsEditItem
import com.v2ray.ang.ui.compose.SettingsGroupCard
import com.v2ray.ang.ui.compose.SettingsListItem
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.ThemeManager
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.handler.DirectSites
import com.v2ray.ang.handler.RuleProfiles
import com.v2ray.ang.handler.ExtraRules
import com.v2ray.ang.ui.compose.SelectListDialog
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.net.ReturnLogic
import com.v2ray.ang.ui.compose.InputDialog
import com.v2ray.ang.ui.compose.InputField
import com.v2ray.ang.enums.RoutingType
import androidx.compose.ui.platform.LocalContext
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.launch

class SettingsActivity : BaseComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshSystemVpnSettingsAvailability()
            }
        }
    }

    private fun openSystemVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            reportSystemVpnSettingsFailure(error)
        } catch (error: SecurityException) {
            reportSystemVpnSettingsFailure(error)
        }
    }

    private fun reportSystemVpnSettingsFailure(error: RuntimeException) {
        LogUtil.e(AppConfig.TAG, "Cannot open system VPN settings", error)
        toastError(R.string.toast_system_vpn_settings_unavailable)
    }

    @Composable
    override fun ScreenContent() {
        SettingsScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onModeHelpClicked = { Utils.openUri(this, AppConfig.APP_WIKI_MODE) },
            onSystemVpnSettingsClicked = ::openSystemVpnSettings,
            onOpenSection = ::openSection
        )
    }

    private fun openSection(destination: MainDestination) {
        val target = when (destination) {
            MainDestination.Subscriptions -> SubSettingActivity::class.java
            MainDestination.PerAppProxy -> PerAppProxyActivity::class.java
            MainDestination.Routing -> RoutingSettingActivity::class.java
            MainDestination.UserAssets -> UserAssetActivity::class.java
            MainDestination.Logcat -> LogcatActivity::class.java
            MainDestination.CheckUpdate -> CheckUpdateActivity::class.java
            MainDestination.About -> AboutActivity::class.java
            MainDestination.Settings -> return
        }
        startActivity(Intent(this, target))
    }
}

/** Sub-screens reachable from the settings menu, in display order. */
private val SettingsSections = listOf(
    MainDestination.Routing,
    MainDestination.UserAssets,
    MainDestination.Logcat,
)

/** Technical screens shown only in developer mode. */
private val DevOnlySections = setOf(MainDestination.Routing, MainDestination.UserAssets, MainDestination.Logcat)

@OptIn(ExperimentalMaterial3Api::class)
/** Set when fragmentation was switched on by the bypass switch (not by hand). */
private const val FRAGMENT_BY_BYPASS = "fragment_enabled_by_bypass"

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    onModeHelpClicked: () -> Unit,
    onSystemVpnSettingsClicked: () -> Unit,
    onOpenSection: (MainDestination) -> Unit = {}
) {
    val scrollState = rememberScrollState()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val systemVpnSettingsAvailable by viewModel.systemVpnSettingsAvailable.collectAsStateWithLifecycle()
    var expertExpanded by rememberSaveable { mutableStateOf(false) }
    var vpnSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var coreSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var muxSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var fragmentSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var observatorySettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedSettingsExpanded by rememberSaveable { mutableStateOf(false) }
    var modeSettingsExpanded by rememberSaveable { mutableStateOf(false) }

    var localDns by rememberMmkvBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false)
    var fakeDns by rememberMmkvBool(AppConfig.PREF_FAKE_DNS_ENABLED, false)
    var appendHttpProxy by rememberMmkvBool(AppConfig.PREF_APPEND_HTTP_PROXY, false)
    var vpnDns by rememberMmkvString(AppConfig.PREF_VPN_DNS, "")
    var vpnBypassLan by rememberMmkvString(AppConfig.PREF_VPN_BYPASS_LAN, AppConfig.DEFAULT_VPN_BYPASS_LAN)
    var vpnInterfaceAddress by rememberMmkvString(AppConfig.PREF_VPN_INTERFACE_ADDRESS_CONFIG_INDEX, "0")
    var vpnMtu by rememberMmkvString(AppConfig.PREF_VPN_MTU, "")

    var mux by rememberMmkvBool(AppConfig.PREF_MUX_ENABLED, false)
    var muxConcurrency by rememberMmkvString(AppConfig.PREF_MUX_CONCURRENCY, "8")
    var muxXudpConcurrency by rememberMmkvString(AppConfig.PREF_MUX_XUDP_CONCURRENCY, AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY)
    var muxXudpQuic by rememberMmkvString(AppConfig.PREF_MUX_XUDP_QUIC, "reject")

    var fragment by rememberMmkvBool(AppConfig.PREF_FRAGMENT_ENABLED, false)
    var fragmentPackets by rememberMmkvString(AppConfig.PREF_FRAGMENT_PACKETS, "tlshello")
    var fragmentLength by rememberMmkvString(AppConfig.PREF_FRAGMENT_LENGTH, "50-100")
    var fragmentInterval by rememberMmkvString(AppConfig.PREF_FRAGMENT_INTERVAL, "10-20")
    var fragmentMaxSplit by rememberMmkvString(AppConfig.PREF_FRAGMENT_MAXSPLIT, "10")
    var observatoryLeastPingInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_PING_INTERVAL, AppConfig.OBSERVATORY_LEAST_PING_INTERVAL)
    var observatoryLeastLoadInterval by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_INTERVAL, AppConfig.OBSERVATORY_LEAST_LOAD_INTERVAL)
    var observatoryLeastLoadMethod by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_METHOD, AppConfig.OBSERVATORY_LEAST_LOAD_METHOD)
    var observatoryLeastLoadSampling by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_SAMPLING, AppConfig.OBSERVATORY_LEAST_LOAD_SAMPLING)
    var observatoryLeastLoadTimeout by rememberMmkvString(AppConfig.PREF_OBSERVATORY_LEAST_LOAD_TIMEOUT, AppConfig.OBSERVATORY_LEAST_LOAD_TIMEOUT)

    var mode by rememberMmkvString(AppConfig.PREF_MODE, VPN)
    var enableRootMode by rememberMmkvBool(AppConfig.PREF_ROOT_MODE_ENABLE, false)
    var lanSharing by rememberMmkvBool(AppConfig.PREF_ROOT_LAN_SHARING, false)

    var hevTunLogLevel by rememberMmkvString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL, AppConfig.DEFAULT_HEV_TUNNEL_LOGLEVEL)
    var hevTunRwTimeout by rememberMmkvString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT, "")
    var useHevTun by rememberMmkvBool(AppConfig.PREF_USE_HEV_TUNNEL, true)

    var enableLocalProxy by rememberMmkvBool(AppConfig.PREF_ENABLE_LOCAL_PROXY, true)
    var socksPort by rememberMmkvString(AppConfig.PREF_SOCKS_PORT, "")
    var dynamicSocksPort by rememberMmkvBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)
    var socksUsername by rememberMmkvString(AppConfig.PREF_SOCKS_USERNAME, "")
    var socksPassword by rememberMmkvString(AppConfig.PREF_SOCKS_PASSWORD, "")
    var socksEnableUdp by rememberMmkvBool(AppConfig.PREF_SOCKS_ENABLE_UDP, AppConfig.DEFAULT_SOCKS_ENABLE_UDP)
    var proxySharing by rememberMmkvBool(AppConfig.PREF_PROXY_SHARING, false)

    var speedEnabled by rememberMmkvBool(AppConfig.PREF_SPEED_ENABLED, false)
    var sendHwid by rememberMmkvBool(AppConfig.PREF_SEND_HWID, true)
    var automation by rememberMmkvBool(AppConfig.PREF_AUTOMATION_ENABLED, false)
    var clipboardOffer by rememberMmkvBool(AppConfig.PREF_CLIPBOARD_OFFER, true)
    var countryDirect by rememberMmkvString(ExtraRules.PREF_COUNTRY, com.v2ray.ang.net.CountryProfiles.NONE)
    var showHwid by remember { mutableStateOf(false) }
    var autoFailover by rememberMmkvBool(AppConfig.PREF_AUTO_FAILOVER, true)
    var failoverAcrossSubs by rememberMmkvBool(AppConfig.PREF_FAILOVER_ACROSS_SUBS, false)
    var whitelistBypass by rememberMmkvBool(WhitelistBypass.PREF_ENABLED, false)
    var whitelistBypassMode by rememberMmkvString(WhitelistBypass.PREF_MODE, WhitelistBypass.MODE_AUTO)
    var whitelistBypassReturn by rememberMmkvBool(WhitelistBypass.PREF_AUTO_RETURN, true)
    var whitelistBypassCount by remember { mutableStateOf(WhitelistBypass.servers().size) }
    var showBypassPicker by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showBypassLog by remember { mutableStateOf(false) }
    var noiseEnabled by rememberMmkvBool(AppConfig.PREF_NOISE_ENABLED, false)
    var noiseRand by rememberMmkvString(AppConfig.PREF_NOISE_RAND, "10-20")
    var noiseDelay by rememberMmkvString(AppConfig.PREF_NOISE_DELAY, "10-16")
    var bypassFallback by rememberMmkvString(WhitelistBypass.PREF_FALLBACK, WhitelistBypass.FALLBACK_NONE)
    var bypassPingBypass by rememberMmkvString(WhitelistBypass.PREF_BYPASS_PING, WhitelistBypass.DEFAULT_BYPASS_PING_MS.toString())
    var bypassSearchSeconds by rememberMmkvString(WhitelistBypass.PREF_SEARCH_SECONDS, WhitelistBypass.DEFAULT_SEARCH_SECONDS.toString())
    var bypassMasks by rememberMmkvString(BypassRating.PREF_MASKS, "")
    var bypassLabels by rememberMmkvString(BypassRating.PREF_LABELS, "")
    var batteryAllowed by remember { mutableStateOf(true) }
    var bypassBadMinutes by rememberMmkvString(WhitelistBypass.PREF_BAD_MINUTES, WhitelistBypass.DEFAULT_BAD_MINUTES.toString())
    var bypassPingLimit by rememberMmkvString(WhitelistBypass.PREF_PING_LIMIT, ReturnLogic.DEFAULT_PING_LIMIT_MS.toString())
    var bypassStableSeconds by rememberMmkvString(WhitelistBypass.PREF_STABLE_SECONDS, ReturnLogic.DEFAULT_STABLE_SECONDS.toString())
    var subReminders by rememberMmkvBool(AppConfig.PREF_SUB_REMINDERS, true)
    var ruleProfile by remember { mutableStateOf(RuleProfiles.current()) }
    var adBlock by rememberMmkvBool(ExtraRules.PREF_ADBLOCK, false)
    var telegramProxy by rememberMmkvBool(ExtraRules.PREF_TELEGRAM_PROXY, false)
    var showRuleProfiles by remember { mutableStateOf(false) }
    var directSitesCount by remember { mutableStateOf(DirectSites.count()) }
    var showDirectSites by remember { mutableStateOf(false) }
    var showBestButton by rememberMmkvBool(AppConfig.PREF_SHOW_BEST_BUTTON, true)
    var devMode by rememberMmkvBool(AppConfig.PREF_DEV_MODE, false)
    var subUpdateInterval by rememberMmkvString(AppConfig.PREF_SUB_UPDATE_INTERVAL, AppConfig.SUB_DEFAULT_UPDATE_MINUTES.toString())
    val subUpdateEntries = stringArrayResource(R.array.sub_update_interval_entries).toList()
    val subUpdateValues = stringArrayResource(R.array.sub_update_interval_values).toList()
    val settingsContext = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryAllowed = BatteryOptimization.isIgnored(settingsContext) }
    val deviceHwid = remember { DeviceIdentity.hwid() }
    var confirmRemove by rememberMmkvBool(AppConfig.PREF_CONFIRM_REMOVE, false)
    var language by remember {
        mutableStateOf(
            MmkvManager.decodeSettingsString(AppConfig.PREF_LANGUAGE, "auto") ?: "auto"
        )
    }
    var uiModeNight by rememberMmkvString(AppConfig.PREF_UI_MODE_NIGHT, "0")
    var dynamicColor by rememberMmkvBool(AppConfig.PREF_DYNAMIC_COLOR, true)

    var ipv6Enabled by rememberMmkvBool(AppConfig.PREF_IPV6_ENABLED, false)
    var preferIpv6 by rememberMmkvBool(AppConfig.PREF_PREFER_IPV6, false)
    var sniffingEnabled by rememberMmkvBool(AppConfig.PREF_SNIFFING_ENABLED, true)
    var routeOnlyEnabled by rememberMmkvBool(AppConfig.PREF_ROUTE_ONLY_ENABLED, false)
    var remoteDns by rememberMmkvString(AppConfig.PREF_REMOTE_DNS, "")
    var domesticDns by rememberMmkvString(AppConfig.PREF_DOMESTIC_DNS, "")
    var dnsHosts by rememberMmkvString(AppConfig.PREF_DNS_HOSTS, "")
    var coreLogLevel by rememberMmkvString(AppConfig.PREF_LOGLEVEL, "warning")
    var outboundResolveMethod by rememberMmkvString(AppConfig.PREF_OUTBOUND_DOMAIN_RESOLVE_METHOD, AppConfig.DEFAULT_OUTBOUND_DOMAIN_RESOLVE_METHOD)

    var isBooted by rememberMmkvBool(AppConfig.PREF_IS_BOOTED, false)
    var delayTestUrl by rememberMmkvString(AppConfig.PREF_DELAY_TEST_URL, "")
    var realPingConcurrency by rememberMmkvString(AppConfig.PREF_REAL_PING_CONCURRENCY, "16")
    var ipApiUrl by rememberMmkvString(AppConfig.PREF_IP_API_URL, "")

    val isVpn = mode == VPN
    val hevTunEnabled = isVpn && useHevTun
    val localProxyForced = hevTunEnabled
    val effectiveLocalProxy = enableLocalProxy || localProxyForced
    val muxXudpConcurrencyInt = muxXudpConcurrency.toIntOrNull() ?: AppConfig.DEFAULT_MUX_XUDP_CONCURRENCY.toInt()

    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    LaunchedEffect(dynamicColorSupported) {
        if (!dynamicColorSupported && dynamicColor) {
            dynamicColor = false
            ThemeManager.setDynamicColorEnabled(false)
        }
    }

    val languageEntries = stringArrayResource(R.array.language_select).toList()
    val languageValues = stringArrayResource(R.array.language_select_value).toList()
    val uiModeNightEntries = stringArrayResource(R.array.ui_mode_night).toList()
    val uiModeNightValues = stringArrayResource(R.array.ui_mode_night_value).toList()
    val bypassLanEntries = stringArrayResource(R.array.vpn_bypass_lan).toList()
    val bypassLanValues = stringArrayResource(R.array.vpn_bypass_lan_value).toList()
    val interfaceAddrEntries = stringArrayResource(R.array.vpn_interface_address).toList()
    val interfaceAddrValues = stringArrayResource(R.array.vpn_interface_address_value).toList()
    val hevLogEntries = stringArrayResource(R.array.hev_tunnel_loglevel).toList()
    val hevLogValues = stringArrayResource(R.array.hev_tunnel_loglevel).toList()
    val coreLogLevelEntries = stringArrayResource(R.array.core_loglevel).toList()
    val coreLogLevelValues = stringArrayResource(R.array.core_loglevel).toList()
    val outboundResolveEntries = stringArrayResource(R.array.outbound_domain_resolve_method).toList()
    val outboundResolveValues = stringArrayResource(R.array.outbound_domain_resolve_method_value).toList()
    val xudpQuicEntries = stringArrayResource(R.array.mux_xudp_quic_entries).toList()
    val xudpQuicValues = stringArrayResource(R.array.mux_xudp_quic_value).toList()
    val fragmentPacketsEntries = stringArrayResource(R.array.fragment_packets).toList()
    val fragmentPacketsValues = stringArrayResource(R.array.fragment_packets).toList()
    val observatoryLeastLoadMethodEntries = stringArrayResource(R.array.observatory_least_load_method).toList()
    val observatoryLeastLoadMethodValues = stringArrayResource(R.array.observatory_least_load_method).toList()
    val modeEntries = stringArrayResource(R.array.mode_entries).toList()
    val modeValues = stringArrayResource(R.array.mode_value).toList()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.title_settings),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScrollbar(scrollState)
                .verticalScroll(scrollState)
        ) {
            PreferenceGroupHeader(title = stringResource(R.string.settings_section_sections))
            SettingsGroupCard {
                SettingsSections.filter { devMode || it !in DevOnlySections }.forEach { section ->
                    SettingsMenuItem(
                        icon = painterResource(section.iconRes),
                        title = stringResource(section.labelRes),
                        onClick = { onOpenSection(section) }
                    )
                }
            }

            // Network diagnostics are for troubleshooting: developer mode only (the auto bypass itself is unaffected).
            if (devMode) NetStatusCard(devMode = true)

            PreferenceGroupHeader(title = stringResource(R.string.settings_section_connection))
            SettingsGroupCard {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_noise),
                    summary = stringResource(R.string.summary_pref_noise),
                    checked = noiseEnabled,
                    onCheckedChange = { noiseEnabled = it }
                )
                if (noiseEnabled && devMode) {
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_noise_rand),
                        value = noiseRand,
                        onValueChanged = { noiseRand = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_noise_delay),
                        value = noiseDelay,
                        onValueChanged = { noiseDelay = it }
                    )
                }
                SettingsSwitchItem(
                    title = stringResource(R.string.title_whitelist_bypass),
                    summary = stringResource(R.string.summary_whitelist_bypass),
                    checked = whitelistBypass,
                    onCheckedChange = {
                        whitelistBypass = it
                        if (it) {
                            // Fragmenting the first packets helps the servers that pass a restricted network: turn it on together
                            // with the bypass and remember that we did, so turning the bypass off puts it back.
                            if (!fragment) {
                                fragment = true
                                MmkvManager.encodeSettings(FRAGMENT_BY_BYPASS, true)
                            }
                            if (!batteryAllowed) showBatteryDialog = true
                        } else if (MmkvManager.decodeSettingsBool(FRAGMENT_BY_BYPASS, false)) {
                            fragment = false
                            MmkvManager.encodeSettings(FRAGMENT_BY_BYPASS, false)
                        }
                    }
                )
                if (whitelistBypass) {
                    SettingsMenuItem(
                        title = stringResource(R.string.title_bypass_battery),
                        subtitle = stringResource(if (batteryAllowed) R.string.summary_bypass_battery_on else R.string.summary_bypass_battery_off),
                        onClick = { BatteryOptimization.request(settingsContext) }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_whitelist_bypass_mode),
                        entries = listOf(
                            stringResource(R.string.whitelist_bypass_mode_auto),
                            stringResource(R.string.whitelist_bypass_mode_manual),
                        ),
                        values = listOf(WhitelistBypass.MODE_AUTO, WhitelistBypass.MODE_MANUAL),
                        selectedValue = whitelistBypassMode,
                        onSelected = { whitelistBypassMode = it }
                    )
                    if (whitelistBypassMode == WhitelistBypass.MODE_AUTO) {
                        SettingsMenuItem(
                            title = stringResource(R.string.summary_whitelist_bypass_auto),
                            onClick = {}
                        )
                        SettingsListItem(
                            title = stringResource(R.string.title_bypass_fallback),
                            entries = listOf(
                                stringResource(R.string.bypass_fallback_none),
                                stringResource(R.string.bypass_fallback_all),
                            ),
                            values = listOf(WhitelistBypass.FALLBACK_NONE, WhitelistBypass.FALLBACK_ALL),
                            selectedValue = bypassFallback,
                            onSelected = { bypassFallback = it }
                        )
                    }
                    BypassStatusItems()
                    if (whitelistBypassMode == WhitelistBypass.MODE_MANUAL) {
                        SettingsMenuItem(
                            title = stringResource(R.string.title_whitelist_bypass_servers),
                            subtitle = stringResource(R.string.summary_whitelist_bypass_servers, whitelistBypassCount),
                            onClick = { showBypassPicker = true }
                        )
                    }
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_whitelist_bypass_auto_return),
                        summary = stringResource(R.string.summary_whitelist_bypass_auto_return),
                        checked = whitelistBypassReturn,
                        onCheckedChange = { whitelistBypassReturn = it }
                    )
                    if (devMode) {
                        SettingsMenuItem(
                            title = stringResource(R.string.title_bypass_log),
                            subtitle = stringResource(R.string.summary_bypass_log),
                            onClick = { showBypassLog = true }
                        )
                        SettingsListItem(
                            title = stringResource(R.string.title_bypass_ping_bypass),
                            entries = listOf("1000", "1500", "2500", "4000"),
                            values = listOf("1000", "1500", "2500", "4000"),
                            selectedValue = bypassPingBypass,
                            onSelected = { bypassPingBypass = it }
                        )
                        SettingsListItem(
                            title = stringResource(R.string.title_bypass_search_seconds),
                            entries = listOf("20", "40", "60", "120"),
                            values = listOf("20", "40", "60", "120"),
                            selectedValue = bypassSearchSeconds,
                            onSelected = { bypassSearchSeconds = it }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.title_bypass_masks),
                            value = bypassMasks,
                            onValueChanged = { bypassMasks = it }
                        )
                        SettingsEditItem(
                            title = stringResource(R.string.title_bypass_labels),
                            value = bypassLabels,
                            onValueChanged = { bypassLabels = it }
                        )
                        SettingsListItem(
                            title = stringResource(R.string.title_bypass_bad_minutes),
                            entries = listOf("5", "10", "20", "60"),
                            values = listOf("5", "10", "20", "60"),
                            selectedValue = bypassBadMinutes,
                            onSelected = { bypassBadMinutes = it }
                        )
                        SettingsListItem(
                            title = stringResource(R.string.title_bypass_ping_limit),
                            entries = listOf("250", "400", "600", "1000"),
                            values = listOf("250", "400", "600", "1000"),
                            selectedValue = bypassPingLimit,
                            onSelected = { bypassPingLimit = it }
                        )
                        SettingsListItem(
                            title = stringResource(R.string.title_bypass_stable_seconds),
                            entries = listOf("5", "8", "15", "30"),
                            values = listOf("5", "8", "15", "30"),
                            selectedValue = bypassStableSeconds,
                            onSelected = { bypassStableSeconds = it }
                        )
                    }
                }
                if (showBypassLog) BypassLogDialog(onDismiss = { showBypassLog = false })
                if (showBatteryDialog) {
                    ConfirmDialog(
                        title = stringResource(R.string.bypass_battery_dialog_title),
                        message = stringResource(R.string.bypass_battery_dialog_message),
                        confirmText = stringResource(R.string.bypass_battery_allow),
                        dismissText = stringResource(R.string.bypass_battery_later),
                        onConfirm = { BatteryOptimization.request(settingsContext) },
                        onDismiss = { showBatteryDialog = false }
                    )
                }
                if (showBypassPicker) {
                    BypassServerPickerDialog(
                        onDismiss = { showBypassPicker = false },
                        onSaved = { count ->
                            whitelistBypassCount = count
                            showBypassPicker = false
                        }
                    )
                }
            }

            PreferenceGroupHeader(title = stringResource(R.string.settings_section_developer))
            SettingsGroupCard {
                SettingsSwitchItem(
                    title = stringResource(R.string.title_pref_dev_mode),
                    summary = stringResource(R.string.summary_pref_dev_mode),
                    checked = devMode,
                    onCheckedChange = { devMode = it }
                )
            }

            // Core, DNS, routing and other technical options: developer mode only.
            if (devMode) {
            PreferenceGroupHeader(title = stringResource(R.string.settings_section_core))
            SettingsGroupCard {
                SettingsEditItem(
                    title = stringResource(R.string.title_pref_delay_test_url),
                    value = delayTestUrl,
                    onValueChanged = { delayTestUrl = it }
                )
                SettingsListItem(
                    title = stringResource(R.string.title_core_loglevel),
                    entries = coreLogLevelEntries,
                    values = coreLogLevelValues,
                    selectedValue = coreLogLevel,
                    onSelected = { coreLogLevel = it }
                )
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.settings_section_expert),
                expanded = expertExpanded,
                onExpandedChange = { expertExpanded = it }
            )
            if (expertExpanded) {
            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_vpn_settings),
                expanded = vpnSettingsExpanded,
                onExpandedChange = { vpnSettingsExpanded = it }
            )
            if (vpnSettingsExpanded) {
                SettingsGroupCard {
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_ipv6_enabled),
                        summary = stringResource(R.string.summary_pref_ipv6_enabled),
                        checked = ipv6Enabled,
                        onCheckedChange = { ipv6Enabled = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_prefer_ipv6),
                        summary = stringResource(R.string.summary_pref_prefer_ipv6),
                        checked = preferIpv6,
                        onCheckedChange = { preferIpv6 = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_local_dns_enabled),
                        summary = stringResource(R.string.summary_pref_local_dns_enabled),
                        checked = localDns,
                        enabled = isVpn,
                        onCheckedChange = { localDns = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_fake_dns_enabled),
                        summary = stringResource(R.string.summary_pref_fake_dns_enabled),
                        checked = fakeDns,
                        enabled = isVpn && localDns,
                        onCheckedChange = { fakeDns = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_vpn_dns),
                        value = vpnDns,
                        enabled = isVpn && !localDns,
                        onValueChanged = { vpnDns = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_append_http_proxy),
                        summary = stringResource(R.string.summary_pref_append_http_proxy),
                        checked = appendHttpProxy,
                        enabled = effectiveLocalProxy,
                        onCheckedChange = { appendHttpProxy = it }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_pref_vpn_bypass_lan),
                        entries = bypassLanEntries,
                        values = bypassLanValues,
                        selectedValue = vpnBypassLan,
                        enabled = isVpn,
                        onSelected = { vpnBypassLan = it }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_pref_vpn_interface_address),
                        entries = interfaceAddrEntries,
                        values = interfaceAddrValues,
                        selectedValue = vpnInterfaceAddress,
                        enabled = isVpn,
                        onSelected = { vpnInterfaceAddress = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_vpn_mtu),
                        value = vpnMtu,
                        enabled = isVpn,
                        keyboardNumber = true,
                        onValueChanged = { vpnMtu = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_use_hev_tunnel),
                        summary = stringResource(R.string.summary_pref_use_hev_tunnel),
                        checked = useHevTun,
                        enabled = isVpn,
                        onCheckedChange = {
                            useHevTun = it
                            if (it && !enableLocalProxy) {
                                enableLocalProxy = true
                            }
                        }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_pref_hev_tunnel_loglevel),
                        entries = hevLogEntries,
                        values = hevLogValues,
                        selectedValue = hevTunLogLevel,
                        enabled = hevTunEnabled,
                        onSelected = { hevTunLogLevel = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_hev_tunnel_rw_timeout),
                        value = hevTunRwTimeout,
                        enabled = hevTunEnabled,
                        keyboardNumber = true,
                        onValueChanged = { hevTunRwTimeout = it }
                    )
                }
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_core_settings),
                expanded = coreSettingsExpanded,
                onExpandedChange = { coreSettingsExpanded = it }
            )
            if (coreSettingsExpanded) {
                SettingsGroupCard {
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_sniffing_enabled),
                        summary = stringResource(R.string.summary_pref_sniffing_enabled),
                        checked = sniffingEnabled,
                        onCheckedChange = { sniffingEnabled = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_route_only_enabled),
                        summary = stringResource(R.string.summary_pref_route_only_enabled),
                        checked = routeOnlyEnabled,
                        onCheckedChange = { routeOnlyEnabled = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_enable_local_proxy),
                        summary = stringResource(R.string.summary_pref_enable_local_proxy),
                        checked = enableLocalProxy,
                        enabled = !localProxyForced,
                        onCheckedChange = {
                            if (!localProxyForced) {
                                enableLocalProxy = it
                                if (!it && appendHttpProxy) {
                                    appendHttpProxy = false
                                }
                            }
                        }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_proxy_sharing_enabled),
                        summary = stringResource(R.string.summary_pref_proxy_sharing_enabled),
                        checked = proxySharing,
                        enabled = effectiveLocalProxy,
                        onCheckedChange = { proxySharing = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_dynamic_socks_port),
                        summary = stringResource(R.string.summary_pref_dynamic_socks_port),
                        checked = dynamicSocksPort,
                        enabled = effectiveLocalProxy,
                        onCheckedChange = { dynamicSocksPort = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_socks_port),
                        value = socksPort,
                        enabled = effectiveLocalProxy && !dynamicSocksPort,
                        keyboardNumber = true,
                        onValueChanged = { socksPort = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_socks_username),
                        value = socksUsername,
                        enabled = effectiveLocalProxy,
                        onValueChanged = { socksUsername = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_socks_password),
                        value = socksPassword,
                        enabled = effectiveLocalProxy,
                        isPassword = true,
                        onValueChanged = { socksPassword = it }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_socks_enable_udp),
                        summary = stringResource(R.string.summary_pref_socks_enable_udp),
                        checked = socksEnableUdp,
                        enabled = effectiveLocalProxy,
                        onCheckedChange = { socksEnableUdp = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_remote_dns),
                        value = remoteDns,
                        onValueChanged = { remoteDns = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_domestic_dns),
                        value = domesticDns,
                        onValueChanged = { domesticDns = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_dns_hosts),
                        value = dnsHosts,
                        onValueChanged = { dnsHosts = it }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_outbound_domain_resolve_method),
                        entries = outboundResolveEntries,
                        values = outboundResolveValues,
                        selectedValue = outboundResolveMethod,
                        onSelected = { outboundResolveMethod = it }
                    )
                }
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_mux_settings),
                expanded = muxSettingsExpanded,
                onExpandedChange = { muxSettingsExpanded = it }
            )
            if (muxSettingsExpanded) {
                SettingsGroupCard {
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_mux_enabled),
                        summary = stringResource(R.string.summary_pref_mux_enabled),
                        checked = mux,
                        onCheckedChange = { mux = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_mux_concurrency),
                        value = muxConcurrency,
                        enabled = mux,
                        keyboardNumber = true,
                        onValueChanged = { muxConcurrency = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_mux_xudp_concurrency),
                        value = muxXudpConcurrency,
                        enabled = mux,
                        keyboardNumber = true,
                        onValueChanged = { muxXudpConcurrency = it }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_pref_mux_xudp_quic),
                        entries = xudpQuicEntries,
                        values = xudpQuicValues,
                        selectedValue = muxXudpQuic,
                        enabled = mux && muxXudpConcurrencyInt >= 0,
                        onSelected = { muxXudpQuic = it }
                    )
                }
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_fragment_settings),
                expanded = fragmentSettingsExpanded,
                onExpandedChange = { fragmentSettingsExpanded = it }
            )
            if (fragmentSettingsExpanded) {
                SettingsGroupCard {
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_pref_fragment_enabled),
                        checked = fragment,
                        onCheckedChange = {
                            fragment = it
                            // The user decided by hand: turning the bypass off must not undo it.
                            MmkvManager.encodeSettings(FRAGMENT_BY_BYPASS, false)
                        }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_pref_fragment_packets),
                        entries = fragmentPacketsEntries,
                        values = fragmentPacketsValues,
                        selectedValue = fragmentPackets,
                        enabled = fragment,
                        onSelected = { fragmentPackets = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_fragment_length),
                        value = fragmentLength,
                        enabled = fragment,
                        onValueChanged = { fragmentLength = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_fragment_interval),
                        value = fragmentInterval,
                        enabled = fragment,
                        onValueChanged = { fragmentInterval = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_fragment_maxsplit),
                        value = fragmentMaxSplit,
                        enabled = fragment,
                        keyboardNumber = true,
                        onValueChanged = { fragmentMaxSplit = it }
                    )
                }
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_observatory_settings),
                expanded = observatorySettingsExpanded,
                onExpandedChange = { observatorySettingsExpanded = it }
            )
            if (observatorySettingsExpanded) {
                SettingsGroupCard {
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_observatory_least_ping_interval),
                        value = observatoryLeastPingInterval,
                        onValueChanged = {
                            viewModel.validateObservatoryDuration(it)?.let { value ->
                                observatoryLeastPingInterval = value
                            }
                        }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_observatory_least_load_interval),
                        value = observatoryLeastLoadInterval,
                        onValueChanged = {
                            viewModel.validateObservatoryDuration(it)?.let { value ->
                                observatoryLeastLoadInterval = value
                            }
                        }
                    )
                    SettingsListItem(
                        title = stringResource(R.string.title_pref_observatory_least_load_method),
                        entries = observatoryLeastLoadMethodEntries,
                        values = observatoryLeastLoadMethodValues,
                        selectedValue = observatoryLeastLoadMethod,
                        onSelected = { observatoryLeastLoadMethod = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_observatory_least_load_sampling),
                        value = observatoryLeastLoadSampling,
                        keyboardNumber = true,
                        onValueChanged = {
                            viewModel.validateObservatorySampling(it)?.let { value ->
                                observatoryLeastLoadSampling = value
                            }
                        }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_observatory_least_load_timeout),
                        value = observatoryLeastLoadTimeout,
                        onValueChanged = {
                            viewModel.validateObservatoryDuration(it)?.let { value ->
                                observatoryLeastLoadTimeout = value
                            }
                        }
                    )
                }
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_advanced),
                expanded = advancedSettingsExpanded,
                onExpandedChange = { advancedSettingsExpanded = it }
            )
            if (advancedSettingsExpanded) {
                SettingsGroupCard {
                    if (systemVpnSettingsAvailable) {
                        SettingsMenuItem(
                            title = stringResource(R.string.title_system_vpn_settings),
                            subtitle = stringResource(R.string.summary_system_vpn_settings),
                            onClick = onSystemVpnSettingsClicked
                        )
                    }
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_real_ping_concurrency),
                        value = realPingConcurrency,
                        keyboardNumber = true,
                        onValueChanged = { realPingConcurrency = it }
                    )
                    SettingsEditItem(
                        title = stringResource(R.string.title_pref_ip_api_url),
                        value = ipApiUrl,
                        onValueChanged = { ipApiUrl = it }
                    )
                }
            }

            CollapsiblePreferenceGroupHeader(
                title = stringResource(R.string.title_mode_settings),
                expanded = modeSettingsExpanded,
                onExpandedChange = { modeSettingsExpanded = it }
            )
            if (modeSettingsExpanded) {
                SettingsGroupCard {
                    SettingsListItem(
                        title = stringResource(R.string.title_mode),
                        entries = modeEntries,
                        values = modeValues,
                        selectedValue = mode,
                        onSelected = { mode = it }
                    )
                    SettingsMenuItem(
                        title = stringResource(R.string.title_mode_help),
                        onClick = onModeHelpClicked
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_root_mode_enabled),
                        summary = stringResource(R.string.summary_root_mode_enabled),
                        checked = enableRootMode,
                        onCheckedChange = { newValue ->
                            if (newValue && !RootManager.cachedRoot()) {
                                viewModel.checkAndRequestRoot {
                                    enableRootMode = true
                                }
                            } else {
                                enableRootMode = newValue
                            }
                        }
                    )
                    SettingsSwitchItem(
                        title = stringResource(R.string.title_root_lan_sharing),
                        summary = stringResource(R.string.summary_root_lan_sharing),
                        checked = lanSharing,
                        onCheckedChange = { newValue ->
                            if (newValue && !RootManager.cachedRoot()) {
                                viewModel.checkAndRequestRoot {
                                    lanSharing = true
                                }
                            } else {
                                lanSharing = newValue
                            }
                        }
                    )
                }
            }

            }
            }

            Spacer(modifier = Modifier.height(24.dp))
            NavigationBarsSpacer()
        }
    }
}

private fun ruleProfileTitle(profile: RuleProfiles.Profile): Int = when (profile) {
    RuleProfiles.Profile.RU_DIRECT -> R.string.rule_profile_ru
    RuleProfiles.Profile.ALL_PROXY -> R.string.rule_profile_all
    RuleProfiles.Profile.ALL_DIRECT -> R.string.rule_profile_direct
}

private fun ruleProfileHint(profile: RuleProfiles.Profile): Int = when (profile) {
    RuleProfiles.Profile.RU_DIRECT -> R.string.rule_profile_ru_hint
    RuleProfiles.Profile.ALL_PROXY -> R.string.rule_profile_all_hint
    RuleProfiles.Profile.ALL_DIRECT -> R.string.rule_profile_direct_hint
}
