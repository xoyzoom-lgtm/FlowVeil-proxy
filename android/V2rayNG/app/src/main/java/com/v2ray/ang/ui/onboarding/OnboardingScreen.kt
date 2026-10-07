package com.v2ray.ang.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import com.v2ray.ang.handler.ExtraRules
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.RuleProfiles
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.ui.main.MainAction
import com.v2ray.ang.ui.main.MainBackground
import com.v2ray.ang.ui.main.home.SheetRow
import com.v2ray.ang.ui.main.home.glassEdge
import com.v2ray.ang.ui.main.home.homeAccent
import com.v2ray.ang.ui.main.home.homeSurface
import com.v2ray.ang.ui.main.mainOnAccentColor

private const val LAST_STEP = 3

/**
 * First run: a welcome screen, then three steps (add a key, how to send traffic, the main switches), all skippable.
 * Nothing is turned on silently: every switch starts in the state the user already has (ads and Telegram off).
 * The notification permission is asked in a popup at the end, not by the system before the user has seen anything.
 */
@Composable
fun OnboardingScreen(onAction: (MainAction) -> Unit, onRequestNotifications: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var askNotify by rememberSaveable { mutableStateOf(false) }
    var rules by rememberSaveable { mutableStateOf(RuleProfiles.current() == RuleProfiles.Profile.RU_DIRECT) }
    var ads by rememberSaveable { mutableStateOf(ExtraRules.adBlock()) }
    var auto by rememberSaveable { mutableStateOf(MmkvManager.decodeSettingsBool(WhitelistBypass.PREF_ENABLED, false)) }
    var tg by rememberSaveable { mutableStateOf(ExtraRules.telegramProxy()) }
    var notify by rememberSaveable { mutableStateOf(UpdateNotifier.isEnabled()) }

    val needsNotifyAsk = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val leave: () -> Unit = {
        if (needsNotifyAsk) {
            askNotify = true
        } else {
            onFinish()
        }
    }

    val finish = {
        val profile = if (rules) RuleProfiles.Profile.RU_DIRECT else RuleProfiles.Profile.ALL_PROXY
        if (RuleProfiles.current() != profile) RuleProfiles.apply(context, profile)
        MmkvManager.encodeSettings(ExtraRules.PREF_ADBLOCK, ads)
        MmkvManager.encodeSettings(ExtraRules.PREF_TELEGRAM_PROXY, tg)
        ExtraRules.apply()
        MmkvManager.encodeSettings(WhitelistBypass.PREF_ENABLED, auto)
        UpdateNotifier.setEnabled(context, notify)
        leave()
    }

    Box(Modifier.fillMaxSize()) {
        MainBackground()
        if (step == 0) {
            WelcomeHero(onStart = { step = 1 }, onSkip = { leave() })
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(vertical = 12.dp)
                ) {
                    repeat(LAST_STEP) { i ->
                        val on = i + 1 <= step
                        val w by animateFloatAsState(if (i + 1 == step) 28f else 14f, tween(300), label = "dot")
                        Box(
                            Modifier
                                .height(6.dp)
                                .width(w.dp)
                                .clip(CircleShape)
                                .background(if (on) homeAccent() else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        )
                    }
                }
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(280)) + slideInHorizontally(tween(320)) { dir * it / 5 }) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(tween(320)) { -dir * it / 5 })
                    },
                    label = "step",
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f)
                ) { s ->
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        val (title, text) = when (s) {
                            1 -> R.string.onb1_title to R.string.onb1_text
                            2 -> R.string.onb2_title to R.string.onb2_text
                            else -> R.string.onb3_title to R.string.onb3_text
                        }
                        Text(stringResource(title), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(text), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        when (s) {
                            1 -> {
                                // Each way of adding moves on: the import itself still asks for confirmation.
                                val go: (MainAction) -> Unit = { onAction(it); step = 2 }
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
                            2 -> {
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
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)
                ) {
                    Text(
                        stringResource(if (step == 1) R.string.onb_skip else R.string.onb_back),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .semantics { role = Role.Button }
                            .clickable { if (step == 1) leave() else step-- }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(stringResource(if (step < LAST_STEP) R.string.onb_next else R.string.onb_done)) {
                        if (step < LAST_STEP) step++ else finish()
                    }
                }
            }
        }
        if (askNotify) {
            NotifyPopup(
                onAllow = { askNotify = false; onFinish(); onRequestNotifications() },
                onLater = { askNotify = false; onFinish() },
            )
        }
    }
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 52.dp)
            .widthIn(min = 140.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(homeAccent())
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp)
    ) {
        Text(label, color = mainOnAccentColor(), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Each block rises and fades in a little after the previous one. */
@Composable
private fun Reveal(shown: Boolean, delayMs: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(520, delayMs)) + slideInVertically(tween(520, delayMs)) { it / 2 },
    ) { content() }
}

@Composable
private fun WelcomeHero(onStart: () -> Unit, onSkip: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val accent = homeAccent()
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    val logoScale by animateFloatAsState(if (shown) 1f else 0.55f, spring(dampingRatio = 0.5f, stiffness = 110f), label = "logoScale")
    val logoAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(700), label = "logoAlpha")

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(220.dp)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glow * logoAlpha }
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)))
            )
            Image(
                painter = painterResource(R.drawable.ic_app_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(112.dp)
                    .graphicsLayer { scaleX = logoScale; scaleY = logoScale; alpha = logoAlpha }
            )
        }
        Reveal(shown, 350) {
            Text(
                stringResource(R.string.app_name),
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(6.dp))
        Reveal(shown, 480) {
            Text(
                stringResource(R.string.onb0_tagline),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
            Reveal(shown, 650) { FeatureCard(R.drawable.ic_cloud_download_24dp, R.string.onb0_f1, R.string.onb0_f1_sub) }
            Spacer(Modifier.height(8.dp))
            Reveal(shown, 800) { FeatureCard(R.drawable.ic_flash_on_24dp, R.string.onb0_f2, R.string.onb0_f2_sub) }
            Spacer(Modifier.height(8.dp))
            Reveal(shown, 950) { FeatureCard(R.drawable.ic_lock_24dp, R.string.onb0_f3, R.string.onb0_f3_sub) }
        }
        Spacer(Modifier.weight(1f))
        Reveal(shown, 1150) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton(stringResource(R.string.onb_start), Modifier.widthIn(min = 240.dp), onStart)
                Text(
                    stringResource(R.string.onb_skip),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { role = Role.Button }
                        .clickable(onClick = onSkip)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(icon: Int, title: Int, sub: Int) {
    val accent = homeAccent()
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(homeSurface())
            .glassEdge(shape)
            .padding(14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.16f))
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(title), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(sub), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Our own explanation before the system permission prompt, so it is clear what the notifications are for. */
@Composable
private fun NotifyPopup(onAllow: () -> Unit, onLater: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val accent = homeAccent()
    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(220)) + scaleIn(spring(dampingRatio = 0.65f, stiffness = 300f), initialScale = 0.85f),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(24.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f))
                ) {
                    Icon(painterResource(R.drawable.ic_bell_24dp), contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.onb_notify_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.onb_notify_text),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                PrimaryButton(stringResource(R.string.onb_notify_allow), Modifier.fillMaxWidth(), onAllow)
                Text(
                    stringResource(R.string.onb_notify_later),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { role = Role.Button }
                        .clickable(onClick = onLater)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
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

