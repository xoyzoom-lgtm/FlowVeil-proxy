package com.v2ray.ang.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.ExtraRules
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.RuleProfiles
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.ui.main.MainAction
import com.v2ray.ang.ui.main.MainBackground
import com.v2ray.ang.ui.main.home.SheetRow
import com.v2ray.ang.ui.main.home.homeAccent
import com.v2ray.ang.ui.main.home.homeSurface
import com.v2ray.ang.ui.main.mainOnAccentColor

/**
 * First-run wizard, three steps, can be skipped: add a key, how to send traffic, the main switches.
 * Nothing is turned on silently: every switch starts in the state the user already has (ads and Telegram off).
 */
@Composable
fun OnboardingScreen(onAction: (MainAction) -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var rules by rememberSaveable { mutableStateOf(RuleProfiles.current() == RuleProfiles.Profile.RU_DIRECT) }
    var ads by rememberSaveable { mutableStateOf(ExtraRules.adBlock()) }
    var auto by rememberSaveable { mutableStateOf(MmkvManager.decodeSettingsBool(WhitelistBypass.PREF_ENABLED, false)) }
    var tg by rememberSaveable { mutableStateOf(ExtraRules.telegramProxy()) }
    var notify by rememberSaveable { mutableStateOf(UpdateNotifier.isEnabled()) }

    val finish = {
        val profile = if (rules) RuleProfiles.Profile.RU_DIRECT else RuleProfiles.Profile.ALL_PROXY
        if (RuleProfiles.current() != profile) RuleProfiles.apply(context, profile)
        MmkvManager.encodeSettings(ExtraRules.PREF_ADBLOCK, ads)
        MmkvManager.encodeSettings(ExtraRules.PREF_TELEGRAM_PROXY, tg)
        ExtraRules.apply()
        MmkvManager.encodeSettings(WhitelistBypass.PREF_ENABLED, auto)
        UpdateNotifier.setEnabled(context, notify)
        onFinish()
    }

    Box(Modifier.fillMaxSize()) {
        MainBackground()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                    repeat(3) { i ->
                        Box(
                            Modifier
                                .height(6.dp)
                                .width(if (i == step) 28.dp else 14.dp)
                                .clip(CircleShape)
                                .background(if (i <= step) homeAccent() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        )
                    }
                }
                val (title, text) = when (step) {
                    0 -> R.string.onb1_title to R.string.onb1_text
                    1 -> R.string.onb2_title to R.string.onb2_text
                    else -> R.string.onb3_title to R.string.onb3_text
                }
                Text(stringResource(title), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(text), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                when (step) {
                    0 -> {
                        // Each way of adding moves on: the import itself still asks for confirmation.
                        val go: (MainAction) -> Unit = { onAction(it); step = 1 }
                        SheetRow(R.drawable.ic_copy, stringResource(R.string.home_add_paste)) { go(MainAction.ImportClipboard) }
                        SheetRow(R.drawable.ic_scan_24dp, stringResource(R.string.home_add_scan)) { go(MainAction.ImportQRcode) }
                        SheetRow(R.drawable.ic_cloud_download_24dp, stringResource(R.string.home_add_transfer)) { go(MainAction.OpenMigration) }
                        SheetRow(R.drawable.ic_edit_24dp, stringResource(R.string.home_add_manual)) {
                            go(MainAction.ImportManually(com.v2ray.ang.enums.EConfigType.VLESS.value))
                        }
                        Text(
                            stringResource(R.string.home_add_caption),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                        )
                    }
                    1 -> {
                        ModeOption(stringResource(R.string.onb2_all), stringResource(R.string.onb2_all_sub), !rules) { rules = false }
                        ModeOption(stringResource(R.string.onb2_rules), stringResource(R.string.onb2_rules_sub), rules) { rules = true }
                    }
                    else -> {
                        SwitchRow(stringResource(R.string.onb3_ads), stringResource(R.string.onb3_ads_sub), ads) { ads = it }
                        SwitchRow(stringResource(R.string.onb3_auto), stringResource(R.string.onb3_auto_sub), auto) { auto = it }
                        SwitchRow(stringResource(R.string.onb3_tg), stringResource(R.string.onb3_tg_sub), tg) { tg = it }
                        SwitchRow(stringResource(R.string.onb3_notify), stringResource(R.string.onb3_notify_sub), notify) { notify = it }
                        Text(
                            stringResource(R.string.onb3_vpn_note),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)
            ) {
                Text(
                    stringResource(if (step == 0) R.string.onb_skip else R.string.onb_back),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { role = Role.Button }
                        .clickable { if (step == 0) onFinish() else step-- }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                )
                Spacer(Modifier.weight(1f))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .heightIn(min = 52.dp)
                        .widthIn(min = 140.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(homeAccent())
                        .semantics { role = Role.Button }
                        .clickable { if (step < 2) step++ else finish() }
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        stringResource(if (step < 2) R.string.onb_next else R.string.onb_done),
                        color = mainOnAccentColor(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val accent = homeAccent()
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .background(homeSurface(selected))
            .border(1.5.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), shape)
            .semantics { role = Role.RadioButton }
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                .padding(5.dp)
                .clip(CircleShape)
                .background(if (selected) accent else androidx.compose.ui.graphics.Color.Transparent)
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(homeSurface())
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

