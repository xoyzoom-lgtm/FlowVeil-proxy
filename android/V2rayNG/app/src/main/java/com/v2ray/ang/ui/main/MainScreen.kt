package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.util.Utils

private const val KEY_HERO = "hero"
private const val SERVER_KEY_PREFIX = "server-"

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val displayText = mainViewModel.formatStatus(uiState.status)
    val selectedGuid = uiState.selectedGuid
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showDelSubConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (showDelSubConfirm) {
        val subId = uiState.selectedGroupId
        DeleteConfirmDialog(
            message = stringResource(R.string.confirm_delete_subscription),
            onConfirm = {
                showDelSubConfirm = false
                onAction(MainAction.RemoveSubscription(subId))
            },
            onDismiss = { showDelSubConfirm = false }
        )
    }

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    // Hide the built-in "Default" group while it has no servers.
    val defaultServersFlow = remember(mainViewModel) {
        mainViewModel.serversForGroup(AppConfig.DEFAULT_SUBSCRIPTION_ID)
    }
    val defaultServers by defaultServersFlow.collectAsStateWithLifecycle()
    val visibleGroups = if (defaultServers.isEmpty()) {
        groups.filterNot { it.id == AppConfig.DEFAULT_SUBSCRIPTION_ID }
    } else {
        groups
    }
    LaunchedEffect(visibleGroups, uiState.selectedGroupId) {
        if (visibleGroups.isNotEmpty() && visibleGroups.none { it.id == uiState.selectedGroupId }) {
            onAction(MainAction.SelectGroup(visibleGroups.first().id))
        }
    }

    val selectedGroupStateFlow = remember(uiState.selectedGroupId) {
        mainViewModel.serverGroupState(uiState.selectedGroupId)
    }
    val selectedGroupState by selectedGroupStateFlow.collectAsStateWithLifecycle()
    val rows = selectedGroupState.rows
    val selectedRow = rows.firstOrNull { it.guid == selectedGuid }
    val selectedSubscription = visibleGroups
        .firstOrNull { it.id == uiState.selectedGroupId && it.id != AppConfig.DEFAULT_SUBSCRIPTION_ID }
        ?.subscription
    val context = LocalContext.current
    val rowActions = remember(onAction) {
        ServerRowActions(
            select = { guid -> onAction(MainAction.SelectServer(guid)) },
            more = { guid, profile -> shareTarget = Triple(guid, profile, true) },
        )
    }

    val showHeroInList = LocalConfiguration.current.screenWidthDp < 700
    val listState = rememberLazyListState()
    val locateTarget = uiState.locateTarget
    LaunchedEffect(locateTarget, rows) {
        if (locateTarget == null) return@LaunchedEffect
        val rowIndex = rows.indexOfFirst { it.guid == locateTarget.serverGuid }
        if (rowIndex >= 0) {
            // Items before the server rows, in the same order listContent emits them.
            val headerCount = listOf(
                showHeroInList && !showSearch,
                selectedSubscription != null && !showSearch,
                true,
                visibleGroups.size > 1,
            ).count { it }
            listState.animateScrollToItem(headerCount + rowIndex)
        }
        onAction(MainAction.LocateHandled)
    }
    val hero: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { buttonSize ->
        ConnectionHero(
            isRunning = isRunning,
            connectedSince = uiState.connectedSince,
            statusText = displayText.takeIf { isRunning || uiState.status !is MainStatus.Disconnected },
            selectedRow = selectedRow,
            onToggle = { onAction(MainAction.ToggleService) },
            onTest = { onAction(MainAction.TestCurrentServer) },
            buttonSize = buttonSize
        )
    }
    val emptyContent: @Composable () -> Unit = {
        EmptyServersState(
            onPaste = { onAction(MainAction.ImportClipboard) },
            onScan = { onAction(MainAction.ImportQRcode) }
        )
    }

    val listContent: LazyListScope.(includeHero: Boolean) -> Unit = { includeHero ->
        if (includeHero && !showSearch) {
            item(key = KEY_HERO) {
                Column {
                    hero(172.dp)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (visibleGroups.isEmpty()) {
            item(key = "empty") { emptyContent() }
        } else {
            if (selectedSubscription != null && !showSearch) {
                item(key = "subscription") {
                    Column {
                        SubscriptionCard(
                            subscription = selectedSubscription,
                            isTesting = uiState.isTesting,
                            onRefresh = { onAction(MainAction.UpdateSubscriptions) },
                            onTestAll = { onAction(MainAction.CheckServers) },
                            onOpenSupport = { url -> Utils.openUri(context, url) },
                            onMenuAction = { action ->
                                when (action) {
                                    SubscriptionMenuAction.Update -> onAction(MainAction.UpdateSubscriptions)
                                    SubscriptionMenuAction.TestRealPing -> onAction(MainAction.CheckServers)
                                    SubscriptionMenuAction.TestTcping -> onAction(MainAction.TestAllServers)
                                    SubscriptionMenuAction.SortByPing -> onAction(MainAction.SortByTestResults)
                                    SubscriptionMenuAction.Edit -> onAction(MainAction.EditSubscription(uiState.selectedGroupId))
                                    SubscriptionMenuAction.CopyLink -> {
                                        Utils.setClipboard(context, selectedSubscription.url)
                                        context.toastSuccess(R.string.toast_success)
                                    }
                                    SubscriptionMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                                    SubscriptionMenuAction.RemoveDuplicate -> showDelDuplicateConfirm = true
                                    SubscriptionMenuAction.RemoveInvalid -> showDelInvalidConfirm = true
                                    SubscriptionMenuAction.Delete -> showDelSubConfirm = true
                                    SubscriptionMenuAction.AllSubscriptions -> onNavigate(MainDestination.Subscriptions)
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            item(key = "title") {
                Text(
                    text = stringResource(R.string.main_servers_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp)
                )
            }
            if (visibleGroups.size > 1) {
                item(key = "groups") {
                    Column {
                        GroupChips(
                            groups = visibleGroups,
                            selectedIndex = visibleGroups.indexOfFirst { it.id == uiState.selectedGroupId }.coerceAtLeast(0),
                            mainViewModel = mainViewModel,
                            onClick = { index -> onAction(MainAction.SelectGroup(visibleGroups[index].id)) }
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            if (rows.isEmpty() && searchQuery.isEmpty()) {
                item(key = "empty-group") { emptyContent() }
            }
            items(rows, key = { SERVER_KEY_PREFIX + it.guid }) { row ->
                ServerListItem(
                    row = row,
                    isSelected = row.guid == selectedGuid,
                    actions = rowActions,
                    alive = uiState.availability[row.guid],
                    availabilityOnly = uiState.availabilityOnly
                )
            }
        }
    }

    run {
        Box(Modifier.fillMaxSize()) {
            MainBackground()
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    MainTopBar(
                        isLoading = isLoading,
                        showSearch = showSearch,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { query: String ->
                            searchQuery = query
                            onAction(MainAction.Search(query))
                        },
                        onSearchClose = {
                            searchQuery = ""
                            onAction(MainAction.Search(""))
                            showSearch = false
                        },
                        onSearchToggle = { show: Boolean -> showSearch = show },
                        onMenuClick = { onNavigate(MainDestination.Settings) },
                        onAction = onAction,
                        onMoreMenuAction = { action ->
                            when (action) {
                                MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                                MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                                MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                                MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                                MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                                MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                                MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                                MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                                MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                                MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                            }
                        }
                    )
                },
            ) { innerPadding ->
                if (showHeroInList) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScrollbar(listState),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        listContent(true)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.Center
                        ) {
                            hero(190.dp)
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .verticalScrollbar(listState),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                        ) {
                            listContent(false)
                        }
                    }
                }
            }
        }
    }
}
