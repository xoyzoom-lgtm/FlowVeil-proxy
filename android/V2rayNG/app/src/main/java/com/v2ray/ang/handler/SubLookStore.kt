package com.v2ray.ang.handler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.net.SubLook
import com.v2ray.ang.net.TelegramAvatar
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Per-subscription look (avatar picture or emoji, card colour, background picture), kept only on this phone:
 * the choice in settings storage, the pictures in the app's private folder. Nothing is uploaded anywhere.
 */
object SubLookStore {
    private const val AVATAR_SIZE = 256
    private const val BACKGROUND_SIDE = 1000
    private const val MAX_DOWNLOAD = 3L * 1024 * 1024

    private val state = mutableStateMapOf<String, SubLook>()

    private fun key(id: String) = "sub_look_$id"

    fun get(id: String): SubLook =
        state[id] ?: SubLook.decode(MmkvManager.decodeSettingsString(key(id)))

    fun set(id: String, look: SubLook) {
        MmkvManager.encodeSettings(key(id), if (look.isDefault) "" else look.encode())
        state[id] = look
    }

    private fun dir(context: Context) = File(context.filesDir, "sublook").apply { mkdirs() }

    fun avatarFile(context: Context, id: String) = File(dir(context), "${safe(id)}_avatar.jpg")
    fun backgroundFile(context: Context, id: String) = File(dir(context), "${safe(id)}_bg.jpg")

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" }

    /** Back to the automatic look; the pictures are deleted. */
    fun reset(context: Context, id: String) {
        avatarFile(context, id).delete()
        backgroundFile(context, id).delete()
        set(id, SubLook())
    }

    /** The picture chosen in the gallery as avatar (square) or as card background. False when it cannot be read. */
    suspend fun setImage(context: Context, id: String, uri: Uri, background: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = decode({ context.contentResolver.openInputStream(uri) }, if (background) BACKGROUND_SIDE else AVATAR_SIZE, square = !background)
                ?: return@runCatching false
            apply(context, id, bmp, background)
        }.getOrElse {
            LogUtil.e(AppConfig.TAG, "Cannot use the chosen picture", it)
            false
        }
    }

    /** The profile picture of a public Telegram bot or channel, taken once and stored. False when there is none or Telegram cannot be reached. */
    suspend fun fetchTelegram(context: Context, id: String, input: String): Boolean = withContext(Dispatchers.IO) {
        val name = TelegramAvatar.username(input) ?: return@withContext false
        runCatching {
            val html = fetchPage("https://t.me/$name") ?: return@runCatching false
            val image = TelegramAvatar.ogImage(html) ?: return@runCatching false
            val tmp = File(context.cacheDir, "tg_avatar_${System.nanoTime()}.bin")
            try {
                val ok = download(image, tmp) && tmp.length() in 1..MAX_DOWNLOAD
                if (!ok) return@runCatching false
                val bmp = decode({ tmp.inputStream() }, AVATAR_SIZE, square = true) ?: return@runCatching false
                apply(context, id, bmp, background = false)
            } finally {
                tmp.delete()
            }
        }.getOrElse {
            LogUtil.e(AppConfig.TAG, "Telegram picture failed", it)
            false
        }
    }

    private fun request(url: String, viaProxy: Boolean) = UrlContentRequest(
        url = url,
        timeout = 12000,
        httpPort = if (viaProxy) SettingsManager.getHttpPort() else 0,
        proxyUsername = if (viaProxy) SettingsManager.getSocksUsername() else null,
        proxyPassword = if (viaProxy) SettingsManager.getSocksPassword() else null,
    )

    private fun fetchPage(url: String): String? =
        HttpUtil.getUrlContent(request(url, false)).takeIf { !it.isNullOrEmpty() }
            ?: HttpUtil.getUrlContent(request(url, true)).takeIf { !it.isNullOrEmpty() }

    private fun download(url: String, target: File): Boolean =
        HttpUtil.downloadToFile(request(url, false), target) || HttpUtil.downloadToFile(request(url, true), target)

    private fun apply(context: Context, id: String, bmp: Bitmap, background: Boolean): Boolean {
        val file = if (background) backgroundFile(context, id) else avatarFile(context, id)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val now = get(id)
        set(
            id,
            if (background) now.copy(background = true, rev = System.currentTimeMillis())
            else now.copy(avatar = SubLook.Avatar.PHOTO, emoji = "", rev = System.currentTimeMillis())
        )
        return true
    }

    private fun decode(open: () -> InputStream?, target: Int, square: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > 120_000_000L) return null
        var sample = 1
        while (max(w, h) / sample > target * 2) sample *= 2
        val raw = open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        return if (square) {
            val side = min(raw.width, raw.height)
            val cropped = Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(cropped, target, target, true)
        } else {
            val scale = target.toFloat() / max(raw.width, raw.height)
            if (scale >= 1f) raw else Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
        }
    }
}
