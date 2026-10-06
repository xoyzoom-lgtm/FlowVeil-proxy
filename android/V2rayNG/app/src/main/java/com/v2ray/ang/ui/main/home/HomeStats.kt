package com.v2ray.ang.ui.main.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.net.TrafficDays
import com.v2ray.ang.ui.main.MainBackground

/** Traffic and connected time per day: a big number for today, bars for the last 7 or 30 days, and the totals. */
@Composable
internal fun HomeStats(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var range by remember { mutableIntStateOf(7) }
    var version by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val all = remember(version) { TrafficDays.parse(MmkvManager.decodeSettingsString(AppConfig.PREF_TRAFFIC_DAYS)) }
    val now = remember(version) { System.currentTimeMillis() }
    val days = remember(all, range) { TrafficDays.lastDays(all, range, now) }
    val today = days.last()
    val sum = TrafficDays.sum(days)
    val units = listOf(
        stringResource(R.string.stats_unit_b), stringResource(R.string.stats_unit_kb),
        stringResource(R.string.stats_unit_mb), stringResource(R.string.stats_unit_gb),
    )
    val hour = stringResource(R.string.stats_hour)
    val minute = stringResource(R.string.stats_min)
    val accent = homeAccent()

    Box(Modifier.fillMaxSize().screenBase(solid = true)) {
        MainBackground()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            ) {
                RoundAction(R.drawable.ic_arrow_back_24dp, stringResource(R.string.onb_back), onClose, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.stats_title), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onBackground)
            }
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Today
                StatCard {
                    Text(stringResource(R.string.stats_today).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(TrafficDays.size(today.total, units), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.stats_today_sub, TrafficDays.size(today.proxyDown, units), TrafficDays.size(today.proxyUp, units)),
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Period switch + bars
                StatCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.stats_period), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        listOf(7, 30).forEach { n ->
                            val on = range == n
                            Box(
                                Modifier
                                    .padding(start = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) accent else homeSurface())
                                    .clickable { range = n }
                                    .heightIn(min = 36.dp)
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(if (n == 7) R.string.stats_range_7 else R.string.stats_range_30),
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (on) com.v2ray.ang.ui.main.mainOnAccentColor() else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (sum.total == 0L && sum.seconds == 0L) {
                        Text(stringResource(R.string.stats_empty), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
                    } else {
                        val max = (days.maxOf { it.total }).coerceAtLeast(1L)
                        val faint = accent.copy(alpha = 0.45f)
                        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                            val n = days.size
                            val gap = if (n > 14) 3.dp.toPx() else 8.dp.toPx()
                            val barW = (size.width - gap * (n - 1)) / n
                            days.forEachIndexed { i, d ->
                                val h = (size.height * (d.total.toFloat() / max)).coerceAtLeast(if (d.total > 0) 6.dp.toPx() else 3.dp.toPx())
                                drawRoundRect(
                                    color = if (i == n - 1) accent else faint,
                                    topLeft = Offset(i * (barW + gap), size.height - h),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(minOf(barW / 2, 8.dp.toPx())),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(days.first().day.takeLast(5).replace('-', '.').let { it.substring(3) + "." + it.substring(0, 2) }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.stats_today), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Totals
                StatCard(padded = false) {
                    StatRow(stringResource(R.string.stats_total), TrafficDays.size(sum.total, units))
                    StatRow(stringResource(R.string.stats_proxy), TrafficDays.size(sum.proxy, units))
                    StatRow(stringResource(R.string.stats_direct), TrafficDays.size(sum.direct, units))
                    StatRow(stringResource(R.string.stats_avg), TrafficDays.size(sum.total / range, units))
                    StatRow(stringResource(R.string.stats_time), TrafficDays.duration(sum.seconds, hour, minute), last = true)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(if (confirmReset) R.string.stats_reset_confirm else R.string.stats_reset),
                    fontSize = 13.sp, color = if (confirmReset) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            if (confirmReset) {
                                MmkvManager.encodeSettings(AppConfig.PREF_TRAFFIC_DAYS, "")
                                confirmReset = false
                                version++
                            } else confirmReset = true
                        }
                        .padding(10.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatCard(padded: Boolean = true, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(HomeStyle.r(22))
    Column(
        Modifier.fillMaxWidth().clip(shape).background(homeSurface()).glassEdge(shape).then(if (padded) Modifier.padding(16.dp) else Modifier)
    ) { content() }
}

@Composable
private fun StatRow(label: String, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
    if (!last) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
}
