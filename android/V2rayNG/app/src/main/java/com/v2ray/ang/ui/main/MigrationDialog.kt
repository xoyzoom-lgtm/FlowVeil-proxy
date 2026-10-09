package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.v2ray.ang.R

/** The apps people move from most often, in the order shown. Names are plain text (no logos); each has its own short how-to. */
private val MIGRATION_APPS = listOf(
    "Happ" to R.string.migrate_steps_happ,
    "INCY" to R.string.migrate_steps_incy,
    "v2rayNG" to R.string.migrate_steps_v2rayng,
    "v2rayN" to R.string.migrate_steps_v2rayn,
    "Hiddify" to R.string.migrate_steps_hiddify,
    "Streisand" to R.string.migrate_steps_streisand,
    "Karing" to R.string.migrate_steps_karing,
    "NekoBox" to R.string.migrate_steps_nekobox,
    "Shadowrocket" to R.string.migrate_steps_shadowrocket,
    "Clash" to R.string.migrate_steps_clash,
)

/**
 * "Move from another app": a subscription cannot be read out of another app, so it comes in by
 * link, by a QR code that THIS app shows (the other device opens it and sends the link here), or
 * from a v2rayNG backup.
 */
@Composable
fun MigrationDialog(
    onClipboard: () -> Unit,
    onBackup: () -> Unit,
    onLink: (String) -> Unit,
    onSendToPc: () -> Unit,
    onSendManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    var qrOpen by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    if (qrOpen) TvReceiveDialog(onLink = onLink, onDismiss = { qrOpen = false })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.migrate_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.migrate_from), style = MaterialTheme.typography.titleSmall)
                MIGRATION_APPS.chunked(3).forEach { rowApps ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        rowApps.forEach { (name, _) ->
                            FilterChip(
                                selected = picked == name,
                                onClick = { picked = if (picked == name) null else name },
                                label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
                MIGRATION_APPS.firstOrNull { it.first == picked }?.let { (_, steps) ->
                    Text(stringResource(steps), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        stringResource(R.string.migrate_app_names_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Text(stringResource(R.string.migrate_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
                OutlinedButton(onClick = { qrOpen = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.migrate_qr))
                }
                OutlinedButton(onClick = { onDismiss(); onClipboard() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.migrate_clipboard))
                }
                OutlinedButton(onClick = { onDismiss(); onBackup() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.migrate_backup))
                }
                OutlinedButton(onClick = onSendToPc, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.pair_scan_pc))
                }
                OutlinedButton(onClick = onSendManual, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(stringResource(R.string.pair_manual))
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
