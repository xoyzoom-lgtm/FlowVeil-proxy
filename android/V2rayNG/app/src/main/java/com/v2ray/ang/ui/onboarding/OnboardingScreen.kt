package com.v2ray.ang.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import com.v2ray.ang.R
import com.v2ray.ang.handler.RuleProfiles
import com.v2ray.ang.ui.main.MainAction
import com.v2ray.ang.ui.main.MainBackground
import com.v2ray.ang.ui.main.home.SheetRow
import com.v2ray.ang.ui.main.home.homeAccent
import com.v2ray.ang.ui.main.home.homeSurface
import com.v2ray.ang.ui.main.mainOnAccentColor

private const val PAGES = 4

/**
 * First run: four swipeable slides (what this is, where servers come from, how to send traffic, add a subscription),
 * all skippable. Nothing is turned on silently: the traffic choice starts in the state the user already has.
 * The notification permission is asked in a popup at the end, not by the system before the user has seen anything.
 */
@Composable
fun OnboardingScreen(onAction: (MainAction) -> Unit, onRequestNotifications: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    var askNotify by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var rules by rememberSaveable { mutableStateOf(RuleProfiles.current() == RuleProfiles.Profile.RU_DIRECT) }

    val needsNotifyAsk = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val applyChoice: () -> Unit = {
        val profile = if (rules) RuleProfiles.Profile.RU_DIRECT else RuleProfiles.Profile.ALL_PROXY
        if (RuleProfiles.current() != profile) {
            RuleProfiles.apply(context, profile)
        }
    }
    // "Later": keep the traffic choice, then offer notifications before leaving.
    val later: () -> Unit = {
        applyChoice()
        if (needsNotifyAsk) {
            askNotify = true
        } else {
            onFinish()
        }
    }
    val skip: () -> Unit = {
        if (needsNotifyAsk) {
            askNotify = true
        } else {
            onFinish()
        }
    }
    val last = pager.currentPage == PAGES - 1

    Box(Modifier.fillMaxSize()) {
        MainBackground()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.widthIn(max = 560.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    stringResource(R.string.onb_skip),
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { role = Role.Button }
                        .clickable(onClick = skip)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth()) { page ->
                Slide(page, active = pager.currentPage == page, rules = rules, onRules = { rules = it })
            }
            Dots(pager.currentPage)
            Spacer(Modifier.height(24.dp))
            if (!last) {
                PrimaryButton(stringResource(R.string.onb_next), Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
                Spacer(Modifier.height(8.dp))
                // Keeps the layout from jumping between the first slides and the last one.
                Spacer(Modifier.height(48.dp))
            } else {
                PrimaryButton(stringResource(R.string.onb_add_sub), Modifier.widthIn(max = 560.dp).fillMaxWidth()) { showAdd = true }
                Text(
                    stringResource(R.string.onb_later),
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .semantics { role = Role.Button }
                        .clickable(onClick = later)
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
        if (showAdd) {
            AddDialog(
                onPick = { action ->
                    showAdd = false
                    applyChoice()
                    onAction(action)
                    onFinish()
                },
                onDismiss = { showAdd = false },
            )
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
private fun Dots(current: Int) {
    val accent = homeAccent()
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGES) { i ->
            val w by animateDpAsState(if (i == current) 28.dp else 8.dp, tween(300), label = "dot")
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(if (i == current) accent else idle))
        }
    }
}

@Composable
private fun Slide(page: Int, active: Boolean, rules: Boolean, onRules: (Boolean) -> Unit) {
    val accent = homeAccent()
    // The picture pops in when its slide comes into view.
    val pop by animateFloatAsState(if (active) 1f else 0.72f, spring(dampingRatio = 0.55f, stiffness = 180f), label = "pop")
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    val (title, text) = when (page) {
        0 -> R.string.onb_p1_title to R.string.onb_p1_text
        1 -> R.string.onb_p2_title to R.string.onb_p2_text
        2 -> R.string.onb2_title to R.string.onb2_text
        else -> R.string.onb_p4_title to R.string.onb_p4_text
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(188.dp)
                .graphicsLayer { scaleX = pop; scaleY = pop; alpha = 0.4f + 0.6f * ((pop - 0.72f) / 0.28f).coerceIn(0f, 1f) }
        ) {
            if (page == 0) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = glow }
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.6f), Color.Transparent)))
                )
                Image(painterResource(R.drawable.ic_app_logo), contentDescription = null, modifier = Modifier.size(104.dp))
            } else {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(accent.copy(alpha = 0.14f)))
                val icon = when (page) {
                    1 -> R.drawable.ic_server_24dp
                    2 -> R.drawable.ic_routing_24dp
                    else -> R.drawable.ic_link_24dp
                }
                Icon(painterResource(icon), contentDescription = null, tint = accent, modifier = Modifier.size(84.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(title),
            fontSize = 30.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(text),
            fontSize = 17.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (page == 2) {
            Spacer(Modifier.height(20.dp))
            ModeOption(stringResource(R.string.onb2_all), stringResource(R.string.onb2_all_sub), !rules) { onRules(false) }
            ModeOption(stringResource(R.string.onb2_rules), stringResource(R.string.onb2_rules_sub), rules) { onRules(true) }
        }
    }
}

/** The ways to add a subscription, in a card over the last slide. */
@Composable
private fun AddDialog(onPick: (MainAction) -> Unit, onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(200)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 300f), initialScale = 0.9f),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 16.dp)
            ) {
                Text(
                    stringResource(R.string.onb_add_sub),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                )
                // Each way of adding still asks for confirmation before anything is imported.
                SheetRow(R.drawable.ic_copy, stringResource(R.string.home_add_paste)) { onPick(MainAction.ImportClipboard) }
                SheetRow(R.drawable.ic_scan_24dp, stringResource(R.string.home_add_scan)) { onPick(MainAction.ImportQRcode) }
                SheetRow(R.drawable.ic_cloud_download_24dp, stringResource(R.string.home_add_transfer)) { onPick(MainAction.OpenMigration) }
                SheetRow(R.drawable.ic_edit_24dp, stringResource(R.string.home_add_manual)) {
                    onPick(MainAction.ImportManually(com.v2ray.ang.enums.EConfigType.VLESS.value))
                }
                Text(
                    stringResource(R.string.home_add_caption),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, end = 8.dp)
                )
            }
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

