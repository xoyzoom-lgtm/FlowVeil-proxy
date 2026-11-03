package com.v2ray.ang.ui.main.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.LanShareManager
import com.v2ray.ang.net.LanShare
import com.v2ray.ang.ui.compose.HomeTokens
import com.v2ray.ang.util.QRCodeDecoder

/**
 * "Share the connection" with other devices of the home network. Off: what it does and one button. On: the address, the login and
 * the password made by the app, a QR code and the one step to do on the other device.
 */
@Composable
internal fun ShareNetSheet(
    state: LanShareManager.State,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    HomeSheet(onDismiss) {
        Text(
            stringResource(R.string.share_net_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        val note = if (state.enabled) R.string.share_net_step else R.string.share_net_about
        Text(
            stringResource(note),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (!state.enabled) {
            Text(
                stringResource(R.string.share_net_warning),
                fontSize = 14.sp,
                color = HomeTokens.warn,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(HomeTokens.warn.copy(alpha = 0.12f))
                    .padding(14.dp)
            )
            Spacer(Modifier.height(10.dp))
            SheetRow(R.drawable.ic_flash_on_24dp, stringResource(R.string.share_net_enable), onClick = onEnable)
            return@HomeSheet
        }
        val ip = state.address
        val user = state.user
        val password = state.password
        if (ip == null || user == null || password == null) {
            Text(
                stringResource(if (ip == null) R.string.share_net_no_network else R.string.share_net_no_login),
                fontSize = 14.sp,
                color = HomeTokens.warn,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        } else {
            val uri = remember(ip, state.port, user, password) { LanShare.proxyUri(user, password, ip, state.port) }
            val bitmap = remember(uri) { QRCodeDecoder.createQRCode(uri) }
            if (bitmap != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(HomeTokens.qrBackground)
                            .padding(12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            SelectionContainer {
                Text(
                    stringResource(R.string.share_net_details, ip, state.port, user, password),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            SheetRow(R.drawable.ic_copy, stringResource(R.string.share_net_copy)) { onCopy(uri) }
        }
        SheetRow(R.drawable.ic_status_cross, stringResource(R.string.share_net_disable), danger = true, onClick = onDisable)
    }
}
