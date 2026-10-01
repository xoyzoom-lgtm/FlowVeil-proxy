package com.v2ray.ang.handler

import com.v2ray.ang.net.BadMarks
import com.v2ray.ang.net.BypassSnapshot
import com.v2ray.ang.net.ReturnLogic

/**
 * "Auto bypass of mobile whitelists": settings (read by the UI process and the core process
 * through MULTI_PROCESS MMKV), the persisted switch state and the pure diagnosis.
 * The switching itself lives in [com.v2ray.ang.core.ConnectionWatchdog].
 */
object WhitelistBypass {
    const val PREF_ENABLED = "pref_whitelist_bypass_enabled"
    const val PREF_MODE = "pref_whitelist_bypass_mode"
    const val PREF_SERVERS = "pref_whitelist_bypass_servers"
    const val PREF_AUTO_RETURN = "pref_whitelist_bypass_auto_return"

    // Developer-mode tuning (defaults in [ReturnLogic] and below).
    const val PREF_BAD_MINUTES = "pref_whitelist_bypass_bad_minutes"
    const val PREF_PING_LIMIT = "pref_whitelist_bypass_ping_limit"
    const val PREF_STABLE_SECONDS = "pref_whitelist_bypass_stable_seconds"
    const val DEFAULT_BAD_MINUTES = 10

    /** What to do when no server looks like a bypass server: stay as is ("none") or try the rest ("all"). */
    const val PREF_FALLBACK = "pref_whitelist_bypass_fallback"
    const val FALLBACK_NONE = "none"
    const val FALLBACK_ALL = "all"

    // Developer mode: slower pings are normal under a whitelist, and how long one search may take.
    const val PREF_BYPASS_PING = "pref_whitelist_bypass_ping_bypass"
    const val PREF_SEARCH_SECONDS = "pref_whitelist_bypass_search_seconds"
    const val DEFAULT_BYPASS_PING_MS = 1500L
    const val DEFAULT_SEARCH_SECONDS = 40

    const val MODE_AUTO = "auto"
    const val MODE_MANUAL = "manual"

    // Runtime state, not settings: the snapshot of the server we left, the one we switched to,
    // servers that failed a check, and which server the user picked with the "Best" button.
    private const val STATE_SNAPSHOT = "whitelist_bypass_snapshot"
    private const val STATE_ACTIVE = "whitelist_bypass_active"
    private const val STATE_BAD = "whitelist_bypass_bad"
    private const val STATE_BEST_GUID = "whitelist_bypass_best_guid"
    private const val STATE_SUCCESS = "whitelist_bypass_success"
    private const val STATE_LAST_DIAG = "whitelist_bypass_last_diag"
    private const val SUCCESS_KEEP = 20

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, false)

    fun mode(): String = MmkvManager.decodeSettingsString(PREF_MODE, MODE_AUTO) ?: MODE_AUTO

    fun tryRest(): Boolean = MmkvManager.decodeSettingsString(PREF_FALLBACK, FALLBACK_NONE) == FALLBACK_ALL

    fun bypassPingMs(): Long = MmkvManager.decodeSettingsString(PREF_BYPASS_PING)?.toLongOrNull()?.takeIf { it in 300..10_000 } ?: DEFAULT_BYPASS_PING_MS

    fun searchBudgetMs(): Long = (MmkvManager.decodeSettingsString(PREF_SEARCH_SECONDS)?.toIntOrNull()?.takeIf { it in 10..300 } ?: DEFAULT_SEARCH_SECONDS) * 1000L

    fun autoReturn(): Boolean = MmkvManager.decodeSettingsBool(PREF_AUTO_RETURN, true)

    fun badMinutes(): Int = MmkvManager.decodeSettingsString(PREF_BAD_MINUTES)?.toIntOrNull()?.takeIf { it in 1..600 } ?: DEFAULT_BAD_MINUTES

    fun pingLimitMs(): Long = MmkvManager.decodeSettingsString(PREF_PING_LIMIT)?.toLongOrNull()?.takeIf { it in 50..10_000 } ?: ReturnLogic.DEFAULT_PING_LIMIT_MS

    /** How long Wi-Fi must stay up before the return starts (metro, lift and flapping protection). */
    fun stableSeconds(): Int = MmkvManager.decodeSettingsString(PREF_STABLE_SECONDS)?.toIntOrNull()?.takeIf { it in 1..300 } ?: ReturnLogic.DEFAULT_STABLE_SECONDS

    /** Manually chosen bypass servers; profiles deleted since are dropped (and the list rewritten). */
    fun servers(): List<String> {
        val stored = MmkvManager.decodeSettingsString(PREF_SERVERS).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val alive = stored.filter { MmkvManager.decodeServerConfig(it) != null }
        if (alive.size != stored.size) setServers(alive)
        return alive
    }

    fun setServers(guids: Collection<String>) {
        MmkvManager.encodeSettings(PREF_SERVERS, guids.distinct().joinToString(","))
    }

    // ---- switch state (core process) ----

    /** The server the user was on before the first switch of the current episode (null = no episode). */
    var snapshot: BypassSnapshot?
        get() = BypassSnapshot.decode(MmkvManager.decodeSettingsString(STATE_SNAPSHOT))
        set(value) { MmkvManager.encodeSettings(STATE_SNAPSHOT, value?.encode().orEmpty()) }

    var active: String?
        get() = MmkvManager.decodeSettingsString(STATE_ACTIVE)?.takeIf { it.isNotBlank() }
        set(value) { MmkvManager.encodeSettings(STATE_ACTIVE, value.orEmpty()) }

    /** Ends the episode: no snapshot, no active bypass server. */
    fun clearState() {
        snapshot = null
        active = null
        NetInfoCache.writeBypass(if (isEnabled()) BypassState.IDLE else BypassState.OFF)
    }

    /** The "Best" button picked [guid]; a later snapshot of that same server is a "best" snapshot. */
    fun markBest(guid: String) = MmkvManager.encodeSettings(STATE_BEST_GUID, guid)

    fun isBestSelection(guid: String): Boolean = MmkvManager.decodeSettingsString(STATE_BEST_GUID) == guid

    /** Servers that failed a check recently (skipped while the mark lasts). */
    fun badMarks(now: Long = System.currentTimeMillis()): Map<String, Long> =
        BadMarks.decode(MmkvManager.decodeSettingsString(STATE_BAD), now, badMinutes() * 60_000L)

    fun isBad(guid: String, now: Long = System.currentTimeMillis()): Boolean =
        BadMarks.isBad(badMarks(now), guid, now, badMinutes() * 60_000L)

    fun markBad(guid: String, now: Long = System.currentTimeMillis()) {
        MmkvManager.encodeSettings(STATE_BAD, BadMarks.encode(badMarks(now) + (guid to now)))
    }

    /** Last diagnosis, shown in developer mode only. */
    var lastDiagnosis: String
        get() = MmkvManager.decodeSettingsString(STATE_LAST_DIAG).orEmpty()
        set(value) { MmkvManager.encodeSettings(STATE_LAST_DIAG, value) }

    /** guid -> time it last worked as a bypass server, newest first. */
    fun recentSuccess(): Map<String, Long> =
        MmkvManager.decodeSettingsString(STATE_SUCCESS).orEmpty().split(';').mapNotNull { entry ->
            val idx = entry.lastIndexOf('=')
            if (idx <= 0) return@mapNotNull null
            val ts = entry.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            entry.substring(0, idx) to ts
        }.toMap()

    fun recordSuccess(guid: String, now: Long = System.currentTimeMillis()) {
        val updated = (recentSuccess() + (guid to now)).entries
            .sortedByDescending { it.value }
            .take(SUCCESS_KEEP)
            .joinToString(";") { "${it.key}=${it.value}" }
        MmkvManager.encodeSettings(STATE_SUCCESS, updated)
    }

    // ---- diagnosis (pure) ----

    enum class Diagnosis { OK, WHITELIST, SERVER_DOWN, NO_NETWORK }

    /**
     * @param viaProxy a foreign test URL loads through the current server
     * @param domesticDirect Russian sites answer over the mobile network directly
     * @param foreignDirect foreign sites answer over the mobile network directly
     */
    fun diagnose(viaProxy: Boolean, domesticDirect: Boolean, foreignDirect: Boolean): Diagnosis = when {
        viaProxy -> Diagnosis.OK
        !domesticDirect && !foreignDirect -> Diagnosis.NO_NETWORK
        // Only whitelisted (domestic) resources answer: the classic mobile whitelist.
        domesticDirect && !foreignDirect -> Diagnosis.WHITELIST
        // The open internet works, only our server does not: a dead server, not a whitelist.
        else -> Diagnosis.SERVER_DOWN
    }

    /**
     * Candidate order in automatic mode: favorites, then servers that recently worked as a
     * bypass (newest first), then servers with a known good delay (fastest first), then the rest.
     */
    fun orderCandidates(
        guids: List<String>,
        favorites: Set<String>,
        recentSuccess: Map<String, Long>,
        knownDelay: (String) -> Long,
    ): List<String> = guids.distinct().sortedWith(
        compareBy<String>(
            { if (it in favorites) 0 else 1 },
            { -(recentSuccess[it] ?: 0L) },
            { knownDelay(it).let { d -> if (d > 0) d else Long.MAX_VALUE } },
        )
    )

    /** The step of the pause after a search that found nothing: 15 s, 30 s, 1, 2, then 5 minutes (the caller adds the jitter). */
    fun backoffMillis(failedSearches: Int): Long = com.v2ray.ang.net.Backoff.baseMs(failedSearches)
}
