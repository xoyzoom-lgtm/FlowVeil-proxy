package com.v2ray.ang.ui.main.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.UpdateNotifier
import com.v2ray.ang.net.HomeCard
import com.v2ray.ang.net.ShortName
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.main.EmptyServersState
import com.v2ray.ang.ui.main.MainAction
import com.v2ray.ang.ui.main.MainBackground
import com.v2ray.ang.ui.main.MainDestination
import com.v2ray.ang.ui.main.MainDialogs
import com.v2ray.ang.ui.main.MainStatus
import com.v2ray.ang.ui.main.MainViewModel
import com.v2ray.ang.ui.main.ServerDeleteTarget
import com.v2ray.ang.ui.main.ShareMethodDialog
import com.v2ray.ang.ui.main.splitFlag
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** How long "connecting" may last before the panel says it failed (counted only while the app is on screen). */
private const val CONNECT_TIMEOUT_MS = 20_000L

private enum class Sheet { NONE, SUB, ADD, MANUAL, NOTE, SHARE, LOOK }

/**
 * New home screen (mockup v4): subscription cards on top, servers below, one fixed bottom panel with the main
 * button. Uses only the existing [MainViewModel] state and [MainAction]s.
 * Phones: one column (centred, at most 720dp wide). Tablets, foldables and landscape (700dp+): cards on the left, servers on the right.
 */
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    onWizard: () -> Unit = {},
) {
    val context = LocalContext.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSubs by rememberSaveable { mutableStateOf(false) }
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning

    // Clock for "days left", the connected timer and "updated just now".
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isRunning) {
        while (true) {
            now = System.currentTimeMillis()
            delay(if (isRunning) 1_000L else 30_000L)
        }
    }

    // Hide the built-in group while it has no servers.
    val defaultServers by remember(mainViewModel) { mainViewModel.serversForGroup(AppConfig.DEFAULT_SUBSCRIPTION_ID) }
        .collectAsStateWithLifecycle()
    val groups = if (defaultServers.isEmpty()) uiState.groups.filterNot { it.id == AppConfig.DEFAULT_SUBSCRIPTION_ID } else uiState.groups
    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isNotEmpty() && groups.none { it.id == uiState.selectedGroupId }) {
            onAction(MainAction.SelectGroup(groups.first().id))
        }
    }
    val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }.coerceAtLeast(0)
    val selectedGroup = groups.getOrNull(selectedIndex)

    val groupState by remember(uiState.selectedGroupId) { mainViewModel.serverGroupState(uiState.selectedGroupId) }
        .collectAsStateWithLifecycle()
    val rows = groupState.rows
    val selectedRow = rows.firstOrNull { it.guid == uiState.selectedGuid }

    // The group whose server is running, so its card says "connected".
    val activeGroupId = if (isRunning && selectedRow != null) uiState.selectedGroupId else null

    // Cards <-> selected group, both ways.
    val pagerState = rememberPagerState(initialPage = selectedIndex) { groups.size }
    LaunchedEffect(pagerState, groups) {
        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { page ->
            groups.getOrNull(page)?.let { if (it.id != mainViewModel.uiState.value.selectedGroupId) onAction(MainAction.SelectGroup(it.id)) }
        }
    }
    LaunchedEffect(selectedIndex, groups.size) {
        if (groups.isNotEmpty() && pagerState.currentPage != selectedIndex && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var sheetGroup by remember { mutableStateOf<GroupMapItem?>(null) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var shareTarget by remember { mutableStateOf<Pair<String, com.v2ray.ang.dto.entities.ProfileItem>?>(null) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) { mutableStateOf<ServerDeleteTarget?>(null) }
    var showDelDuplicate by remember { mutableStateOf(false) }
    var showDelInvalid by remember { mutableStateOf(false) }
    var deleteSubId by remember { mutableStateOf<String?>(null) }
    var updateBanner by remember { mutableStateOf(UpdateNotifier.bannerCandidate()) }
    LaunchedEffect(Unit) {
        UpdateNotifier.schedule(context)
        UpdateNotifier.checkIfDue(context)
        updateBanner = UpdateNotifier.bannerCandidate()
    }

    // Connecting / failed, kept here because the service only reports "running" or not.
    var connecting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(isRunning) {
        if (isRunning) {
            connecting = false
            failed = false
        }
    }
    LaunchedEffect(connecting, resumed) {
        if (connecting && resumed) {
            delay(CONNECT_TIMEOUT_MS)
            if (connecting && !mainViewModel.uiState.value.isRunning) {
                connecting = false
                failed = true
            }
        }
    }
    val testFailed = isRunning && (uiState.status as? MainStatus.ConnectionTest)?.result?.delayMillis?.let { it < 0 } == true

    MainDialogs(
        showDelAllConfirm = false,
        onDismissDelAll = {},
        onConfirmDelAll = {},
        showDelDuplicateConfirm = showDelDuplicate,
        onDismissDelDuplicate = { showDelDuplicate = false },
        onConfirmDelDuplicate = { showDelDuplicate = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalid,
        onDismissDelInvalid = { showDelInvalid = false },
        onConfirmDelInvalid = { showDelInvalid = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) },
    )
    deleteSubId?.let { subId ->
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_subscription),
            onConfirm = { deleteSubId = null; onAction(MainAction.RemoveSubscription(subId)) },
            onDismiss = { deleteSubId = null },
        )
    }
    shareTarget?.let { (guid, profile) ->
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = true,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = { g, name ->
                if (uiState.confirmRemove) showRemoveConfirm = ServerDeleteTarget(g, name) else onAction(MainAction.RemoveServer(g))
            },
        )
    }
    uiState.shareQRCodeBitmap?.let { QRCodeDialog(bitmap = it, onDismiss = { onAction(MainAction.DismissQRCodeDialog) }) }

    val closeSheet = { sheet = Sheet.NONE }
    when (sheet) {
        Sheet.SUB -> sheetGroup?.let { g ->
            val count by remember(g.id) { mainViewModel.serversForGroup(g.id) }.collectAsStateWithLifecycle()
            SubscriptionSheet(
                group = g,
                serverCount = count.size,
                now = now,
                onDismiss = closeSheet,
                onEnabled = { on ->
                    com.v2ray.ang.handler.MmkvManager.decodeSubscription(g.id)?.let { item ->
                        item.enabled = on
                        com.v2ray.ang.handler.MmkvManager.encodeSubscription(g.id, item)
                    }
                    onAction(MainAction.RefreshGroups)
                },
                onAction = { a ->
                    if (g.id != uiState.selectedGroupId) onAction(MainAction.SelectGroup(g.id))
                    val url = g.subscription?.url.orEmpty()
                    when (a) {
                        SubSheetAction.Update -> { closeSheet(); context.toastSuccess(R.string.home_toast_updating); onAction(MainAction.UpdateSubscriptions) }
                        SubSheetAction.Check -> { closeSheet(); onAction(MainAction.CheckServers) }
                        SubSheetAction.Share -> sheet = Sheet.SHARE
                        SubSheetAction.Edit -> { closeSheet(); onAction(MainAction.EditSubscription(g.id)) }
                        SubSheetAction.Look -> sheet = Sheet.LOOK
                        SubSheetAction.Message -> sheet = Sheet.NOTE
                        SubSheetAction.Support -> g.subscription?.supportUrl?.let { Utils.openUri(context, it) }
                        SubSheetAction.CopyLink -> { Utils.setClipboard(context, url); context.toastSuccess(R.string.toast_success) }
                        SubSheetAction.AllSubscriptions -> { closeSheet(); showSubs = true }
                        SubSheetAction.SortByPing -> { closeSheet(); onAction(MainAction.SortByTestResults) }
                        SubSheetAction.TestTcping -> { closeSheet(); onAction(MainAction.TestAllServers) }
                        SubSheetAction.ExportAll -> { closeSheet(); onAction(MainAction.ExportAll) }
                        SubSheetAction.RemoveDuplicate -> { closeSheet(); showDelDuplicate = true }
                        SubSheetAction.RemoveInvalid -> { closeSheet(); showDelInvalid = true }
                        // The sheet already asked twice ("tap again"), so delete straight away.
                        SubSheetAction.Delete -> { closeSheet(); com.v2ray.ang.handler.SubLookStore.reset(context, g.id); onAction(MainAction.RemoveSubscription(g.id)) }
                    }
                },
            )
        }
        Sheet.ADD -> AddSheet(
            onPaste = { closeSheet(); onAction(MainAction.ImportClipboard) },
            onScan = { closeSheet(); onAction(MainAction.ImportQRcode) },
            onTransfer = { closeSheet(); onAction(MainAction.OpenMigration) },
            onManual = { sheet = Sheet.MANUAL },
            onFile = { closeSheet(); onAction(MainAction.ImportConfigLocal) },
            onDismiss = closeSheet,
        )
        Sheet.MANUAL -> ManualSheet(onPick = { closeSheet(); onAction(MainAction.ImportManually(it)) }, onDismiss = closeSheet)
        Sheet.NOTE -> sheetGroup?.subscription?.let { s ->
            NoteSheet(s.announce.orEmpty(), s.supportUrl, onSupport = { Utils.openUri(context, it) }, onDismiss = closeSheet)
        }
        Sheet.SHARE -> sheetGroup?.subscription?.let { s ->
            ShareSheet(s.url, onCopy = { Utils.setClipboard(context, s.url); context.toastSuccess(R.string.toast_success) }, onDismiss = closeSheet)
        }
        Sheet.LOOK -> sheetGroup?.let { g -> AppearanceSheet(g, now, onDismiss = { sheet = Sheet.SUB }) }
        Sheet.NONE -> Unit
    }

    // ----- pieces -----
    val bestRow = if (!uiState.isTesting) rows.filter { it.testDelayMillis > 0L }.minByOrNull { it.testDelayMillis } else null
    val testedCount = rows.count { it.testDelayMillis != 0L }
    val headerTitle = when (val st = uiState.status) {
        is MainStatus.TestProgress -> stringResource(R.string.home_checking, st.progress)
        MainStatus.Testing -> stringResource(R.string.home_checking, "$testedCount/${rows.size}")
        else -> stringResource(R.string.home_servers_title, rows.size)
    }
    val openSub: (GroupMapItem) -> Unit = { sheetGroup = it; sheet = Sheet.SUB }

    val cardsBlock: @Composable () -> Unit = {
        Column {
            if (groups.isEmpty()) {
                EmptyServersState(
                    onPaste = { onAction(MainAction.ImportClipboard) },
                    onScan = { onAction(MainAction.ImportQRcode) },
                )
            } else {
                SubscriptionCarousel(groups, pagerState, mainViewModel, activeGroupId, now, onMore = openSub)
                selectedGroup?.subscription?.announce?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        note,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { sheetGroup = selectedGroup; sheet = Sheet.NOTE }
                            .background(homeSurface())
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }

    val serverItems: LazyListScope.() -> Unit = {
        if (groups.isNotEmpty()) {
            item(key = "servers-header") {
                ServersHeader(
                    title = headerTitle,
                    searchOn = showSearch,
                    refreshing = isLoading,
                    testing = uiState.isTesting,
                    onSearch = {
                        showSearch = !showSearch
                        if (!showSearch && query.isNotEmpty()) {
                            query = ""
                            onAction(MainAction.Search(""))
                        }
                    },
                    onRefresh = { context.toastSuccess(R.string.home_toast_updating); onAction(MainAction.UpdateSubscriptions) },
                    onPing = { if (uiState.isTesting) onAction(MainAction.CancelTesting) else onAction(MainAction.CheckServers) },
                )
            }
            if (showSearch) {
                item(key = "search") { SearchField(query) { query = it; onAction(MainAction.Search(it)) } }
            }
            if (rows.size > 1 && uiState.showBestButton && query.isEmpty()) {
                item(key = "auto") {
                    AutoBestRow(
                        bestName = bestRow?.let { splitFlag(it.remarks).let { (f, n) -> if (f != null && f != n) "$f $n" else n } },
                        bestDelay = bestRow?.testDelayMillis ?: 0L,
                        onClick = { onAction(MainAction.ConnectBest) },
                    )
                }
            }
            if (rows.isEmpty()) {
                item(key = "empty-rows") {
                    Text(
                        if (query.isNotEmpty()) stringResource(R.string.home_nothing_found, query) else stringResource(R.string.home_empty_text),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(24.dp)
                    )
                }
            }
            items(rows, key = { "server-" + it.guid }) { row ->
                ServerRowV4(
                    row = row,
                    selected = row.guid == uiState.selectedGuid,
                    best = bestRow?.guid == row.guid && testedCount > 1,
                    alive = uiState.availability[row.guid],
                    availabilityOnly = uiState.availabilityOnly,
                    testing = uiState.isTesting,
                    onClick = { onAction(MainAction.SelectServer(row.guid)) },
                    onLongClick = { shareTarget = row.guid to row.profile },
                )
            }
        }
        item(key = "dock-space") { DockSpacer() }
    }

    var expiryHidden by rememberSaveable { mutableStateOf<String?>(null) }
    val banner: @Composable () -> Unit = {
        // One notice at a time: the update first, then a subscription that ends within 3 days.
        val g = selectedGroup
        val sub = g?.subscription
        val days = HomeCard.daysLeft(sub?.expireAt, now)
        if (updateBanner == null && g != null && sub != null && days != null && days in 1..3 && expiryHidden != g.id) {
            val renew = sub.webPageUrl?.takeIf { it.isNotBlank() } ?: sub.supportUrl?.takeIf { it.isNotBlank() }
            HomeBanner(
                text = stringResource(R.string.home_banner_expiring, ShortName.of(groupFullName(g)), days.toInt()),
                action = if (renew != null) stringResource(R.string.home_banner_renew) else stringResource(R.string.home_banner_later),
                onAction = { if (renew != null) Utils.openUri(context, renew) else expiryHidden = g.id },
                dismiss = stringResource(R.string.home_banner_later),
                onDismiss = { expiryHidden = g.id },
            )
        }
        updateBanner?.let { update ->
            HomeBanner(
                text = stringResource(R.string.home_banner_update) + " · build-${update.build}",
                action = stringResource(R.string.home_banner_update_btn),
                onAction = { context.startActivity(Intent(context, com.v2ray.ang.ui.checkupdate.CheckUpdateActivity::class.java)) },
                dismiss = stringResource(R.string.home_banner_later),
                onDismiss = { UpdateNotifier.later(); updateBanner = null },
            )
        }
    }

    // ----- dock -----
    val subShort = selectedGroup?.let { ShortName.of(groupFullName(it)) }
    val expiredSelected = HomeCard.expired(selectedGroup?.subscription?.expireAt, now)
    val dockState = when {
        isRunning && testFailed -> DockState.ERROR
        isRunning -> DockState.CONNECTED
        connecting -> DockState.CONNECTING
        failed -> DockState.ERROR
        selectedRow == null && uiState.selectedGuid.isNullOrEmpty() -> DockState.BLOCKED
        expiredSelected -> DockState.BLOCKED
        else -> DockState.IDLE
    }
    // The chosen server may sit in another subscription than the card on screen: take its name from storage then.
    val otherName = remember(uiState.selectedGuid, selectedRow == null) {
        if (selectedRow == null) uiState.selectedGuid?.let { com.v2ray.ang.handler.MmkvManager.decodeServerConfig(it)?.remarks } else null
    }
    val serverName = (selectedRow?.remarks ?: otherName)?.let { splitFlag(it).let { (f, n) -> if (f != null && f != n) "$f  $n" else n } }
    val dockTitle = when (dockState) {
        DockState.ERROR -> stringResource(R.string.home_dock_error_title)
        else -> serverName ?: stringResource(R.string.home_choose_server)
    }
    val pingText = selectedRow?.testDelayMillis?.takeIf { it > 0L }?.let { stringResource(R.string.server_test_delay_value, it) }
    val dockSubtitle = when (dockState) {
        DockState.CONNECTED -> listOfNotNull(
            if (uiState.connectedSince > 0L) HomeCard.clock(now - uiState.connectedSince) else stringResource(R.string.home_status_connected),
            pingText,
            uiState.speed?.let { (up, down) ->
                val mbit = stringResource(R.string.unit_mbit)
                val gbit = stringResource(R.string.unit_gbit)
                "↓ ${com.v2ray.ang.ui.main.formatBitRate(down, mbit, gbit)} ↑ ${com.v2ray.ang.ui.main.formatBitRate(up, mbit, gbit)}"
            },
        ).joinToString(" · ")
        DockState.CONNECTING -> stringResource(R.string.home_dock_connecting)
        DockState.ERROR -> stringResource(R.string.home_dock_error)
        DockState.BLOCKED -> if (expiredSelected) stringResource(R.string.home_status_expired) else stringResource(R.string.home_dock_off_plain)
        DockState.IDLE -> (subShort?.let { stringResource(R.string.home_dock_off, it) } ?: stringResource(R.string.home_dock_off_plain))
            .let { if (com.v2ray.ang.handler.MmkvManager.decodeSettingsBool(AppConfig.PREF_FAST_MODE, false)) stringResource(R.string.home_fast_tag) + " · " + it else it }
    }
    val dockButton = when (dockState) {
        DockState.CONNECTED -> stringResource(R.string.home_btn_disconnect)
        DockState.CONNECTING -> stringResource(R.string.home_btn_cancel)
        DockState.ERROR -> stringResource(R.string.home_btn_fix)
        else -> stringResource(R.string.home_btn_connect)
    }
    val onDockButton: () -> Unit = {
        when (dockState) {
            DockState.CONNECTED -> onAction(MainAction.ToggleService)
            DockState.CONNECTING -> {
                connecting = false
                LauncherManager.stopService(context)
            }
            DockState.ERROR -> {
                failed = false
                onAction(MainAction.OpenDiagnosis)
            }
            DockState.IDLE -> {
                failed = false
                connecting = true
                onAction(MainAction.ToggleService)
            }
            DockState.BLOCKED -> Unit
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val onDockBody: () -> Unit = {
        // Tapping the panel scrolls to the chosen server.
        val index = rows.indexOfFirst { it.guid == uiState.selectedGuid }
        if (index >= 0) scope.launch { runCatching { listState.animateScrollToItem(index + 1) } }
    }

    // Blur style: everything under an open sheet or full-screen layer is blurred (Android 12+; older phones just dim).
    val layerOpen = sheet != Sheet.NONE || showSettings || showSubs
    val blurRadius by androidx.compose.animation.core.animateDpAsState(
        if (HomeStyle.mode == HomeStyle.Mode.BLUR && layerOpen) 22.dp else 0.dp, label = "homeBlur"
    )
    Box(Modifier.fillMaxSize()) {
      Box(Modifier.fillMaxSize().then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)) {
        MainBackground()
        // The list shrinks above the keyboard (the window is edge-to-edge, so this is not automatic).
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            val twoPane = maxWidth >= 700.dp
            // Opening the search brings its field to the top, so the field and the results stay above the keyboard.
            LaunchedEffect(showSearch, twoPane) {
                if (showSearch) {
                    delay(120)
                    runCatching { listState.animateScrollToItem(if (twoPane) 1 else 3) }
                }
            }
            Column(Modifier.fillMaxSize()) {
                HomeTopBar(
                    onAdd = { sheet = Sheet.ADD },
                    onSettings = { showSettings = true },
                    maxWidth = if (twoPane) Dp.Unspecified else 720.dp,
                )
                if (twoPane) {
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            banner()
                            cardsBlock()
                            DockSpacer()
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(0.58f).fillMaxHeight(),
                        ) { serverItems() }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                            contentPadding = PaddingValues(top = 4.dp),
                        ) {
                            item(key = "banner") { banner() }
                            item(key = "cards") { cardsBlock() }
                            serverItems()
                        }
                    }
                }
            }
        }
        // The bottom panel steps aside while the keyboard is open.
        val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        if (!keyboardOpen) ConnectDock(
            state = dockState,
            title = dockTitle,
            subtitle = dockSubtitle,
            buttonText = dockButton,
            onButton = onDockButton,
            onBody = onDockBody,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
        androidx.compose.animation.AnimatedVisibility(
            visible = showSettings,
            enter = androidx.compose.animation.slideInHorizontally { it },
            exit = androidx.compose.animation.slideOutHorizontally { it },
        ) {
            HomeSettings(
                onClose = { showSettings = false },
                // "Subscriptions" opens the new list instead of the old "Groups" screen.
                onNavigate = { dest -> if (dest == MainDestination.Subscriptions) showSubs = true else onNavigate(dest) },
                onWizard = { showSettings = false; onWizard() },
                onRulesChanged = { if (mainViewModel.uiState.value.isRunning) LauncherManager.restartService(context) },
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showSubs,
            enter = androidx.compose.animation.slideInHorizontally { it },
            exit = androidx.compose.animation.slideOutHorizontally { it },
            modifier = Modifier.fillMaxSize(),
        ) {
            HomeSubscriptions(
                groups = groups,
                now = now,
                onClose = { showSubs = false },
                onOpen = { g -> sheetGroup = g; sheet = Sheet.SUB },
                onAdd = { sheet = Sheet.ADD },
                onRefreshAll = { context.toastSuccess(R.string.home_toast_updating); onAction(MainAction.UpdateSubscriptions) },
                onToggle = { g, on ->
                    com.v2ray.ang.handler.MmkvManager.decodeSubscription(g.id)?.let { item ->
                        item.enabled = on
                        com.v2ray.ang.handler.MmkvManager.encodeSubscription(g.id, item)
                    }
                    onAction(MainAction.RefreshGroups)
                },
            )
        }
    }
}

@Composable
private fun HomeTopBar(onAdd: () -> Unit, onSettings: () -> Unit, maxWidth: Dp) {
    val accent = homeAccent()
    val logo = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Flow") }
        withStyle(SpanStyle(brush = Brush.linearGradient(listOf(accent, MaterialTheme.colorScheme.tertiary)))) { append("Veil") }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .then(if (maxWidth != Dp.Unspecified) Modifier.widthIn(max = maxWidth) else Modifier)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
        ) {
            Text(logo, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            RoundAction(R.drawable.ic_add_24dp, stringResource(R.string.home_add_title), onAdd, size = 42.dp)
            RoundAction(R.drawable.ic_settings_24dp, stringResource(R.string.home_settings), onSettings, size = 42.dp)
        }
    }
}
