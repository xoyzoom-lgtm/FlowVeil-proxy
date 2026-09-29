package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.GroupMapItem
import com.v2ray.ang.dto.LocateTarget

/** Locale-neutral state formatted only when it reaches the main UI. */
sealed interface MainStatus {
    data object Disconnected : MainStatus
    data object Connected : MainStatus
    data object Testing : MainStatus
    data class TestProgress(val progress: String) : MainStatus
    data class ConnectionTest(val result: ConnectionTestResult) : MainStatus
    data class Message(val text: String) : MainStatus
}

/**
 * Main UI state
 */
data class MainUiState(
    val groups: List<GroupMapItem> = emptyList(),
    val selectedGroupId: String = "",
    val selectedGuid: String? = null,
    val isRunning: Boolean = false,
    val connectedSince: Long = 0L,
    val isTesting: Boolean = false,
    val status: MainStatus = MainStatus.Disconnected,
    val locateTarget: LocateTarget? = null,
    val confirmRemove: Boolean = false,
    val doubleColumnDisplay: Boolean = false,
    val showBestButton: Boolean = true,
    /** guid -> server answered the availability check (step 1 of a server check). */
    val availability: Map<String, Boolean> = emptyMap(),
    val availabilityOnly: Boolean = false,
    val shareQRCodeBitmap: android.graphics.Bitmap? = null,
    /** Server picked by "connect to the best": the activity selects it and connects, then clears it. */
    val connectBestGuid: String? = null,
    /** Throughput while connected, bytes per second (null until the first sample). */
    val speed: Pair<Long, Long>? = null,
)

/**
 * All possible user interaction intents
 */
sealed interface MainAction {
    data object Initialize : MainAction
    data object RefreshGroups : MainAction
    data object ToggleService : MainAction
    data object TestCurrentServer : MainAction
    data object TestAllServers : MainAction
    data object TestRealAllServers : MainAction
    /** Two steps: first which servers are alive, then their real ping. */
    data object CheckServers : MainAction
    data object ConnectBest : MainAction
    data object ConnectBestHandled : MainAction
    data class ToggleFavorite(val guid: String) : MainAction
    data object SpeedTest : MainAction
    data object CancelTesting : MainAction
    data object RemoveAllServers : MainAction
    data object RemoveDuplicateServers : MainAction
    data object RemoveInvalidServers : MainAction
    data object SortByTestResults : MainAction
    data object UpdateSubscriptions : MainAction
    data object ExportAll : MainAction
    data object SyncNewSubscriptions : MainAction
    data class RemoveSubscription(val subId: String) : MainAction
    data class EditSubscription(val subId: String) : MainAction

    data object ImportQRcode : MainAction
    data object ImportClipboard : MainAction
    data object ImportConfigLocal : MainAction
    data class ImportManually(val type: Int) : MainAction
    data object RestartService : MainAction
    data object LocateSelectedServer : MainAction

    data class SelectGroup(val groupId: String) : MainAction
    data class SelectServer(val guid: String) : MainAction
    data class RemoveServer(val guid: String) : MainAction
    data class EditServer(val guid: String, val profile: com.v2ray.ang.dto.entities.ProfileItem) : MainAction
    data class Search(val query: String) : MainAction
    data class ShareQRCode(val guid: String) : MainAction
    data class ShareClipboard(val guid: String) : MainAction
    data class ShareFullContent(val guid: String) : MainAction
    data object DismissQRCodeDialog : MainAction

    data class ImportBatchConfig(val configText: String) : MainAction

    data object LocateHandled : MainAction
}
