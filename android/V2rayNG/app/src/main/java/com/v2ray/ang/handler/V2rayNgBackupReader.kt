package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.ZipUtil
import java.io.File

/**
 * Reads the subscription links out of a v2rayNG backup file (a zip of its MMKV storages) without
 * restoring anything: our own settings and servers stay as they are. Only the "SUB" storage is
 * opened, from a temporary folder that is deleted at once.
 */
object V2rayNgBackupReader {
    fun subscriptionUrls(cacheDir: File, zip: File): List<String> {
        val dir = File(cacheDir, "migrate_${System.nanoTime()}")
        return try {
            if (!ZipUtil.unzipToFolder(zip, dir.absolutePath)) return emptyList()
            val root = dir.walkTopDown().firstOrNull { it.isFile && it.name == "SUB" }?.parentFile ?: return emptyList()
            val storage = MMKV.mmkvWithID("SUB", MMKV.SINGLE_PROCESS_MODE, null, root.absolutePath)
            val urls = try {
                storage.allKeys().orEmpty().mapNotNull { key ->
                    storage.decodeString(key)?.let { JsonUtil.fromJsonSafe(it, SubscriptionItem::class.java) }?.url?.trim()
                }
            } finally {
                runCatching { storage.close() }
            }
            urls.filter { it.startsWith("http", ignoreCase = true) }.distinct()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cannot read the v2rayNG backup", e)
            emptyList()
        } finally {
            dir.deleteRecursively()
        }
    }
}
