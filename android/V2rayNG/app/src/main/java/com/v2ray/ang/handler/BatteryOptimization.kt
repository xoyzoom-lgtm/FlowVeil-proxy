package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Many phone makers put apps to sleep in the background. The auto bypass has to react to a network
 * change while the phone is in a pocket, so it asks for the "no battery restrictions" exemption.
 */
object BatteryOptimization {
    fun isIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /** The system dialog first; the list of exemptions, then the app's own page, if a rom lacks it. */
    fun request(context: Context) {
        val uri = Uri.parse("package:${context.packageName}")
        val attempts = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri),
        )
        for (intent in attempts) {
            val started = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (started) return
        }
    }
}
