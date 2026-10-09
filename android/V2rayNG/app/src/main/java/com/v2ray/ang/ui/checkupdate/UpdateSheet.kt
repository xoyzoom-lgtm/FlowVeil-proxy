package com.v2ray.ang.ui.checkupdate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.util.Utils

private val Teal = Color(0xFF3BE0B0)
private val Sky = Color(0xFF38BDF8)
private val Warn = Color(0xFFF5B43C)

/**
 * The update as one bottom sheet: what is new, the download with its progress, then "file downloaded" with its SHA-256,
 * what was checked, and the Install button. Used by the Update screen and by the home screen (the "new version" notice).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(viewModel: CheckUpdateViewModel) {
    val show by viewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val result by viewModel.updateResult.collectAsStateWithLifecycle()
    val progress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val error by viewModel.updateError.collectAsStateWithLifecycle()
    val detail by viewModel.errorDetail.collectAsStateWithLifecycle()
    val failed by viewModel.downloadFailed.collectAsStateWithLifecycle()
    val update = result
    if (!show || update == null) return

    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = { viewModel.dismissUpdateDialog() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Teal, Sky))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(painterResource(R.drawable.ic_cloud_download_24dp), null, tint = Color(0xFF04132B), modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.update_sheet_title), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(stringResource(R.string.update_sheet_sub), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (downloaded == null) {
                Spacer(Modifier.height(18.dp))
                Text(update.releaseNotes.orEmpty(), fontSize = 15.sp, lineHeight = 22.sp)
            }

            Spacer(Modifier.height(20.dp))
            val file = downloaded
            when {
                progress != null -> {
                    val p = progress!!
                    Text(stringResource(R.string.update_downloading, if (p >= 0) "$p%" else ""), fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    if (p >= 0) Bar(p / 100f) else LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)), color = Teal)
                }

                file != null -> {
                    Text(stringResource(R.string.update_file_ready), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Bar(1f)
                    Spacer(Modifier.height(14.dp))
                    Text(file.sha256, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = Teal)
                    Spacer(Modifier.height(10.dp))
                    file.checks.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Box(
                                Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (c.ok) Teal.copy(alpha = .22f) else Warn.copy(alpha = .22f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (c.ok) "✓" else "!", color = if (c.ok) Teal else Warn, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(c.textRes), fontSize = 14.sp, color = if (c.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    BigButton(stringResource(R.string.update_install_btn)) { viewModel.installDownloaded() }
                    if (error != null) ErrorBlock(error, detail)
                }

                else -> {
                    if (error != null) ErrorBlock(error, detail)
                    BigButton(stringResource(R.string.update_now)) { viewModel.downloadAndInstall() }
                    if ((failed || error != null) && !update.downloadUrl.isNullOrBlank()) {
                        TextButton(onClick = { update.downloadUrl?.let { Utils.openUri(context, it) } }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.update_open_browser))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { viewModel.later() }) { Text(stringResource(R.string.update_later)) }
                        TextButton(onClick = { viewModel.skipVersion() }) { Text(stringResource(R.string.update_skip)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
        color = Teal,
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f),
    )
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.horizontalGradient(listOf(Teal, Sky)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color(0xFF04132B), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ErrorBlock(error: Int?, detail: String?) {
    Column(Modifier.padding(bottom = 10.dp)) {
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
        detail?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
}
