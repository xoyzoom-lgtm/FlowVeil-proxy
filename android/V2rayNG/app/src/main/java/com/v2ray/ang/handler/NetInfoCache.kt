package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.net.FailReason
import com.v2ray.ang.net.IpParser
import com.v2ray.ang.net.NetType

/** Bypass status shown in the UI. */
enum class BypassState { OFF, IDLE, SEARCHING, CHECKING, OK, FAIL, NO_NETWORK }

/**
 * What the core process knows about the network, for the UI process (MULTI_PROCESS MMKV).
 * IP addresses are kept only for [IP_TTL_MS] and are dropped when the connection stops; they are
 * never written to logs and never sent anywhere except the IP-lookup services that were asked.
 */
object NetInfoCache {
    const val IP_TTL_MS = 15 * 60_000L

    /** [Info.returnTo] value when the way back is "pick the best server again" rather than a named one. */
    const val RETURN_BEST = "@best"

    data class Info(
        val type: NetType,
        val others: List<NetType>,
        val realIp: String?,
        val realIp6: String?,
        val exitIp: String?,
        val bypass: BypassState,
        val reason: FailReason?,
        val returnTo: String?,
        val updatedAt: Long,
    )

    private const val KEY_UPDATED = "cache_net_updated_at"

    fun read(now: Long = System.currentTimeMillis()): Info {
        val (state, reason) = readBypass()
        return Info(
            type = NetType.fromId(MmkvManager.decodeSettingsString(AppConfig.CACHE_NET_TYPE)),
            others = MmkvManager.decodeSettingsString(AppConfig.CACHE_NET_OTHERS).orEmpty()
                .split(',').filter { it.isNotBlank() }.map { NetType.fromId(it) }.filter { it != NetType.NONE },
            realIp = readIp(AppConfig.CACHE_REAL_IP, now),
            realIp6 = readIp(AppConfig.CACHE_REAL_IP6, now),
            exitIp = readIp(AppConfig.CACHE_EXIT_IP, now),
            bypass = state,
            reason = reason,
            returnTo = MmkvManager.decodeSettingsString(AppConfig.CACHE_BYPASS_RETURN_TO)?.takeIf { it.isNotBlank() },
            updatedAt = MmkvManager.decodeSettingsString(KEY_UPDATED)?.toLongOrNull() ?: 0L,
        )
    }

    fun writeType(type: NetType, others: List<NetType>) {
        MmkvManager.encodeSettings(AppConfig.CACHE_NET_TYPE, type.id)
        MmkvManager.encodeSettings(AppConfig.CACHE_NET_OTHERS, others.joinToString(",") { it.id })
        touch()
    }

    fun writeRealIp(ip: String?, ip6: String? = null) {
        writeIp(AppConfig.CACHE_REAL_IP, ip)
        writeIp(AppConfig.CACHE_REAL_IP6, ip6)
        touch()
    }

    fun readRealIp(now: Long = System.currentTimeMillis()): String? = readIp(AppConfig.CACHE_REAL_IP, now)

    fun writeExitIp(ip: String?) {
        writeIp(AppConfig.CACHE_EXIT_IP, ip)
        touch()
    }

    fun writeBypass(state: BypassState, reason: FailReason? = null, returnTo: String? = null) {
        MmkvManager.encodeSettings(AppConfig.CACHE_BYPASS_STATE, "${state.name}|${reason?.name.orEmpty()}")
        MmkvManager.encodeSettings(AppConfig.CACHE_BYPASS_RETURN_TO, returnTo.orEmpty())
        touch()
    }

    /** The connection stopped: nothing here describes a running tunnel any more. */
    fun clearOnStop() {
        writeIp(AppConfig.CACHE_REAL_IP, null)
        writeIp(AppConfig.CACHE_REAL_IP6, null)
        writeIp(AppConfig.CACHE_EXIT_IP, null)
        writeBypass(if (WhitelistBypass.isEnabled()) BypassState.IDLE else BypassState.OFF)
        MmkvManager.encodeSettings(AppConfig.CACHE_NET_TYPE, NetType.NONE.id)
        MmkvManager.encodeSettings(AppConfig.CACHE_NET_OTHERS, "")
    }

    private fun readBypass(): Pair<BypassState, FailReason?> {
        val parts = MmkvManager.decodeSettingsString(AppConfig.CACHE_BYPASS_STATE).orEmpty().split('|')
        val state = BypassState.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: BypassState.OFF
        val reason = FailReason.entries.firstOrNull { it.name == parts.getOrNull(1) }
        return state to reason
    }

    private fun writeIp(key: String, ip: String?) {
        MmkvManager.encodeSettings(key, if (ip != null && IpParser.isIp(ip)) "$ip|${System.currentTimeMillis()}" else "")
    }

    private fun readIp(key: String, now: Long): String? {
        val raw = MmkvManager.decodeSettingsString(key).orEmpty()
        val idx = raw.lastIndexOf('|')
        if (idx <= 0) return null
        val ts = raw.substring(idx + 1).toLongOrNull() ?: return null
        if (now - ts !in 0 until IP_TTL_MS) return null
        return raw.substring(0, idx).takeIf { IpParser.isIp(it) }
    }

    private fun touch() {
        MmkvManager.encodeSettings(KEY_UPDATED, System.currentTimeMillis().toString())
    }
}
