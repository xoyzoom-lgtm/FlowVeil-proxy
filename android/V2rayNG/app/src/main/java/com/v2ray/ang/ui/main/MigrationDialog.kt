package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R

/** "Move from another app": a subscription cannot be read out of another app, so it comes in by link, QR or a v2rayNG backup. */
@Composable
fun MigrationDialog(
    onClipboard: () -> Unit,
    onQr: () -> Unit,
    onBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.migrate_title)) },
        text = {
            Column {
                Text(stringResource(R.string.migrate_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { onDismiss(); onClipboard() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.migrate_clipboard))
                }
                OutlinedButton(onClick = { onDismiss(); onQr() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.migrate_qr))
                }
                OutlinedButton(onClick = { onDismiss(); onBackup() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.migrate_backup))
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
