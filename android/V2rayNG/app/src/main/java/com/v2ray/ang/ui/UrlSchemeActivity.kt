package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import com.v2ray.ang.AppConfig
import com.v2ray.ang.net.InviteLink
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil

/**
 * Opens shared text and invite links and hands them to the main screen, which imports them
 * like a paste (downloads the servers and shows the result):
 *  - flowveil://add?url=<encoded link>[&name=<title>]   flowveil://add/<link>   flowveil://install-sub?url=...
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
                .apply {
                    if (!text.isNullOrBlank()) putExtra(MainActivity.EXTRA_IMPORT_TEXT, text)
                    if (intent.action == Intent.ACTION_SEND) putExtra(MainActivity.EXTRA_SHARED, true)
                }
        )
        finish()
    }

    @Composable
    override fun ScreenContent() {
    }

    private fun linkFromUri(uri: Uri): String? {
        // A code from a FlowVeil computer: the main screen opens the "send a subscription" window.
        if (uri.host.equals("pair", ignoreCase = true)) return uri.toString()
        // The provider's name from &name= rides along as the link's #fragment: the importer names the new subscription with it.
        val invite = InviteLink.parse(uri.toString()) ?: return null
        LogUtil.i(AppConfig.TAG, "Import invite link")
        return invite.link
    }
}
