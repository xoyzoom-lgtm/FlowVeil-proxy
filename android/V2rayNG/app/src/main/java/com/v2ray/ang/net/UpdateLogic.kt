package com.v2ray.ang.net

import java.util.Locale

/*
 * Update notifications, the pure part (no Android classes: tested on the JVM). The Windows client has the same logic in
 * ServiceLib/Handler/UpdateLogic.cs; the tests on both sides describe the same table.
 *  - which release is "the newest for this device" (highest build number, never GitHub's "latest" flag)
 *  - when to bother the user (once per version, one reminder after 3 days, "Later", "Skip this version")
 *  - the release notes as safe plain text, SHA-256 sums, check throttling and back-off
 */

data class ReleaseAsset(val name: String, val url: String)

data class ReleaseInfo(val tag: String, val draft: Boolean, val prerelease: Boolean, val body: String, val assets: List<ReleaseAsset>)

data class UpdateCandidate(
    val build: Int,
    val tag: String,
    val notes: String,
    val assetName: String,
    val assetUrl: String,
    /** The `SHA256SUMS.txt` of that release, when it has one. */
    val sumsUrl: String?,
    /** FlowVeil-manifest.json and its signature, when the release has them. */
    val manifestUrl: String? = null,
    val signatureUrl: String? = null,
)

object UpdateLogic {
    const val SUMS_FILE = "SHA256SUMS.txt"

    /** "v0092" → 92, "build-92" → 92; null when the tag has no digits. */
    fun buildOf(tag: String): Int? = tag.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /**
     * The newest published release that has one of [wantedAssets] (in order of preference), if it is newer than [currentBuild].
     * A newer release without a fitting file is skipped in favour of an older one that has it (still newer than the running build).
     */
    fun pick(releases: List<ReleaseInfo>, currentBuild: Int, wantedAssets: List<String>): UpdateCandidate? {
        var best: UpdateCandidate? = null
        for (release in releases) {
            if (release.draft || release.prerelease) continue
            val build = buildOf(release.tag) ?: continue
            if (build <= currentBuild || (best != null && build <= best.build)) continue
            val asset = wantedAssets.firstNotNullOfOrNull { wanted -> release.assets.firstOrNull { it.name == wanted } } ?: continue
            best = UpdateCandidate(
                build = build,
                tag = release.tag,
                notes = release.body,
                assetName = asset.name,
                assetUrl = asset.url,
                sumsUrl = release.assets.firstOrNull { it.name == SUMS_FILE }?.url,
                manifestUrl = release.assets.firstOrNull { it.name == UpdateManifest.MANIFEST_FILE }?.url,
                signatureUrl = release.assets.firstOrNull { it.name == UpdateManifest.SIGNATURE_FILE }?.url,
            )
        }
        return best
    }

    /** Android: the small arm64 build for modern phones, the universal one for everything else. */
    fun androidAssets(primaryAbi: String?): List<String> =
        if (primaryAbi?.contains("arm64", ignoreCase = true) == true) listOf("FlowVeil-android-arm64.apk", "FlowVeil-android.apk")
        else listOf("FlowVeil-android.apk")
}

/** What we remember about notifying the user. Times are epoch milliseconds. */
data class NotifyState(
    val lastNotifiedBuild: Int = 0,
    val lastNotifiedAt: Long = 0L,
    val reminded: Boolean = false,
    val snoozedUntil: Long = 0L,
    val skippedBuild: Int = 0,
) {
    fun encode(): String = listOf(lastNotifiedBuild, lastNotifiedAt, if (reminded) 1 else 0, snoozedUntil, skippedBuild).joinToString("|")

    companion object {
        fun decode(text: String?): NotifyState {
            val p = text?.split('|') ?: return NotifyState()
            if (p.size != 5) return NotifyState()
            return NotifyState(p[0].toIntOrNull() ?: 0, p[1].toLongOrNull() ?: 0L, p[2] == "1", p[3].toLongOrNull() ?: 0L, p[4].toIntOrNull() ?: 0)
        }
    }
}

object NotifyPolicy {
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val REMIND_AFTER_MS = 3 * DAY_MS

    /** A quiet banner inside the app: any newer, not skipped, not snoozed version. */
    fun bannerVisible(state: NotifyState, candidateBuild: Int, currentBuild: Int, now: Long): Boolean =
        candidateBuild > currentBuild && state.skippedBuild != candidateBuild && now >= state.snoozedUntil

    /** A system notification: once per version, then one reminder after 3 days; never a downgrade, never a skipped or snoozed version. */
    fun shouldNotify(state: NotifyState, candidateBuild: Int, currentBuild: Int, now: Long): Boolean {
        if (!bannerVisible(state, candidateBuild, currentBuild, now)) return false
        if (state.lastNotifiedBuild != candidateBuild) return true
        return !state.reminded && now - state.lastNotifiedAt >= REMIND_AFTER_MS
    }

    fun afterNotified(state: NotifyState, build: Int, now: Long): NotifyState =
        state.copy(lastNotifiedBuild = build, lastNotifiedAt = now, reminded = state.lastNotifiedBuild == build)

    fun afterLater(state: NotifyState, now: Long): NotifyState = state.copy(snoozedUntil = now + REMIND_AFTER_MS)

    fun afterSkip(state: NotifyState, build: Int): NotifyState = state.copy(skippedBuild = build)
}

/** How often to ask GitHub: at most once per [MIN_INTERVAL_MS], and after failures wait longer (silently). */
object CheckThrottle {
    const val MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val MAX_BACKOFF_MS = 24 * 60 * 60 * 1000L

    fun delayAfterFailures(failures: Int): Long {
        if (failures <= 0) return MIN_INTERVAL_MS
        val factor = 1L shl minOf(failures, 4)
        return minOf(MIN_INTERVAL_MS / 2 * factor, MAX_BACKOFF_MS)
    }

    fun mayCheck(lastAttemptAt: Long, failures: Int, now: Long): Boolean = now - lastAttemptAt >= delayAfterFailures(failures)
}

/** `SHA256SUMS.txt`: "<hex>  <file>" or "<hex> *<file>" per line. */
object Sha256Sums {
    fun parse(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val match = Regex("""^([0-9a-fA-F]{64})\s+\*?(\S.*?)\s*$""").find(line.trim()) ?: return@mapNotNull null
        match.groupValues[2] to match.groupValues[1].lowercase(Locale.ROOT)
    }.toMap()

    /** true = matches, false = differs (do not install), null = the file is not listed (do not block). */
    fun verify(sums: Map<String, String>, fileName: String, actualHex: String): Boolean? =
        sums[fileName]?.let { it == actualHex.lowercase(Locale.ROOT) }
}

/** The release notes shown as plain text: no HTML, no links that could be pressed, bounded length. */
object ReleaseNotes {
    fun plain(body: String, maxChars: Int = 700): String {
        var text = body.replace("\r\n", "\n")
        text = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL).replace(text, "")
        text = Regex("<[^>]*>").replace(text, "")
        text = Regex("!\\[[^\\]]*]\\([^)]*\\)").replace(text, "")
        text = Regex("\\[([^\\]]*)]\\([^)]*\\)").replace(text) { it.groupValues[1] }
        text = text.replace("**", "").replace("__", "").replace("`", "")
        text = Regex("(?m)^\\s{0,3}#{1,6}\\s*").replace(text, "")
        text = Regex("(?m)^\\s*[-*]\\s+").replace(text, "• ")
        text = text.lines().joinToString("\n") { it.trimEnd() }
        text = Regex("\n{3,}").replace(text, "\n\n").trim()
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val lineEnd = cut.lastIndexOf('\n')
        return (if (lineEnd > maxChars / 2) cut.substring(0, lineEnd) else cut).trimEnd() + "…"
    }
}
