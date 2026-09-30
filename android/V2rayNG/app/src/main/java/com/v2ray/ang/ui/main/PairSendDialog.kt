package com.v2ray.ang.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PairClient
import com.v2ray.ang.net.PairProtocol
import kotlinx.coroutines.launch

/**
 * "Send to computer": the computer shows a FlowVeil Pair QR code (or an address and a 6-digit code); the chosen
 * subscription links go to it over the home network, encrypted when the QR was scanned. The computer asks for
 * confirmation before adding anything.
 * [qr] null = manual entry (address + code). [preset] = links already chosen (shared text).
 */
@Composable
fun PairSendDialog(qr: PairProtocol.Qr?, preset: List<String>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val subs = remember { MmkvManager.decodeSubscriptions().filter { it.subscription.url.startsWith("http") } }
    var selected by remember { mutableStateOf(if (preset != null) emptySet() else subs.map { it.guid }.take(1).toSet()) }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pcName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(qr) {
        if (qr != null) {
            pcName = PairClient.info(context, qr)
            if (pcName == null) status = context.getString(R.string.pair_err_route)
        }
    }

    fun message(r: PairClient.Result) = context.getString(
        when (r) {
            PairClient.Result.OK -> R.string.pair_done
            PairClient.Result.BAD_TOKEN -> R.string.pair_err_token
            PairClient.Result.EXPIRED -> R.string.pair_err_expired
            PairClient.Result.USED -> R.string.pair_err_used
            PairClient.Result.LOCKED -> R.string.pair_err_locked
            PairClient.Result.REJECTED -> R.string.pair_err_rejected
            PairClient.Result.NOT_HOME_NETWORK -> R.string.pair_err_private
            PairClient.Result.NO_ROUTE -> R.string.pair_err_route
        }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pair_title)) },
        text = {
            Column {
                if (qr != null) {
                    Text(stringResource(R.string.pair_to, pcName?.ifBlank { qr.host } ?: qr.host), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.pair_manual_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(address, { address = it.trim() }, label = { Text(stringResource(R.string.pair_address)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.pair_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                if (preset != null) {
                    Text(stringResource(R.string.pair_shared_link), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else if (subs.isEmpty()) {
                    Text(stringResource(R.string.pair_no_subs), modifier = Modifier.padding(top = 8.dp))
                } else {
                    subs.forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                selected = if (s.guid in selected) selected - s.guid else selected + s.guid
                            }
                        ) {
                            Checkbox(checked = s.guid in selected, onCheckedChange = null)
                            Text(s.subscription.remarks.ifBlank { s.subscription.url }, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (!done) {
                TextButton(
                    enabled = !busy && (preset != null || selected.isNotEmpty()) && (qr != null || (address.isNotEmpty() && code.length == 6)),
                    onClick = {
                        busy = true
                        status = null
                        scope.launch {
                            val items = preset ?: subs.filter { it.guid in selected }.map { it.subscription.url }
                            val name = if (items.size == 1) subs.firstOrNull { it.subscription.url == items[0] }?.subscription?.remarks else null
                            val r = if (qr != null) PairClient.send(context, qr, items, name) else PairClient.sendWithCode(context, address, code, items, name)
                            status = message(r)
                            done = r == PairClient.Result.OK
                            busy = false
                        }
                    }
                ) { Text(stringResource(R.string.pair_send)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
