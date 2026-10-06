package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.net.PortPick
import com.v2ray.ang.util.LogUtil
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Before the core starts: if the local proxy port is already taken (another VPN or proxy app on the same phone listens on 10808),
 * the next free one is chosen and remembered, instead of failing with "address already in use".
 */
object PortGuard {
    private fun free(port: Int): Boolean = try {
        ServerSocket(port, 0, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: Exception) {
        false
    }

    fun ensureFree() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_SOCKS_PORT, false)) return
        val port = SettingsManager.getSocksPort()
        val httpOffset = SettingsManager.getHttpPort() - port
        val offsets = if (httpOffset == 0) listOf(0) else listOf(0, httpOffset)
        val picked = PortPick.pick(port, offsets, ::free) ?: return
        if (picked != port) {
            LogUtil.w(AppConfig.TAG, "Port $port is busy (another app?), using $picked")
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, picked.toString())
            MmkvManager.encodeSettings(AppConfig.CACHE_PORT_MOVED, picked.toString())
        }
    }

}
