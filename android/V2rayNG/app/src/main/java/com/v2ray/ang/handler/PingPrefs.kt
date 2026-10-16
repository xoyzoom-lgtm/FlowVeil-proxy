package com.v2ray.ang.handler

import androidx.compose.runtime.mutableStateOf
import com.v2ray.ang.AppConfig
import com.v2ray.ang.net.PingChoice

/**
 * The ping settings of the "Ping" sheet (see [PingChoice]), stored in the shared settings so the test service reads the
 * same values. The test address is the existing [AppConfig.PREF_DELAY_TEST_URL]. [showIcon] is also kept as Compose
 * state so the server list redraws the moment it is changed.
 */
object PingPrefs {
    const val PREF_METHOD = "pref_ping_method"
    const val PREF_TIMEOUT = "pref_ping_timeout_seconds"
    const val PREF_SHOW_ICON = "pref_ping_show_icon"
    const val PREF_BEST_OF_TWO = "pref_ping_best_of_two"

    fun method(): PingChoice.Method = PingChoice.method(MmkvManager.decodeSettingsString(PREF_METHOD))

    fun setMethod(m: PingChoice.Method) = MmkvManager.encodeSettings(PREF_METHOD, m.id)

    /** "Through the server" asks twice and the faster answer counts (the second skips the DNS lookup). */
    fun bestOfTwo(): Boolean = MmkvManager.decodeSettingsBool(PREF_BEST_OF_TWO, false)

    fun setBestOfTwo(on: Boolean) { MmkvManager.encodeSettings(PREF_BEST_OF_TWO, on) }

    fun timeoutSeconds(): Int = PingChoice.timeoutSeconds(MmkvManager.decodeSettingsString(PREF_TIMEOUT))

    fun setTimeoutSeconds(s: Int) = MmkvManager.encodeSettings(PREF_TIMEOUT, s.toString())

    fun url(): String = SettingsManager.getDelayTestUrl()

    /** False when the text is not a usable address (nothing is stored then). */
    fun setUrl(text: String): Boolean {
        val clean = PingChoice.cleanUrl(text) ?: return false
        MmkvManager.encodeSettings(AppConfig.PREF_DELAY_TEST_URL, clean)
        return true
    }

    val showIconState = mutableStateOf(runCatching { MmkvManager.decodeSettingsBool(PREF_SHOW_ICON, false) }.getOrDefault(false))

    fun setShowIcon(on: Boolean) {
        MmkvManager.encodeSettings(PREF_SHOW_ICON, on)
        showIconState.value = on
    }
}
