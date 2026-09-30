package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.SingboxBridge
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.net.PingUrls

/** One real connection test of a saved server, shared by the server list check and the watchdog. */
object SpeedtestConfig {

    /**
     * Delay in ms through [guid], or -1 when it does not work. Tries the second test URL when
     * the first one fails.
     */
    fun measure(context: Context, guid: String): Long =
        // Servers run through a sing-box bridge (TUIC) get a private one that ends with the test.
        SingboxBridge.scoped { measureInScope(context, guid) }

    /**
     * The check for a bypass candidate: two independent hosts must both answer through the server
     * (a proxy that lets one host through proves little). The delay of the first is returned, -1
     * when either fails. The app is excluded from its own tunnel, so this runs on the phone's
     * real network. The native call reports only a time, so it is a filter; the exact HTTP 204 and
     * the data check are done afterwards on the live connection.
     */
    fun measureStrict(context: Context, guid: String): Long =
        SingboxBridge.scoped {
            val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!result.status) return@scoped -1L
            var first = -1L
            for (url in listOf(PingUrls.PRIMARY, PingUrls.FALLBACK)) {
                val delay = runCatching { CoreNativeManager.measureOutboundDelay(result.content, url) }.getOrDefault(-1L)
                if (delay <= 0) return@scoped -1L
                if (first < 0) first = delay
            }
            first
        }

    private fun measureInScope(context: Context, guid: String): Long {
        val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!result.status) return -1L
        // A custom JSON profile is tested exactly as it connects (its DNS hosts, dialer chains and
        // routing decide whether the server works, e.g. in whitelist configs). Slimming it down
        // gave wrong results, so the full config runs, one at a time (see [limiterType]).
        val content = result.content
        val urls = listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(second = true)).distinct()
        // gstatic first (a light, predictable answer: 204 with no body), cloudflare only when gstatic
        // gets no answer. One sample: a list check of hundreds of servers must stay quick, and
        // "works / does not work" never throws, it is always a number or -1.
        for (url in urls) {
            val delay = runCatching { CoreNativeManager.measureOutboundDelay(content, url) }.getOrDefault(-1L)
            if (delay > 0) return delay
        }
        return -1L
    }

    /** Custom profiles are serialized by [RealPingExecutionLimiter]; the rest run in parallel. */
    fun limiterType(guid: String): EConfigType {
        return MmkvManager.decodeServerConfig(guid)?.configType ?: EConfigType.VLESS
    }
}
