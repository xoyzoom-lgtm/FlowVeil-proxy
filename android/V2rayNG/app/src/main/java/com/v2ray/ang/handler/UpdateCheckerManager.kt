package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        // Newest build by number, not GitHub's "latest" flag (parallel builds can mark an older one).
        val url = AppConfig.APP_API_URL + "?per_page=10"

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val viaProxy = UrlContentRequest(
            url = url,
            timeout = 15000,
            httpPort = SettingsManager.getHttpPort(),
            proxyUsername = proxyUsername,
            proxyPassword = proxyPassword
        )
        val direct = UrlContentRequest(url = url, timeout = 15000)
        val directSecureDns = UrlContentRequest(url = url, timeout = 15000, secureDns = true)
        // A cold mobile connection often misses the first request, so every route gets a real
        // timeout and there are fallbacks: through the running VPN first, then direct, then
        // direct with DNS-over-HTTPS.
        // The VPN runs in another process; its "connected since" mark is shared through MMKV.
        val vpnOn = MmkvManager.decodeSettingsLong(AppConfig.CACHE_CONNECTED_SINCE, 0L) > 0L
        val attempts = if (vpnOn) listOf(viaProxy, direct, directSecureDns) else listOf(direct, directSecureDns, direct)
        var response: String? = null
        for (request in attempts) {
            response = runCatching { HttpUtil.getUrlContent(request) }.getOrNull()
            if (!response.isNullOrEmpty()) break
        }
        if (response.isNullOrEmpty()) throw IllegalStateException("Failed to get response")

        val latestRelease = JsonUtil.fromJsonSafe(response, Array<GitHubRelease>::class.java)
            ?.filter { !it.prerelease && it.assets.isNotEmpty() }
            ?.maxByOrNull { release -> release.tagName.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        // FlowVeil releases are tagged "build-N"; the running build number is baked in by CI.
        val latestBuild = latestRelease.tagName.filter { it.isDigit() }.toIntOrNull() ?: 0
        LogUtil.i(AppConfig.TAG, "Latest build: $latestBuild (current: ${BuildConfig.HUPP_BUILD})")

        return@withContext if (latestBuild > BuildConfig.HUPP_BUILD) {
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestRelease.tagName,
                releaseNotes = latestRelease.body,
                downloadUrl = getDownloadUrl(latestRelease, Build.SUPPORTED_ABIS[0]),
                isPreRelease = latestRelease.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    private fun getDownloadUrl(release: GitHubRelease, abi: String): String {
        // Small arm64 build for modern phones, universal build for everything else.
        val wanted = if (abi.contains("arm64", ignoreCase = true)) "FlowVeil-android-arm64.apk" else "FlowVeil-android.apk"
        val asset = release.assets.firstOrNull { it.name == wanted }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk") }
        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}
