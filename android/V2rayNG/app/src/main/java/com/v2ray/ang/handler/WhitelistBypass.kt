package com.v2ray.ang.handler

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

    const val MODE_AUTO = "auto"
    const val MODE_MANUAL = "manual"

    // Runtime state, not settings: the server we left and the one we switched to.
    private const val STATE_ORIGIN = "whitelist_bypass_origin"
    private const val STATE_ACTIVE = "whitelist_bypass_active"
    private const val STATE_SUCCESS = "whitelist_bypass_success"
    private const val STATE_LAST_DIAG = "whitelist_bypass_last_diag"
    private const val SUCCESS_KEEP = 20

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(PREF_ENABLED, false)

    fun mode(): String = MmkvManager.decodeSettingsString(PREF_MODE, MODE_AUTO) ?: MODE_AUTO

    fun autoReturn(): Boolean = MmkvManager.decodeSettingsBool(PREF_AUTO_RETURN, true)

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

    var origin: String?
        get() = MmkvManager.decodeSettingsString(STATE_ORIGIN)?.takeIf { it.isNotBlank() }
        set(value) { MmkvManager.encodeSettings(STATE_ORIGIN, value.orEmpty()) }

    var active: String?
        get() = MmkvManager.decodeSettingsString(STATE_ACTIVE)?.takeIf { it.isNotBlank() }
        set(value) { MmkvManager.encodeSettings(STATE_ACTIVE, value.orEmpty()) }

    fun clearState() {
        origin = null
        active = null
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

    /** Backoff after a search that found nothing: 1, 2, 5, then 10 minutes. */
    fun backoffMillis(failedSearches: Int): Long = when {
        failedSearches <= 1 -> 60_000L
        failedSearches == 2 -> 120_000L
        failedSearches == 3 -> 300_000L
        else -> 600_000L
    }
}
