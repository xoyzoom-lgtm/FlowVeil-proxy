package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The decision log of the auto bypass ("why this server"): short lines, newest last, kept in MMKV so
 * the settings screen (another process) can show and copy it. Never holds an IP address or a
 * Wi-Fi name: only server names, scores, step results and timings.
 */
object BypassLog {
    private const val KEY = "bypass_decision_log"
    private const val MAX_CHARS = 24_000

    private val lock = Any()

    fun add(message: String) {
        LogUtil.w(AppConfig.TAG, "Bypass: $message")
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + message
        synchronized(lock) {
            var text = MmkvManager.decodeSettingsString(KEY).orEmpty() + line + "\n"
            if (text.length > MAX_CHARS) text = text.substring(text.length - MAX_CHARS).substringAfter('\n')
            MmkvManager.encodeSettings(KEY, text)
        }
    }

    fun read(): String = MmkvManager.decodeSettingsString(KEY).orEmpty()

    fun clear() = MmkvManager.encodeSettings(KEY, "")
}
