package com.v2ray.ang.ui.main.home

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.ExtraRules
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.RuleProfiles
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.net.CountryProfiles
import com.v2ray.ang.ui.main.MainBackground
import com.v2ray.ang.ui.main.MainDestination
import com.v2ray.ang.util.Utils

/**
 * Top-level settings of the new design (mockup #scrSettings): the everyday switches grouped in five blocks.
 * Everything else stays in the full settings ("Для опытных"), nothing is removed.
 */
@Composable
internal fun HomeSettings(
    onClose: () -> Unit,
    onNavigate: (MainDestination) -> Unit,
    onWizard: () -> Unit,
    /** Called after a routing switch changed: the screen restarts a running connection so it takes effect now. */
    onRulesChanged: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val russian = LocalConfiguration.current.locales[0].language == "ru"
    var ads by remember { mutableStateOf(ExtraRules.adBlock()) }
    var tg by remember { mutableStateOf(ExtraRules.telegramProxy()) }
    var fast by remember { mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_FAST_MODE, false)) }
    var country by remember { mutableStateOf(MmkvManager.decodeSettingsString(ExtraRules.PREF_COUNTRY) ?: CountryProfiles.NONE) }
    var profile by remember { mutableStateOf(RuleProfiles.current()) }
    var picker by remember { mutableStateOf<String?>(null) }
    val subs = remember { MmkvManager.decodeSubscriptions().size }
    val servers = remember { MmkvManager.decodeAllServerList().size }
    val bypass = remember { WhitelistBypass.servers().size }
    val changed = {
        ExtraRules.apply()
        onRulesChanged()
    }

    Box(Modifier.fillMaxSize()) {
        MainBackground()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            ) {
                RoundAction(R.drawable.ic_arrow_back_24dp, stringResource(R.string.onb_back), onClose, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.home_settings), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
            }
            Column(
                Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Group(stringResource(R.string.hs_group_subs)) {
                    NavRow(R.drawable.ic_subscriptions_24dp, stringResource(R.string.hs_subscriptions), stringResource(R.string.hs_subscriptions_sub, subs, servers)) {
                        onNavigate(MainDestination.Subscriptions)
                    }
                    NavRow(R.drawable.ic_per_apps_24dp, stringResource(R.string.hs_apps), stringResource(R.string.hs_apps_sub)) {
                        onNavigate(MainDestination.PerAppProxy)
                    }
                    NavRow(R.drawable.ic_flash_on_24dp, stringResource(R.string.hs_bypass), stringResource(R.string.hs_bypass_sub, bypass)) {
                        onNavigate(MainDestination.Settings)
                    }
                }
                Group(stringResource(R.string.hs_group_traffic)) {
                    NavRow(
                        R.drawable.ic_routing_24dp,
                        stringResource(R.string.title_rule_profile),
                        stringResource(
                            when (profile) {
                                RuleProfiles.Profile.RU_DIRECT -> R.string.rule_profile_ru
                                RuleProfiles.Profile.ALL_PROXY -> R.string.rule_profile_all
                                RuleProfiles.Profile.ALL_DIRECT -> R.string.rule_profile_direct
                                null -> R.string.rule_profile_custom
                            }
                        )
                    ) { picker = "profile" }
                    NavRow(R.drawable.ic_language_24dp, stringResource(R.string.title_country_direct), CountryProfiles.label(country, stringResource(R.string.country_none), russian)) {
                        picker = "country"
                    }
                    SwitchRow2(R.drawable.ic_status_cross, stringResource(R.string.title_pref_adblock), stringResource(R.string.onb3_ads_sub), ads) {
                        ads = it
                        MmkvManager.encodeSettings(ExtraRules.PREF_ADBLOCK, it)
                        changed()
                    }
                    SwitchRow2(R.drawable.ic_flash_on_24dp, stringResource(R.string.title_fast_mode), stringResource(R.string.summary_fast_mode), fast) {
                        fast = it
                        MmkvManager.encodeSettings(AppConfig.PREF_FAST_MODE, it)
                        onRulesChanged()
                    }
                    SwitchRow2(R.drawable.ic_telegram_24dp, stringResource(R.string.title_pref_telegram_proxy), stringResource(R.string.onb3_tg_sub), tg) {
                        tg = it
                        MmkvManager.encodeSettings(ExtraRules.PREF_TELEGRAM_PROXY, it)
                        changed()
                    }
                }
                Group(stringResource(R.string.hs_group_look)) {
                    NavRow(R.drawable.ic_image_24dp, stringResource(R.string.title_happ_themes), stringResource(R.string.hs_theme_sub)) { onNavigate(MainDestination.Settings) }
                    NavRow(R.drawable.ic_translate_24dp, stringResource(R.string.title_language), if (russian) "Русский" else "English") { onNavigate(MainDestination.Settings) }
                }
                Group(stringResource(R.string.hs_group_about)) {
                    NavRow(R.drawable.ic_telegram_24dp, stringResource(R.string.hs_channel), "t.me/FlowVeil") { Utils.openUri(context, "https://t.me/FlowVeil") }
                    NavRow(R.drawable.ic_check_update_24dp, stringResource(R.string.hs_check_update), "build ${BuildConfig.HUPP_BUILD}") { onNavigate(MainDestination.CheckUpdate) }
                    NavRow(R.drawable.ic_play_24dp, stringResource(R.string.title_onboarding_again), stringResource(R.string.summary_onboarding_again)) { onWizard() }
                    NavRow(R.drawable.ic_backup_24dp, stringResource(R.string.hs_backup), null) { onNavigate(MainDestination.BackupRestore) }
                    NavRow(R.drawable.ic_settings_24dp, stringResource(R.string.hs_expert), stringResource(R.string.hs_expert_sub)) { onNavigate(MainDestination.Settings) }
                    NavRow(R.drawable.ic_about_24dp, stringResource(R.string.title_about), null) { onNavigate(MainDestination.About) }
                }
            }
        }
    }

    when (picker) {
        "profile" -> HomeSheet(onDismiss = { picker = null }) {
            Text(stringResource(R.string.title_rule_profile), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            listOf(
                RuleProfiles.Profile.RU_DIRECT to (R.string.rule_profile_ru to R.string.rule_profile_ru_hint),
                RuleProfiles.Profile.ALL_PROXY to (R.string.rule_profile_all to R.string.rule_profile_all_hint),
                RuleProfiles.Profile.ALL_DIRECT to (R.string.rule_profile_direct to R.string.rule_profile_direct_hint),
            ).forEach { (p, texts) ->
                SheetRow(if (p == profile) R.drawable.ic_action_done else null, stringResource(texts.first), stringResource(texts.second)) {
                    RuleProfiles.apply(context, p)
                    onRulesChanged()
                    profile = p
                    picker = null
                }
            }
        }
        "country" -> HomeSheet(onDismiss = { picker = null }) {
            Text(stringResource(R.string.title_country_direct), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
            Text(stringResource(R.string.hs_country_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            val pick: (String) -> Unit = { id ->
                country = id
                MmkvManager.encodeSettings(ExtraRules.PREF_COUNTRY, id)
                changed()
                picker = null
            }
            SheetRow(if (country == CountryProfiles.NONE) R.drawable.ic_action_done else null, stringResource(R.string.country_none)) { pick(CountryProfiles.NONE) }
            CountryProfiles.ALL.forEach { c ->
                SheetRow(if (country == c.id) R.drawable.ic_action_done else null, "${c.flag} ${if (russian) c.nameRu else c.nameEn}") { pick(c.id) }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp)
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(homeSurface())
    ) {
        content()
    }
}

@Composable
private fun RowShell(iconRes: Int, title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(homeAccent().copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(painterResource(iconRes), null, tint = homeAccent(), modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
    HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
}

@Composable
private fun NavRow(iconRes: Int, title: String, subtitle: String?, onClick: () -> Unit) =
    RowShell(iconRes, title, subtitle, onClick) {
        Icon(
            painterResource(R.drawable.ic_chevron_right_24dp), null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(20.dp)
        )
    }

@Composable
private fun SwitchRow2(iconRes: Int, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    RowShell(iconRes, title, subtitle, { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }

