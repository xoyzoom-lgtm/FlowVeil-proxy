package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.concatUrl
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateCheckerManager {
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val url = AppConfig.APP_API_URL.concatUrl("latest")

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

        val latestRelease = JsonUtil.fromJsonSafe(response, GitHubRelease::class.java)
        if (latestRelease == null) {
            return@withContext CheckUpdateResult(hasUpdate = false)
        }

        // Hupp releases are tagged "build-N"; the running build number is baked in by CI.
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
        val wanted = if (abi.contains("arm64", ignoreCase = true)) "Hupp-android-arm64.apk" else "Hupp-android.apk"
        val asset = release.assets.firstOrNull { it.name == wanted }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk") }
        return asset?.browserDownloadUrl
            ?: throw IllegalStateException("No compatible APK found")
    }
}
