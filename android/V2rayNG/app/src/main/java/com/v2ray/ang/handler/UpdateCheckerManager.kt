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

        var response = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000
            )
        )
        if (response.isNullOrEmpty()) {
            val httpPort = SettingsManager.getHttpPort()
            response = HttpUtil.getUrlContent(
                UrlContentRequest(
                    url = url,
                    timeout = 5000,
                    httpPort = httpPort,
                    proxyUsername = proxyUsername,
                    proxyPassword = proxyPassword
                )
            )
                ?: throw IllegalStateException("Failed to get response")
        }

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
