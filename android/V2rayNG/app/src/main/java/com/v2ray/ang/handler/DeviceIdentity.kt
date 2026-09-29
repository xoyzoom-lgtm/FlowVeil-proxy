package com.v2ray.ang.handler

import android.annotation.SuppressLint
import android.os.Build
import android.provider.Settings
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device identifier sent to subscription providers that enforce device limits
 * (the `x-hwid` convention used by Remnawave-style panels). It is a one-way hash of this app's
 * ANDROID_ID, so reinstalling FlowVeil does not look like a new device to the provider;
 * the value cannot be turned back into the ANDROID_ID and differs from what other apps see.
 */
object DeviceIdentity {

    fun hwid(): String {
        MmkvManager.decodeSettingsString(AppConfig.CACHE_DEVICE_HWID)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val generated = fromAndroidId() ?: ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02X".format(it) }
        MmkvManager.encodeSettings(AppConfig.CACHE_DEVICE_HWID, generated)
        return generated
    }

    @SuppressLint("HardwareIds")
    private fun fromAndroidId(): String? = runCatching {
        val androidId = Settings.Secure.getString(AngApplication.application.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } ?: return null
        MessageDigest.getInstance("SHA-256").digest("flowveil:$androidId".toByteArray())
            .take(8).joinToString("") { "%02X".format(it) }
    }.getOrNull()

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
