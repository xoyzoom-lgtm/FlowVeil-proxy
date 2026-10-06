package com.v2ray.ang.handler

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.net.Sha256Sums
import com.v2ray.ang.net.UpdateManifest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Downloads a FlowVeil APK inside the app and hands it to the system installer. */
object ApkUpdateInstaller {
    private const val FILE_NAME = "FlowVeil-update.apk"

    /**
     * Returns the downloaded file; [onProgress] gets 0..100 (or -1 while the size is unknown).
     * GitHub's file host is often unreachable without help, so every route is tried like for the update check:
     * through the running tunnel (when it is on), directly, then directly with DNS-over-HTTPS. The error says why it failed.
     */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, FILE_NAME)
        val partial = File(dir, "$FILE_NAME.part")
        val tunnelOn = MmkvManager.decodeSettingsLong(AppConfig.CACHE_CONNECTED_SINCE, 0L) > 0L
        val routes = buildList {
            if (tunnelOn) add(Route(viaProxy = true, secureDns = false))
            add(Route(viaProxy = false, secureDns = false))
            add(Route(viaProxy = false, secureDns = true))
            if (!tunnelOn) add(Route(viaProxy = false, secureDns = false))
        }
        var lastError: Exception? = null
        for (route in routes) {
            try {
                partial.delete()
                fetchTo(url, route, partial, onProgress)
                target.delete()
                if (!partial.renameTo(target)) throw IllegalStateException("Cannot save update")
                return@withContext target
            } catch (e: kotlinx.coroutines.CancellationException) {
                partial.delete()
                throw e
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Update download via $route failed", e)
                lastError = e
            }
        }
        partial.delete()
        throw DownloadFailed(lastError?.message ?: lastError?.javaClass?.simpleName ?: "unknown")
    }

    class DownloadFailed(val reason: String) : Exception(reason)

    private data class Route(val viaProxy: Boolean, val secureDns: Boolean)

    private suspend fun fetchTo(url: String, route: Route, partial: File, onProgress: (Int) -> Unit) {
        val client = HttpUtil.buildOkHttpClient(
            timeout = 30_000,
            httpPort = if (route.viaProxy) SettingsManager.getHttpPort() else 0,
            proxyUsername = if (route.viaProxy) SettingsManager.getSocksUsername() else null,
            proxyPassword = if (route.viaProxy) SettingsManager.getSocksPassword() else null,
            followRedirects = true,
            secureDns = route.secureDns,
        )
        val request = okhttp3.Request.Builder().url(url).header("User-Agent", "FlowVeil-updater").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("empty answer")
            val total = body.contentLength()
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -2
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != last) {
                            last = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                    if (total > 0 && done != total) throw IllegalStateException("cut off at $done of $total")
                }
            }
        }
    }

    /**
     * Checks the downloaded file against the release's `SHA256SUMS.txt`: true = matches, false = differs (do not install),
     * null = no sums or the file is not listed (do not block). A failed download of the sums file does not block either.
     */
    suspend fun verify(apk: File, assetName: String?, sumsUrl: String?): Boolean? = withContext(Dispatchers.IO) {
        if (assetName == null || sumsUrl == null) return@withContext null
        val text = runCatching { HttpUtil.getUrlContent(UrlContentRequest(url = sumsUrl, timeout = 15000)) }.getOrNull()
            ?: return@withContext null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        Sha256Sums.verify(Sha256Sums.parse(text), assetName, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Small release file (manifest or signature) as bytes; null when it is absent or cannot be fetched. */
    private fun fetchSmall(url: String?, limit: Int): ByteArray? {
        if (url == null) return null
        return runCatching {
            var current = URL(url)
            var redirects = 0
            while (true) {
                val conn = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "FlowVeil-updater")
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399 && redirects++ < 5) {
                        current = URL(current, conn.getHeaderField("Location"))
                        continue
                    }
                    if (code !in 200..299) return null
                    val bytes = conn.inputStream.use { it.readNBytesCompat(limit + 1) }
                    return if (bytes.size > limit) null else bytes
                } finally {
                    conn.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }.getOrNull()
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (out.size() < max) {
            val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The downloaded file against the release manifest (signature, name, size, SHA-256, build). */
    suspend fun checkManifest(apk: File, result: com.v2ray.ang.dto.CheckUpdateResult): UpdateManifest.Result = withContext(Dispatchers.IO) {
        val manifest = fetchSmall(result.manifestUrl, 64 * 1024)
        val signature = if (manifest != null) fetchSmall(result.signatureUrl, 1024) else null
        UpdateManifest.check(
            manifest = manifest,
            signature = signature,
            publicKeys = UpdateKeys.publicKeys(),
            assetName = result.assetName.orEmpty(),
            size = apk.length(),
            sha256 = sha256(apk),
            tagBuild = result.build.takeIf { it > 0 },
            currentBuild = com.v2ray.ang.BuildConfig.HUPP_BUILD,
        )
    }

    /**
     * The downloaded APK is signed with the same certificate as the installed app: true / false; null when Android cannot tell.
     * Android itself refuses an update with another certificate; checking first gives a clear message instead of a system error.
     */
    @Suppress("DEPRECATION")
    fun sameSigner(context: Context, apk: File): Boolean? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            val archive = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo ?: return@runCatching null
            val installed = pm.getPackageInfo(context.packageName, flags).signingInfo ?: return@runCatching null
            val a = (if (archive.hasMultipleSigners()) archive.apkContentsSigners else archive.signingCertificateHistory).map { it.toCharsString() }.toSet()
            val b = (if (installed.hasMultipleSigners()) installed.apkContentsSigners else installed.signingCertificateHistory).map { it.toCharsString() }.toSet()
            a.intersect(b).isNotEmpty()
        } else {
            val flags = android.content.pm.PackageManager.GET_SIGNATURES
            val a = pm.getPackageArchiveInfo(apk.path, flags)?.signatures?.map { it.toCharsString() }?.toSet() ?: return@runCatching null
            val b = pm.getPackageInfo(context.packageName, flags).signatures?.map { it.toCharsString() }?.toSet() ?: return@runCatching null
            a == b
        }
    }.getOrNull()

    /** True when Android still needs the user to allow installs from FlowVeil (Android 8+). */
    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.cache", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to start APK install", e)
            throw e
        }
    }
}
