package com.v2ray.ang.net

/** How one episode of the auto switch ended. */
enum class EpisodeResult { OK, NO_SERVER, ABORTED, NOTHING_TO_TRY }

/**
 * What happened during one episode (from "the network turned out restricted" to "works" or "gave up"), counted
 * so the decision log can say it in one line and so the speed and the mistakes can be judged from real runs.
 * No addresses, links, keys or network names: only counts, reasons and times. Thread-safe: the isolated tests
 * report from several coroutines at once.
 */
class EpisodeStats(val startedAt: Long) {
    private val lock = Any()
    private var firstOkAt: Long? = null
    private var tested = 0
    private var passed = 0
    private var timedOut = 0
    private var liveOk = 0
    private var liveFail = 0
    private var falsePositives = 0
    private var reloadRejected = 0
    private var notRecorded = 0
    private var bans = 0
    private var quick: String = "no"
    private val reasons = LinkedHashMap<FailReason, Int>()

    fun isolated(passed: Boolean, timedOut: Boolean) = synchronized(lock) {
        tested++
        if (passed) this.passed++
        if (timedOut) this.timedOut++
    }

    /** The live check after a switch: [reason] only for a failure. A pass of the isolated test followed by a live failure is a false positive. */
    fun live(outcome: LiveOutcome, reason: FailReason?, now: Long) = synchronized(lock) {
        when (outcome) {
            LiveOutcome.OK -> {
                liveOk++
                if (firstOkAt == null) firstOkAt = now
            }
            LiveOutcome.FAIL -> {
                liveFail++
                falsePositives++
                if (reason != null) reasons[reason] = (reasons[reason] ?: 0) + 1
            }
            LiveOutcome.UNKNOWN -> Unit
        }
    }

    fun reloadRejected() = synchronized(lock) { reloadRejected++ }

    /** Failures that were NOT written to the history or turned into a ban (the test could not be trusted). */
    fun notRecorded(count: Int) = synchronized(lock) { notRecorded += count }

    fun banned() = synchronized(lock) { bans++ }

    fun quickPath(result: String) = synchronized(lock) { quick = result }

    fun summary(now: Long, result: EpisodeResult): String = synchronized(lock) {
        fun sec(ms: Long) = "%.1fs".format(java.util.Locale.US, ms / 1000.0)
        val first = firstOkAt?.let { sec((it - startedAt).coerceAtLeast(0)) } ?: "-"
        val why = if (reasons.isEmpty()) "-" else reasons.entries.joinToString(",") { "${it.key.name}:${it.value}" }
        "episode: result=$result first-ok=$first total=${sec((now - startedAt).coerceAtLeast(0))} quick=$quick " +
            "tested=$tested passed=$passed timed-out=$timedOut live-ok=$liveOk live-fail=$liveFail false-positive=$falsePositives " +
            "reasons=$why reload-rejected=$reloadRejected not-recorded=$notRecorded bans=$bans"
    }
}

/** The text a user can copy from the developer screen: a few facts about the app and the phone, then the decision log. Masked like the other reports. */
object BypassReport {
    fun build(header: List<Pair<String, String>>, log: String): String {
        val head = header.joinToString("\n") { (k, v) -> "$k: $v" }
        return ReportMask.apply("$head\n\n${log.trim()}\n")
    }
}
