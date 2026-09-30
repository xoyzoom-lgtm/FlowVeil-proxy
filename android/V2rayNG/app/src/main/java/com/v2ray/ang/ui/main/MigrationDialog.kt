package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.compose.SelectListDialog
import com.v2ray.ang.util.QRCodeDecoder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/** "Move from another app": a subscription cannot be read out of another app, so it comes in by link, QR or a v2rayNG backup. */
@Composable
fun MigrationDialog(
    onClipboard: () -> Unit,
    onBackup: () -> Unit,
    onTvLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var choose by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var tvOpen by remember { mutableStateOf(false) }

    // The QR shows one of OUR subscriptions, to be scanned by another device (or another app).
    fun showQr() {
        val subs = MmkvManager.decodeSubscriptions()
            .filter { it.subscription.url.startsWith("http", ignoreCase = true) }
            .map { (it.subscription.profileTitle?.takeIf { t -> t.isNotBlank() } ?: it.subscription.remarks) to it.subscription.url }
        when {
            subs.isEmpty() -> context.toastError(R.string.migrate_no_subs)
            subs.size == 1 -> qr = QRCodeDecoder.createQRCode(subs[0].second)
            else -> choose = subs
        }
    }
    choose?.let { list ->
        SelectListDialog(
            options = list,
            optionText = { it.first },
            onSelected = { qr = QRCodeDecoder.createQRCode(it.second); choose = null },
            onDismiss = { choose = null },
            title = stringResource(R.string.migrate_qr),
        )
    }
    if (qr != null) QRCodeDialog(bitmap = qr, onDismiss = { qr = null })
    if (tvOpen) TvReceiveDialog(onLink = onTvLink, onDismiss = { tvOpen = false })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.migrate_title)) },
        text = {
            Column {
                Text(stringResource(R.string.migrate_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { onDismiss(); onClipboard() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.migrate_clipboard))
                }
                OutlinedButton(onClick = { showQr() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.migrate_qr))
                }
                OutlinedButton(onClick = { onDismiss(); onBackup() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.migrate_backup))
                }
                OutlinedButton(onClick = { tvOpen = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.migrate_tv))
                }
                Text(
                    stringResource(R.string.migrate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
