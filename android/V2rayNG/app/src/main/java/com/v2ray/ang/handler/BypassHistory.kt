package com.v2ray.ang.handler

import android.content.Context
import android.content.Context.TELEPHONY_SERVICE
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.v2ray.ang.net.BypassHistoryLogic
import com.v2ray.ang.net.HistoryEntry
import com.v2ray.ang.net.HistoryFacts

/**
 * What tests on a MOBILE network said about each server, kept on the phone only (MMKV), keyed by a
 * fingerprint of the server rather than its id, and by the operator's numeric code (MCC+MNC, no
 * permission needed) when it is known. Results from Wi-Fi are never written here: they say nothing
 * about a whitelist.
 */
object BypassHistory {
    private const val KEY = "bypass_history"
    private val lock = Any()

    /** The operator bucket of the active data connection: `cellular:25001`, or `cellular` when unknown. */
    fun bucket(context: Context): String {
        val code = runCatching {
            var tm = context.getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
            val subId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (tm != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm = tm.createForSubscriptionId(subId)
            tm?.networkOperator
        }.getOrNull()
        return BypassHistoryLogic.bucket(code)
    }

    private fun load(): MutableMap<String, HistoryEntry> = BypassHistoryLogic.decode(MmkvManager.decodeSettingsString(KEY)).toMutableMap()

    private fun save(map: Map<String, HistoryEntry>) = MmkvManager.encodeSettings(KEY, BypassHistoryLogic.encode(map))

    fun entry(fingerprint: String, bucket: String): HistoryEntry? = load()[BypassHistoryLogic.key(fingerprint, bucket)]

    /** The newest result for this server in any operator bucket (for badges in the UI). */
    fun latest(fingerprint: String): HistoryEntry? {
        val mine = load().filterKeys { it.substringBefore('@') == fingerprint }.values
        if (mine.isEmpty()) return null
        return HistoryEntry(
            ok = mine.sumOf { it.ok }, fail = mine.sumOf { it.fail },
            lastOkAt = mine.maxOf { it.lastOkAt }, lastFailAt = mine.maxOf { it.lastFailAt },
            streak = mine.minOf { it.streak }, avgPingMs = mine.maxOf { it.avgPingMs },
        )
    }

    fun facts(fingerprint: String, bucket: String, now: Long = System.currentTimeMillis()): HistoryFacts? =
        BypassHistoryLogic.facts(entry(fingerprint, bucket), now, WhitelistBypass.badMinutes())

    fun isBad(fingerprint: String, bucket: String, now: Long = System.currentTimeMillis()): Boolean =
        BypassHistoryLogic.isBad(entry(fingerprint, bucket), now, WhitelistBypass.badMinutes())

    /** Only called with results obtained on the mobile network ([onCellular] guards it once more). */
    fun recordOk(fingerprint: String, bucket: String, pingMs: Long, onCellular: Boolean, now: Long = System.currentTimeMillis()) {
        if (!onCellular) return
        synchronized(lock) {
            val map = load()
            val key = BypassHistoryLogic.key(fingerprint, bucket)
            map[key] = BypassHistoryLogic.recordOk(map[key], now, pingMs)
            save(map)
        }
    }

    /** Returns how many failures in a row this server has now (0 when nothing was written). */
    fun recordFail(fingerprint: String, bucket: String, onCellular: Boolean, now: Long = System.currentTimeMillis()): Int {
        if (!onCellular) return 0
        synchronized(lock) {
            val map = load()
            val key = BypassHistoryLogic.key(fingerprint, bucket)
            val updated = BypassHistoryLogic.recordFail(map[key], now)
            map[key] = updated
            save(map)
            return updated.streak
        }
    }

    /** Forgets servers that are no longer in any profile. */
    fun prune(liveFingerprints: Set<String>) {
        synchronized(lock) {
            val map = load()
            val kept = BypassHistoryLogic.prune(map, liveFingerprints)
            if (kept.size != map.size) save(kept)
        }
    }
}
