package com.v2ray.ang.ui.main

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.TvReceiver
import com.v2ray.ang.util.QRCodeDecoder

/** A TV (or any device) shows a QR code; the phone opens it and sends the subscription link here. */
@Composable
fun TvReceiveDialog(onLink: (String) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var received by remember { mutableIntStateOf(0) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val receiver = TvReceiver { link ->
            main.post {
                received++
                onLink(link)
            }
        }
        val url = runCatching { receiver.start() }.getOrNull()
        if (url == null) {
            failed = true
        } else {
            address = url
            qr = QRCodeDecoder.createQRCode(url, 600)
        }
        onDispose { receiver.close() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tv_title)) },
        text = {
            Column {
                if (failed) {
                    Text(stringResource(R.string.tv_no_network))
                } else {
                    Text(stringResource(R.string.tv_hint), style = MaterialTheme.typography.bodyMedium)
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = stringResource(R.string.acc_qr_code),
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 12.dp)
                        )
                    }
                    address?.let {
                        Text(it.substringBefore('?'), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (received > 0) {
                        Text(stringResource(R.string.tv_received, received), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
