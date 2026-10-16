package com.v2ray.ang.ui.main.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.PingPrefs
import com.v2ray.ang.net.PingChoice

/** Short label of the current ping way for the server list header ("Пинг: через сервер · 10 с"). */
@Composable
fun pingSummary(): String {
    val m = when (PingPrefs.method()) {
        PingChoice.Method.PROXY -> stringResource(R.string.ping_method_proxy_short)
        PingChoice.Method.SMART -> stringResource(R.string.ping_method_smart_short)
        PingChoice.Method.TCP -> "TCP"
        PingChoice.Method.TLS -> "TLS"
        PingChoice.Method.ICMP -> "ICMP"
    }
    return stringResource(R.string.ping_summary, m, PingPrefs.timeoutSeconds())
}

/**
 * "Ping": how the servers are checked, in a few taps. The way (a real request through the server, TCP, ICMP), the test
 * address (for the real request), when a slow server counts as not working, and the number or an icon in the list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PingSheet(onCheckNow: (() -> Unit)?, onDismiss: () -> Unit) {
    var method by remember { mutableStateOf(PingPrefs.method()) }
    var timeout by remember { mutableStateOf(PingPrefs.timeoutSeconds()) }
    var two by remember { mutableStateOf(PingPrefs.bestOfTwo()) }
    var url by remember { mutableStateOf(PingPrefs.url()) }
    var custom by remember { mutableStateOf(PingChoice.URL_PRESETS.none { it.url == PingPrefs.url() }) }
    var customText by remember { mutableStateOf(if (custom) PingPrefs.url() else "") }
    var customBad by remember { mutableStateOf(false) }
    val icon by PingPrefs.showIconState

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            Text(stringResource(R.string.ping_title), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)

            Section(stringResource(R.string.ping_how))
            MethodCard(stringResource(R.string.ping_method_proxy), stringResource(R.string.ping_method_proxy_hint), method == PingChoice.Method.PROXY) {
                method = PingChoice.Method.PROXY; PingPrefs.setMethod(method)
            }
            MethodCard(stringResource(R.string.ping_method_smart), stringResource(R.string.ping_method_smart_hint), method == PingChoice.Method.SMART) {
                method = PingChoice.Method.SMART; PingPrefs.setMethod(method)
            }
            MethodCard("TCP", stringResource(R.string.ping_method_tcp_hint), method == PingChoice.Method.TCP) {
                method = PingChoice.Method.TCP; PingPrefs.setMethod(method)
            }
            MethodCard("TLS", stringResource(R.string.ping_method_tls_hint), method == PingChoice.Method.TLS) {
                method = PingChoice.Method.TLS; PingPrefs.setMethod(method)
            }
            MethodCard("ICMP", stringResource(R.string.ping_method_icmp_hint), method == PingChoice.Method.ICMP) {
                method = PingChoice.Method.ICMP; PingPrefs.setMethod(method)
            }

            if (method == PingChoice.Method.PROXY || method == PingChoice.Method.SMART) {
                Section(stringResource(R.string.ping_mode))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !two, onClick = { two = false; PingPrefs.setBestOfTwo(false) }, label = { Text(stringResource(R.string.ping_mode_default)) })
                    FilterChip(selected = two, onClick = { two = true; PingPrefs.setBestOfTwo(true) }, label = { Text(stringResource(R.string.ping_mode_double)) })
                }
                Text(
                    stringResource(if (two) R.string.ping_mode_double_hint else R.string.ping_mode_default_hint),
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (method == PingChoice.Method.PROXY || method == PingChoice.Method.SMART) {
                Section(stringResource(R.string.ping_url))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PingChoice.URL_PRESETS.forEach { p ->
                        FilterChip(
                            selected = !custom && url == p.url,
                            onClick = { custom = false; customBad = false; if (PingPrefs.setUrl(p.url)) url = p.url },
                            label = { Text(p.label) },
                        )
                    }
                    FilterChip(selected = custom, onClick = { custom = true }, label = { Text(stringResource(R.string.ping_url_custom)) })
                }
                if (custom) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = {
                            customText = it
                            customBad = !PingPrefs.setUrl(it)
                            if (!customBad) url = it.trim()
                        },
                        singleLine = true,
                        isError = customBad && customText.isNotBlank(),
                        placeholder = { Text("https://…") },
                        supportingText = { if (customBad && customText.isNotBlank()) Text(stringResource(R.string.ping_url_bad)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                Text(url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }

            Section(stringResource(R.string.ping_timeout))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PingChoice.TIMEOUTS.forEach { s ->
                    FilterChip(selected = timeout == s, onClick = { timeout = s; PingPrefs.setTimeoutSeconds(s) }, label = { Text(stringResource(R.string.ping_seconds, s)) })
                }
            }

            Section(stringResource(R.string.ping_show))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !icon, onClick = { PingPrefs.setShowIcon(false) }, label = { Text(stringResource(R.string.ping_show_ms)) })
                FilterChip(selected = icon, onClick = { PingPrefs.setShowIcon(true) }, label = { Text(stringResource(R.string.ping_show_icon)) })
            }

            Spacer(Modifier.height(18.dp))
            if (onCheckNow != null) {
                Button(onClick = { onDismiss(); onCheckNow() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.ping_check_now), fontWeight = FontWeight.Bold)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun MethodCard(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(end = 8.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(hint, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
