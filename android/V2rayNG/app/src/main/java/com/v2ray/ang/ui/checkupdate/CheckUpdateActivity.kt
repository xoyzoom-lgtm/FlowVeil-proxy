package com.v2ray.ang.ui.checkupdate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.v2ray.ang.handler.UpdateNotifier
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.SettingsMenuItem
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.ui.compose.VersionInfoBlock
import com.v2ray.ang.ui.compose.verticalScrollbar
import com.v2ray.ang.util.Utils

class CheckUpdateActivity : BaseComponentActivity() {

    private val viewModel: CheckUpdateViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Asked when the switch is turned on (a clear moment), never at the first start. A refusal only removes the system notification; the banner in the app stays. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            viewModel.checkForUpdates(autoInstall = intent.getBooleanExtra(EXTRA_AUTO_UPDATE, false))
        }
    }

    companion object {
        const val EXTRA_AUTO_UPDATE = "auto_update"
    }

    override fun onResume() {
        super.onResume()
        // Back from "allow installs from this app": continue with the downloaded update.
        viewModel.resumeInstallIfPending()
    }

    @Composable
    override fun ScreenContent() {
        CheckUpdateScreen(viewModel = viewModel, onBackClick = { finish() }, onNotifyEnabled = { askNotificationPermission() })
    }
}

@Composable
fun CheckUpdateScreen(
    viewModel: CheckUpdateViewModel,
    onBackClick: () -> Unit,
    onNotifyEnabled: () -> Unit = {},
) {
    val context = LocalContext.current
    var notifyOn by remember { mutableStateOf(UpdateNotifier.isEnabled()) }

    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val updateResult by viewModel.updateResult.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadFailed by viewModel.downloadFailed.collectAsStateWithLifecycle()

    val libVersion = CoreNativeManager.getLibVersion()
    val versionText = "FlowVeil build ${BuildConfig.HUPP_BUILD} ($libVersion)"

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.update_check_for_update),
                onBackClick = onBackClick,
                isLoading = isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsMenuItem(
                icon = painterResource(R.drawable.ic_check_update_24dp),
                title = stringResource(R.string.update_check_for_update),
                onClick = { viewModel.checkForUpdates() }
            )
            SettingsSwitchItem(
                title = stringResource(R.string.update_notify_switch),
                summary = stringResource(R.string.update_notify_summary),
                checked = notifyOn,
                onCheckedChange = { on ->
                    notifyOn = on
                    UpdateNotifier.setEnabled(context, on)
                    if (on) onNotifyEnabled()
                }
            )
            VersionInfoBlock(versionText = versionText)
            NavigationBarsSpacer()
        }
    }

    val askUnsigned by viewModel.askUnsigned.collectAsStateWithLifecycle()
    if (askUnsigned) {
        AlertDialog(
            onDismissRequest = { viewModel.answerUnsigned(false) },
            title = { Text(stringResource(R.string.update_unsigned_title)) },
            text = { Text(stringResource(R.string.update_unsigned_text)) },
            confirmButton = { TextButton(onClick = { viewModel.answerUnsigned(false) }) { Text(stringResource(R.string.update_unsigned_no)) } },
            dismissButton = { TextButton(onClick = { viewModel.answerUnsigned(true) }) { Text(stringResource(R.string.update_unsigned_yes)) } },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showUpdateDialog && updateResult != null) {
        val result = updateResult!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdateDialog() },
            title = { Text(stringResource(R.string.update_new_version_found, result.latestVersion ?: "")) },
            text = {
                val progress = downloadProgress
                if (progress != null) {
                    Column {
                        Text(stringResource(R.string.update_downloading, if (progress >= 0) "$progress%" else ""))
                        androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                        if (progress >= 0) {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    val scrollState = rememberScrollState()
                    Column {
                        Text(
                            text = result.releaseNotes.orEmpty(),
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                                .verticalScrollbar(scrollState)
                        )
                        if (downloadFailed && !result.downloadUrl.isNullOrBlank()) {
                            TextButton(onClick = { result.downloadUrl?.let { Utils.openUri(context, it) } }) {
                                Text(stringResource(R.string.update_open_browser))
                            }
                        }
                        TextButton(onClick = { viewModel.skipVersion() }) {
                            Text(stringResource(R.string.update_skip))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = downloadProgress == null,
                    onClick = { viewModel.downloadAndInstall() }
                ) {
                    Text(stringResource(R.string.update_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.later() }) {
                    Text(stringResource(R.string.update_later))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
