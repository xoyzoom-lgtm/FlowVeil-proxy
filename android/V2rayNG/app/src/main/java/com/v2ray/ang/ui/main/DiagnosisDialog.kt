package com.v2ray.ang.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.BatteryOptimization
import com.v2ray.ang.handler.DiagnosticsRunner
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.net.BatteryGuide
import com.v2ray.ang.net.DiagCause
import com.v2ray.ang.net.DiagResult
import com.v2ray.ang.net.DiagStep
import com.v2ray.ang.net.DiagStepId
import com.v2ray.ang.net.StepStatus

private fun causeTitle(c: DiagCause): Int = when (c) {
    DiagCause.OK -> R.string.diag_cause_ok
    DiagCause.NO_NETWORK -> R.string.diag_cause_no_network
    DiagCause.CAPTIVE_PORTAL -> R.string.diag_cause_captive_portal
    DiagCause.WRONG_TIME -> R.string.diag_cause_wrong_time
    DiagCause.DNS_FAILED -> R.string.diag_cause_dns_failed
    DiagCause.SUB_EXPIRED -> R.string.diag_cause_sub_expired
    DiagCause.SUB_TRAFFIC_OVER -> R.string.diag_cause_sub_traffic_over
    DiagCause.SUB_DEVICE_LIMIT -> R.string.diag_cause_sub_device_limit
    DiagCause.SUB_BLOCKED -> R.string.diag_cause_sub_blocked
    DiagCause.SUB_ACCESS_DENIED -> R.string.diag_cause_sub_access_denied
    DiagCause.SUB_LINK_UNKNOWN -> R.string.diag_cause_sub_link_unknown
    DiagCause.SUB_WEB_PAGE -> R.string.diag_cause_sub_web_page
    DiagCause.SUB_HAPP_CRYPT -> R.string.diag_cause_sub_happ_crypt
    DiagCause.SUB_NO_SERVERS -> R.string.diag_cause_sub_no_servers
    DiagCause.SUB_RATE_LIMITED -> R.string.diag_cause_sub_rate_limited
    DiagCause.SUB_PROVIDER_DOWN -> R.string.diag_cause_sub_provider_down
    DiagCause.SUB_UNREACHABLE -> R.string.diag_cause_sub_unreachable
    DiagCause.NO_SERVER -> R.string.diag_cause_no_server
    DiagCause.NOT_CONNECTED -> R.string.diag_cause_not_connected
    DiagCause.SERVER_DOWN -> R.string.diag_cause_server_down
    DiagCause.SERVER_NOT_PASSING -> R.string.diag_cause_server_not_passing
    DiagCause.MOBILE_RESTRICTED -> R.string.diag_cause_mobile_restricted
    DiagCause.BATTERY_RESTRICTED -> R.string.diag_cause_battery_restricted
}

private fun causeFix(c: DiagCause): Int = when (c) {
    DiagCause.OK -> R.string.diag_fix_ok
    DiagCause.NO_NETWORK -> R.string.diag_fix_no_network
    DiagCause.CAPTIVE_PORTAL -> R.string.diag_fix_captive_portal
    DiagCause.WRONG_TIME -> R.string.diag_fix_wrong_time
    DiagCause.DNS_FAILED -> R.string.diag_fix_dns_failed
    DiagCause.SUB_EXPIRED -> R.string.diag_fix_sub_expired
    DiagCause.SUB_TRAFFIC_OVER -> R.string.diag_fix_sub_traffic_over
    DiagCause.SUB_DEVICE_LIMIT -> R.string.diag_fix_sub_device_limit
    DiagCause.SUB_BLOCKED -> R.string.diag_fix_sub_blocked
    DiagCause.SUB_ACCESS_DENIED -> R.string.diag_fix_sub_access_denied
    DiagCause.SUB_LINK_UNKNOWN -> R.string.diag_fix_sub_link_unknown
    DiagCause.SUB_WEB_PAGE -> R.string.diag_fix_sub_web_page
    DiagCause.SUB_HAPP_CRYPT -> R.string.diag_fix_sub_happ_crypt
    DiagCause.SUB_NO_SERVERS -> R.string.diag_fix_sub_no_servers
    DiagCause.SUB_RATE_LIMITED -> R.string.diag_fix_sub_rate_limited
    DiagCause.SUB_PROVIDER_DOWN -> R.string.diag_fix_sub_provider_down
    DiagCause.SUB_UNREACHABLE -> R.string.diag_fix_sub_unreachable
    DiagCause.NO_SERVER -> R.string.diag_fix_no_server
    DiagCause.NOT_CONNECTED -> R.string.diag_fix_not_connected
    DiagCause.SERVER_DOWN -> R.string.diag_fix_server_down
    DiagCause.SERVER_NOT_PASSING -> R.string.diag_fix_server_not_passing
    DiagCause.MOBILE_RESTRICTED -> R.string.diag_fix_mobile_restricted
    DiagCause.BATTERY_RESTRICTED -> batteryFix()
}

private fun batteryFix(): Int = when (BatteryGuide.maker(Build.MANUFACTURER, Build.BRAND)) {
    BatteryGuide.Maker.XIAOMI -> R.string.diag_battery_xiaomi
    BatteryGuide.Maker.HUAWEI -> R.string.diag_battery_huawei
    BatteryGuide.Maker.SAMSUNG -> R.string.diag_battery_samsung
    BatteryGuide.Maker.ONEPLUS -> R.string.diag_battery_oneplus
    BatteryGuide.Maker.VIVO -> R.string.diag_battery_vivo
    BatteryGuide.Maker.OPPO -> R.string.diag_battery_oppo
    BatteryGuide.Maker.OTHER -> R.string.diag_battery_other
}

private fun stepTitle(id: DiagStepId): Int = when (id) {
    DiagStepId.NETWORK -> R.string.diag_step_network
    DiagStepId.TIME -> R.string.diag_step_time
    DiagStepId.DNS -> R.string.diag_step_dns
    DiagStepId.SUBSCRIPTION -> R.string.diag_step_subscription
    DiagStepId.SERVER -> R.string.diag_step_server
    DiagStepId.END_TO_END -> R.string.diag_step_end_to_end
    DiagStepId.RESTRICTION -> R.string.diag_step_restriction
    DiagStepId.BATTERY -> R.string.diag_step_battery
}

private val SUB_CAUSES = setOf(
    DiagCause.SUB_EXPIRED, DiagCause.SUB_TRAFFIC_OVER, DiagCause.SUB_DEVICE_LIMIT, DiagCause.SUB_BLOCKED, DiagCause.SUB_ACCESS_DENIED,
    DiagCause.SUB_LINK_UNKNOWN, DiagCause.SUB_WEB_PAGE, DiagCause.SUB_HAPP_CRYPT, DiagCause.SUB_NO_SERVERS, DiagCause.SUB_RATE_LIMITED,
    DiagCause.SUB_PROVIDER_DOWN, DiagCause.SUB_UNREACHABLE,
)

/** The support link the provider sent with the subscription of the selected server, if any. */
private fun supportUrl(): String? {
    val guid = MmkvManager.getSelectServer() ?: return null
    val subId = MmkvManager.decodeServerConfig(guid)?.subscriptionId?.takeIf { it.isNotBlank() } ?: return null
    return MmkvManager.decodeSubscription(subId)?.supportUrl?.takeIf { it.isNotBlank() }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * "Why does it not work?": runs the checks one after another, shows each result as it arrives, then a plain
 * verdict with what to do. Nothing leaves the phone; the copied report is masked (no links, keys, addresses).
 */
@Composable
fun DiagnosisDialog(running: Boolean, onUpdateSubscription: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var steps by remember { mutableStateOf<List<DiagStep>>(emptyList()) }
    var result by remember { mutableStateOf<DiagResult?>(null) }
    var runId by remember { mutableIntStateOf(0) }

    LaunchedEffect(runId) {
        result = null
        steps = emptyList()
        val final = DiagnosticsRunner.run(context, running) { id, partial ->
            steps = partial.steps.filter { it.id.ordinal <= id.ordinal }
        }
        steps = final.steps
        result = final
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.diag_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                steps.filter { it.status != StepStatus.SKIPPED }.forEach { StepRow(it) }
                val r = result
                if (r != null) {
                    Text(
                        stringResource(causeTitle(r.cause)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (r.cause == DiagCause.OK) Color(0xFF22C55E) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Text(stringResource(causeFix(r.cause)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    r.warnings.forEach { w ->
                        Text(
                            "${stringResource(R.string.diag_warning_prefix)}: ${stringResource(causeTitle(w))}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Text(stringResource(causeFix(w)), style = MaterialTheme.typography.bodySmall)
                    }
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (r.cause in SUB_CAUSES) {
                            OutlinedButton(onClick = { onDismiss(); onUpdateSubscription() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diag_update_sub)) }
                            supportUrl()?.let { url -> OutlinedButton(onClick = { openUrl(context, url) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diag_open_support)) } }
                        }
                        if (r.cause == DiagCause.WRONG_TIME) {
                            OutlinedButton(
                                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.diag_open_time)) }
                        }
                        if (DiagCause.BATTERY_RESTRICTED in r.warnings) {
                            OutlinedButton(onClick = { BatteryOptimization.request(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.diag_open_battery)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = result != null, onClick = { runId++ }) { Text(stringResource(R.string.diag_retry)) }
                TextButton(
                    enabled = result != null,
                    onClick = {
                        val text = DiagnosticsRunner.report(result ?: return@TextButton)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("FlowVeil report", text))
                        context.toast(R.string.diag_copied)
                    },
                ) { Text(stringResource(R.string.diag_copy_report)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun StepRow(step: DiagStep) {
    val (glyph, color) = when (step.status) {
        StepStatus.OK -> "✓" to Color(0xFF22C55E)
        StepStatus.WARN -> "!" to Color(0xFFF59E0B)
        StepStatus.FAIL -> "✕" to MaterialTheme.colorScheme.error
        StepStatus.SKIPPED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(glyph, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
        Text(stringResource(stepTitle(step.id)), style = MaterialTheme.typography.bodyMedium)
    }
}
