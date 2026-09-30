package com.v2ray.ang.handler

import android.os.Build
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.GitHubRelease
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.net.ReleaseAsset
import com.v2ray.ang.net.ReleaseInfo
import com.v2ray.ang.net.ReleaseNotes
import com.v2ray.ang.net.UpdateCandidate
import com.v2ray.ang.net.UpdateLogic
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Asks GitHub for the FlowVeil releases. The choice of the release itself is the pure [UpdateLogic]; this is only the network side. */
object UpdateCheckerManager {
    sealed interface Fetched {
        data class Ok(val json: String, val etag: String?) : Fetched
        data object NotModified : Fetched
        data object Failed : Fetched
    }

    /**
     * Fetches the latest releases. With an [etag] from an earlier answer GitHub replies "304 Not Modified" (which does not
     * count against the rate limit). A cold mobile connection often misses the first request, so every route gets a real
     * timeout: through the running tunnel first (GitHub may be unreachable without it), then direct, then direct with DoH.
     */
    fun fetchReleases(etag: String? = null): Fetched {
        // Newest build by number, not GitHub's "latest" flag (parallel builds can mark an older one).
        val url = AppConfig.APP_API_URL + "?per_page=10"
        val headers = etag?.takeIf { it.isNotBlank() }?.let { "{\"If-None-Match\":${JsonUtil.toJson(it)}}" }
        val viaProxy = UrlContentRequest(
            url = url, timeout = 15000, userAgent = UPDATER_UA, requestHeaders = headers,
            httpPort = SettingsManager.getHttpPort(),
            proxyUsername = SettingsManager.getSocksUsername(),
            proxyPassword = SettingsManager.getSocksPassword(),
        )
        val direct = UrlContentRequest(url = url, timeout = 15000, userAgent = UPDATER_UA, requestHeaders = headers)
        val directSecureDns = direct.copy(secureDns = true)
        // The tunnel runs in another process; its "connected since" mark is shared through MMKV.
        val vpnOn = MmkvManager.decodeSettingsLong(AppConfig.CACHE_CONNECTED_SINCE, 0L) > 0L
        val attempts = if (vpnOn) listOf(viaProxy, direct, directSecureDns) else listOf(direct, directSecureDns, direct)
        for (request in attempts) {
            try {
                val (text, responseHeaders) = HttpUtil.getUrlContentWithHeaders(request)
                if (text.isNotEmpty()) return Fetched.Ok(text, responseHeaders["etag"])
            } catch (e: IOException) {
                if (e.message?.contains("status code 304") == true) return Fetched.NotModified
            } catch (_: Exception) {
            }
        }
        return Fetched.Failed
    }

    fun parse(json: String): List<ReleaseInfo> =
        (JsonUtil.fromJsonSafe(json, Array<GitHubRelease>::class.java) ?: emptyArray()).map { r ->
            ReleaseInfo(r.tagName, r.draft, r.prerelease, r.body, r.assets.map { ReleaseAsset(it.name, it.browserDownloadUrl) })
        }

    fun candidateFrom(json: String): UpdateCandidate? =
        UpdateLogic.pick(parse(json), BuildConfig.HUPP_BUILD, UpdateLogic.androidAssets(Build.SUPPORTED_ABIS.firstOrNull()))

    /** The manual check ("Check for updates"): always fresh, and it refreshes what the banner shows. */
    suspend fun checkForUpdate(includePreRelease: Boolean = false): CheckUpdateResult = withContext(Dispatchers.IO) {
        val fetched = fetchReleases()
        if (fetched !is Fetched.Ok) throw IllegalStateException("Failed to get response")
        val candidate = candidateFrom(fetched.json)
        UpdateNotifier.remember(candidate, fetched.etag)
        LogUtil.i(AppConfig.TAG, "Latest build: ${candidate?.build} (current: ${BuildConfig.HUPP_BUILD})")
        if (candidate == null) return@withContext CheckUpdateResult(hasUpdate = false)
        CheckUpdateResult(
            hasUpdate = true,
            latestVersion = candidate.tag,
            releaseNotes = ReleaseNotes.plain(candidate.notes),
            downloadUrl = candidate.assetUrl,
            build = candidate.build,
            assetName = candidate.assetName,
            sumsUrl = candidate.sumsUrl,
        )
    }

    private const val UPDATER_UA = "FlowVeil-updater"
}
