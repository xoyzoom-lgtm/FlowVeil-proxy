package com.v2ray.ang.handler

/** Servers the user starred; they are listed first in their subscription. */
object FavoriteServers {
    private const val KEY = "favorite_servers"

    fun all(): Set<String> =
        MmkvManager.decodeSettingsString(KEY).orEmpty().split(',').filter { it.isNotBlank() }.toSet()

    fun isFavorite(guid: String): Boolean = guid in all()

    fun toggle(guid: String): Boolean {
        val current = all().toMutableSet()
        val nowFavorite = if (!current.remove(guid)) current.add(guid) else false
        MmkvManager.encodeSettings(KEY, current.joinToString(","))
        return nowFavorite
    }
}
