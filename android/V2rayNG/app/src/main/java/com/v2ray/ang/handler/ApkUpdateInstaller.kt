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

    /** Returns the downloaded file; [onProgress] gets 0..100 (or -1 while the size is unknown). */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val target = File(dir, FILE_NAME)
        val partial = File(dir, "$FILE_NAME.part")
        var current = URL(url)
        var redirects = 0
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "FlowVeil-updater")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399 && redirects++ < 5) {
                    current = URL(current, conn.getHeaderField("Location"))
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
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
                    }
                }
                target.delete()
                if (!partial.renameTo(target)) throw IllegalStateException("Cannot save update")
                return@withContext target
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        target
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
