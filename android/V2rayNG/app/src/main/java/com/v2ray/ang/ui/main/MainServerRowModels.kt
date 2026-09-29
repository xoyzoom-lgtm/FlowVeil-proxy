package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager

internal data class ServerRowUiModel(
    val guid: String,
    val profile: ProfileItem,
    val remarks: String,
    val statistics: String,
    val typeDescription: String,
    val testDelayMillis: Long,
    val subscriptionBadge: String,
    val isFavorite: Boolean = false,
)

internal data class ServerGroupUiState(
    val servers: List<ServersCache> = emptyList(),
    val rows: List<ServerRowUiModel> = emptyList(),
)

internal fun buildServerRowUiModel(
    server: ServersCache,
    subscriptionRemarks: String,
    isFavorite: Boolean = false,
): ServerRowUiModel {
    val profile = server.profile
    return ServerRowUiModel(
        guid = server.guid,
        profile = profile,
        remarks = profile.remarks,
        statistics = profile.description.nullIfBlank()
            ?: AngConfigManager.generateDescription(profile),
        typeDescription = if (profile.configType == EConfigType.CUSTOM) {
            describeCustomConfig(MmkvManager.decodeServerRaw(server.guid)) ?: profile.configType.name
        } else {
            serverProtocolDescription(profile)
        },
        testDelayMillis = server.testDelayMillis,
        subscriptionBadge = subscriptionRemarks.firstOrNull()?.toString().orEmpty(),
        isFavorite = isFavorite,
    )
}

private fun serverProtocolDescription(profile: ProfileItem): String {
    if (profile.configType.isComplexType()) return profile.configType.name
    // Short badges people know: HY2 and TUIC ride on QUIC, so there is no separate transport.
    val parts = mutableListOf(
        when (profile.configType) {
            EConfigType.HYSTERIA2 -> "HY2"
            else -> profile.configType.name
        }
    )
    val quicBased = profile.configType == EConfigType.HYSTERIA2 || profile.configType == EConfigType.TUIC
    profile.network?.let { network ->
        if (!quicBased && network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) {
            parts.add(network)
        }
    }
    profile.security?.let { security ->
        if (security.isNotBlank()) {
            parts.add(
                if (profile.insecure == true && security.equals("tls", ignoreCase = true)) {
                    "$security insecure"
                } else if (security.equals("reality", ignoreCase = true)) {
                    "REALITY"
                } else {
                    security
                }
            )
        }
    }
    return parts.joinToString(" / ")
}
