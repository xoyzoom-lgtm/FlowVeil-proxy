package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.net.ImportPreview
import java.net.URLDecoder

/**
 * Asked before anything from a link or a scanned code is added: nothing is downloaded and nothing (the device id included)
 * is sent until the user presses "Add". The host is shown big; a link without encryption and a look-alike host are said out loud.
 */
@Composable
internal fun ImportConfirmDialog(text: String, onAdd: () -> Unit, onCancel: () -> Unit) {
    val preview = remember(text) { ImportPreview.of(text) }
    if (preview.kind == ImportPreview.Kind.REJECTED) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.import_rejected_title)) },
            text = { Text(stringResource(R.string.import_rejected_text)) },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_close)) } },
        )
        return
    }
    val name = remember(text) {
        text.trim().substringAfter('#', "").takeIf { preview.kind == ImportPreview.Kind.SUBSCRIPTION && it.isNotBlank() }
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }?.take(60)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(stringResource(if (preview.kind == ImportPreview.Kind.SUBSCRIPTION) R.string.import_confirm_title_sub else R.string.import_confirm_title_servers))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (preview.host.isNotEmpty()) {
                    Text(preview.host, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                if (preview.hostAscii.isNotEmpty() && preview.hostAscii != preview.host) {
                    Text(preview.hostAscii, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                name?.let { Text(stringResource(R.string.import_confirm_name, it)) }
                if (preview.kind == ImportPreview.Kind.SERVERS && preview.servers > 0) {
                    Text(stringResource(R.string.import_confirm_servers, preview.servers))
                }
                if (preview.lookAlike) {
                    Text(stringResource(R.string.import_confirm_lookalike, preview.hostAscii), color = MaterialTheme.colorScheme.error)
                }
                if (preview.insecure) {
                    Text(stringResource(R.string.import_confirm_http), color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.import_confirm_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text(stringResource(R.string.import_confirm_add)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
