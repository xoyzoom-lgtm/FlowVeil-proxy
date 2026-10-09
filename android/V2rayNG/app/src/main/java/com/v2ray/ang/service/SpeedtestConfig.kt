package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.core.SingboxBridge
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.BypassRating
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.net.BypassLevel
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
     * The check for a bypass candidate: one request to [PingUrls.WHITELIST], the address that servers used on
     * mobile whitelists are known to carry. The delay is returned, -1 when it fails. The app is excluded from its
     * own tunnel, so this runs on the phone's real network. The native call reports only a time, so it is a
     * filter; the exact HTTP 204 and the data check are done afterwards on the live connection.
     */
    fun measureWhitelist(context: Context, guid: String): Long =
        SingboxBridge.scoped {
            val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!result.status) return@scoped -1L
            // One request to the address that whitelist servers are known to carry (see PingUrls.WHITELIST).
            runCatching { CoreNativeManager.measureOutboundDelay(result.content, PingUrls.WHITELIST) }.getOrDefault(-1L)
        }

    private fun measureInScope(context: Context, guid: String): Long {
        val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!result.status) return -1L
        // A custom JSON profile is tested exactly as it connects (its DNS hosts, dialer chains and
        // routing decide whether the server works, e.g. in whitelist configs). Slimming it down
        // gave wrong results, so the full config runs, one at a time (see [limiterType]).
        val content = result.content
        val ordinary = listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(second = true))
        // Servers made for mobile whitelists are pinged with the whitelist address first (see PingUrls.WHITELIST), the rest as always.
        val urls = (if (isWhitelistServer(guid)) listOf(PingUrls.WHITELIST) + ordinary else ordinary).distinct()
        // gstatic first (a light, predictable answer: 204 with no body), cloudflare only when gstatic
        // gets no answer. One sample: a list check of hundreds of servers must stay quick, and
        // "works / does not work" never throws, it is always a number or -1.
        for (url in urls) {
            val delay = runCatching { CoreNativeManager.measureOutboundDelay(content, url) }.getOrDefault(-1L)
            if (delay > 0) return delay
        }
        return -1L
    }

    /**
     * A server that is connected right now carries real traffic, so a failed isolated test must not mark it "not working"
     * (the test core can differ from the real connection, e.g. custom JSON profiles or servers that pass only a few hosts).
     * Asks the running core instead, through the same outbound the traffic uses, and stores the delay when any address answers.
     */
    fun verifyRunning(guid: String): Long {
        for (url in listOf(PingUrls.WHITELIST, PingUrls.PRIMARY, PingUrls.FALLBACK, PingUrls.TELEGRAM)) {
            val time = com.v2ray.ang.core.CoreServiceManager.measureLive(url)
            if (time > 0) {
                MmkvManager.encodeServerTestDelayMillis(guid, time)
                return time
            }
        }
        return -1L
    }

    private fun isWhitelistServer(guid: String): Boolean = runCatching {
        WhitelistBypass.servers().contains(guid) ||
            BypassRating.rate(guid, null)?.rating?.level.let { it == BypassLevel.STRONG || it == BypassLevel.LIKELY }
    }.getOrDefault(false)

    /** Custom profiles are serialized by [RealPingExecutionLimiter]; the rest run in parallel. */
    fun limiterType(guid: String): EConfigType {
        return MmkvManager.decodeServerConfig(guid)?.configType ?: EConfigType.VLESS
    }
}
