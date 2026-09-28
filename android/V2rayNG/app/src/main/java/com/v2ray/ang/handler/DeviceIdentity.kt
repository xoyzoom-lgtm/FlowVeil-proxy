package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import java.security.SecureRandom

/**
 * Per-install device identifier sent to subscription providers that enforce device limits
 * (the `x-hwid` convention used by Remnawave-style panels). It is random, not derived from
 * hardware, and stays stable for the lifetime of the app's data.
 */
object DeviceIdentity {

    fun hwid(): String {
        MmkvManager.decodeSettingsString(AppConfig.CACHE_DEVICE_HWID)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val generated = bytes.joinToString("") { "%02X".format(it) }
        MmkvManager.encodeSettings(AppConfig.CACHE_DEVICE_HWID, generated)
        return generated
    }

    /** Headers for subscription requests, or none when the user disabled sending the device ID. */
    fun subscriptionHeaders(): Map<String, String> {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_SEND_HWID, true)) return emptyMap()
        return mapOf(
            "x-hwid" to hwid(),
            "x-device-os" to "Android",
            "x-ver-os" to Build.VERSION.RELEASE.orEmpty(),
            "x-device-model" to Build.MODEL.orEmpty(),
        )
    }
}
