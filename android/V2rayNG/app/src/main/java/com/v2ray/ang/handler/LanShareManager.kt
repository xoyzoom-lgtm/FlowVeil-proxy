package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.net.LanShare
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * "Share the connection": the local proxy listens on the home network with a login and a password made by the app.
 * Turning it on never touches a login the user typed himself; turning it off removes only what was made here.
 */
object LanShareManager {
    private const val PREF_GENERATED = "pref_lan_share_generated"

    data class State(val enabled: Boolean, val address: String?, val port: Int, val user: String?, val password: String?)

    fun state(): State {
        val enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)
        return State(enabled, localAddress(), SettingsManager.getSocksPort(), SettingsManager.getSocksUsername(), SettingsManager.getSocksPassword())
    }

    /** Sharing on, with a login and a password (the user's own ones are kept). The connection must be restarted afterwards. */
    fun enable() {
        if (SettingsManager.getSocksUsername() == null || SettingsManager.getSocksPassword() == null) {
            val random = SecureRandom()
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, LanShare.USER)
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, LanShare.newPassword { random.nextInt(it) })
            MmkvManager.encodeSettings(PREF_GENERATED, true)
        }
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, true)
    }

    fun disable() {
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, false)
        if (MmkvManager.decodeSettingsBool(PREF_GENERATED, false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, "")
            MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, "")
            MmkvManager.encodeSettings(PREF_GENERATED, false)
        }
    }

    /** The home-network address of this phone, or null when it is not on one. */
    fun localAddress(): String? = runCatching {
        val all = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { nic ->
            nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { LanShare.Candidate(nic.name, it.hostAddress.orEmpty()) }
        }
        LanShare.pickAddress(all)
    }.getOrNull()
}
