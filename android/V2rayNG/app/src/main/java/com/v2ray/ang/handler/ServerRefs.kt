package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.net.IdEntry
import com.v2ray.ang.net.LostRef
import com.v2ray.ang.net.ServerIdentity

/**
 * Keeps the user's choices tied to servers across subscription updates (see [ServerIdentity]): the fingerprint of a
 * stored profile, the ids the user points at, and the servers that disappeared from their subscription for a while.
 */
object ServerRefs {
    private const val KEY_LOST = "lost_server_refs"
    private val lock = Any()

    /** What makes the server the server, never its name. A provider's JSON config is identified by its whole text. */
    fun fingerprintOf(p: ProfileItem, raw: String?): String = when (p.configType) {
        EConfigType.CUSTOM -> if (raw.isNullOrBlank()) "" else ServerIdentity.fingerprint(listOf("custom", raw))
        EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN -> ""
        else -> ServerIdentity.fingerprint(
            listOf(
                p.configType.name, p.server, p.serverPort, p.password, p.method, p.username, p.network, p.headerType,
                p.host, p.path, p.serviceName, p.security, p.sni, p.flow, p.publicKey, p.shortId,
            )
        )
    }

    fun entryOf(id: String, p: ProfileItem, raw: String?): IdEntry = IdEntry(id, fingerprintOf(p, raw), ServerIdentity.nameKey(p.remarks))

    /** Ids the user chose by hand: favorites and the servers picked for the mobile network. */
    fun referencedIds(): Set<String> = FavoriteServers.all() + WhitelistBypass.storedServers()

    fun lost(now: Long = System.currentTimeMillis()): List<LostRef> =
        LostRef.prune(LostRef.decode(MmkvManager.decodeSettingsString(KEY_LOST)), now, emptySet())

    fun lostIds(): Set<String> = lost().map { it.id }.toSet()

    /** Lost servers that may get their id back in [subId]: by fingerprint from any subscription, by name only from the same one. */
    fun candidatesFor(subId: String): List<IdEntry> = lost()
        .filter { MmkvManager.decodeServerConfig(it.id) == null }
        .map { IdEntry(it.id, it.fingerprint, if (it.subId == subId) it.nameKey else "") }

    /** [refs]: chosen servers that are about to disappear. [present]: ids that exist again (no longer lost). */
    fun update(refs: List<LostRef>, present: Set<String>, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val merged = LostRef.prune(LostRef.decode(MmkvManager.decodeSettingsString(KEY_LOST)) + refs, now, present)
            MmkvManager.encodeSettings(KEY_LOST, LostRef.encode(merged))
        }
    }

    fun forget(id: String) = update(emptyList(), setOf(id))
}
