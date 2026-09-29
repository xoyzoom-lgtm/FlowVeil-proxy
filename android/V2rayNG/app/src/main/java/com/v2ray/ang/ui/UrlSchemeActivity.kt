package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.v2ray.ang.AppConfig
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import java.net.URLDecoder

/**
 * Opens shared text and invite links and hands them to the main screen, which imports them
 * like a paste (downloads the servers and shows the result):
 *  - flowveil://add?url=<encoded link>   flowveil://add/<link>   flowveil://install-sub?url=...
 *  - v2rayng://install-sub?url=...  and  v2rayng://install-config?url=...
 */
class UrlSchemeActivity : BaseComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = try {
            when (intent.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.data?.let(::linkFromUri)
                else -> null
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Error processing URL scheme", e)
            null
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (!text.isNullOrBlank()) putExtra(MainActivity.EXTRA_IMPORT_TEXT, text) }
        )
        finish()
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun linkFromUri(uri: Uri): String? {
        val raw = uri.getQueryParameter("url")
            ?: uri.encodedPath?.trimStart('/')?.takeIf { it.isNotBlank() }?.let { path ->
                // flowveil://add/https://host/path -> everything after the first segment
                val rest = uri.toString().substringAfter("://").substringAfter('/', "")
                rest.ifBlank { path }
            }
            ?: return null
        var decoded = if (raw.contains("%3A", ignoreCase = true) || raw.contains("%2F", ignoreCase = true)) {
            URLDecoder.decode(raw, "UTF-8")
        } else {
            raw
        }
        val fragment = uri.fragment
        if (!fragment.isNullOrEmpty() && !decoded.contains('#')) decoded += "#$fragment"
        LogUtil.i(AppConfig.TAG, "Import link: $decoded")
        return decoded
    }
}
