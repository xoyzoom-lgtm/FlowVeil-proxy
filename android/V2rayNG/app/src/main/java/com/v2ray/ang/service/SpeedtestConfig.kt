package com.v2ray.ang.service

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager

/** One real connection test of a saved server, shared by the server list check and the watchdog. */
object SpeedtestConfig {

    /**
     * Delay in ms through [guid], or -1 when it does not work. Tries the second test URL when
     * the first one fails.
     */
    fun measure(context: Context, guid: String): Long {
        val config = MmkvManager.decodeServerConfig(guid) ?: return -1L
        val result = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!result.status) return -1L
        // A full custom JSON profile (DNS, routing, balancers...) is slow to start and unsafe to
        // run in parallel; its outbounds alone are enough to test the server.
        val content = if (config.configType == EConfigType.CUSTOM) slim(result.content) ?: result.content else result.content
        val urls = listOf(SettingsManager.getDelayTestUrl(), SettingsManager.getDelayTestUrl(second = true)).distinct()
        for (url in urls) {
            val delay = CoreNativeManager.measureOutboundDelay(content, url)
            if (delay > 0) return delay
        }
        return -1L
    }

    /** Custom profiles that could not be slimmed still have to run one at a time. */
    fun limiterType(guid: String): EConfigType {
        val config = MmkvManager.decodeServerConfig(guid) ?: return EConfigType.VLESS
        if (config.configType != EConfigType.CUSTOM) return config.configType
        val raw = MmkvManager.decodeServerRaw(guid) ?: return EConfigType.CUSTOM
        return if (slim(raw) != null) EConfigType.VLESS else EConfigType.CUSTOM
    }

    /** Only the outbounds of a custom profile, the main proxy first (it carries the test request). */
    private fun slim(raw: String): String? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        val all = root.getAsJsonArray("outbounds")?.mapNotNull { it.takeIf { o -> o.isJsonObject }?.asJsonObject }
            ?: return null
        val proxies = all.filter { o ->
            o.get("protocol")?.asString?.lowercase() !in setOf("freedom", "blackhole", "dns", "loopback")
        }
        val main = proxies.firstOrNull { it.get("tag")?.asString == "proxy" } ?: proxies.firstOrNull() ?: return null
        val ordered = JsonArray().apply {
            add(main)
            all.filter { it !== main }.forEach { add(it) }
        }
        JsonObject().apply {
            add("log", JsonObject().apply { addProperty("loglevel", "none") })
            add("outbounds", ordered)
        }.toString()
    }.getOrNull()
}
