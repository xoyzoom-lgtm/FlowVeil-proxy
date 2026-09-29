package com.v2ray.ang.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager

/** "What's new" shown once after the app was updated (not on a fresh install). */
internal object WhatsNew {
    private const val KEY = "whats_new_seen_version"
    private const val LEGACY_KEY = "whats_new_seen_build"

    /** Bump when the whats_new_items list changes; the dialog shows once per version. */
    private const val CONTENT_VERSION = 4

    fun shouldShow(): Boolean {
        val seen = MmkvManager.decodeSettingsString(KEY)?.toIntOrNull()
        if (seen == null) {
            // Someone with subscriptions (or who saw an older dialog) is updating; an empty app
            // is a fresh install with nothing to announce.
            val updating = MmkvManager.decodeSettingsString(LEGACY_KEY) != null ||
                MmkvManager.decodeSubscriptions().any { it.subscription.url.isNotBlank() }
            if (!updating) markSeen()
            return updating
        }
        return seen < CONTENT_VERSION
    }

    fun markSeen() = MmkvManager.encodeSettings(KEY, CONTENT_VERSION.toString())
}

@Composable
internal fun WhatsNewDialog() {
    var visible by remember { mutableStateOf(WhatsNew.shouldShow()) }
    if (!visible) return
    val close = {
        WhatsNew.markSeen()
        visible = false
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.whats_new_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
            ) {
                stringArrayResource(R.array.whats_new_items).forEach { item ->
                    Text("• $item", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.whats_new_ok)) } },
    )
}
