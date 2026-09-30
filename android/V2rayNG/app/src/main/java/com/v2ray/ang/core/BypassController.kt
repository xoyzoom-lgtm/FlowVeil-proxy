package com.v2ray.ang.core

import android.app.Service
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.BypassHistory
import com.v2ray.ang.handler.BypassLog
import com.v2ray.ang.handler.BypassRating
import com.v2ray.ang.handler.BypassState
import com.v2ray.ang.handler.LocalProxy
import com.v2ray.ang.handler.DevMode
import com.v2ray.ang.handler.FavoriteServers
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NetInfoCache
import com.v2ray.ang.handler.NetProbe
import com.v2ray.ang.handler.ServerCountry
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.handler.WhitelistBypass.Diagnosis
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.net.BypassLevel
import com.v2ray.ang.net.BypassSearch
import com.v2ray.ang.net.BypassSnapshot
import com.v2ray.ang.net.BypassVerdict
import com.v2ray.ang.net.FailReason
import com.v2ray.ang.net.IsoResult
import com.v2ray.ang.net.LiveOutcome
import com.v2ray.ang.net.NetType
import com.v2ray.ang.net.Outcome
import com.v2ray.ang.net.PingUrls
import com.v2ray.ang.net.OriginState
import com.v2ray.ang.net.ProbeStep
import com.v2ray.ang.net.ReturnAction
import com.v2ray.ang.net.ReturnLogic
import com.v2ray.ang.net.SearchCandidate
import com.v2ray.ang.net.SearchEnv
import com.v2ray.ang.net.SearchLimits
import com.v2ray.ang.net.Verdict
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.service.RealPingExecutionLimiter
import com.v2ray.ang.service.SpeedtestConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Automatic switching for restricted mobile networks and the way back (see [WhitelistBypass]).
 *
 * Everything that changes the server runs under [ConnectionWatchdog.switchLock], so failover, the
 * search for a working server and the return never switch at the same time, and at most one
 * search runs. The real network is read by [PhysicalNetwork]; probes that must not go through our
 * own tunnel are bound to it ([NetProbe.physical]), probes that must go through the server use the
 * local proxy ([NetProbe.tunnel]).
 *
 * Two searches with two different rules, deliberately not mixed:
 *  - on the mobile network (a bypass): Russian servers ARE allowed, under a whitelist they are
 *    often the only ones that work; every switch is verified in depth ([verifyAfterSwitch], full);
 *  - back on Wi-Fi/Ethernet (a return): Russian servers are NEVER picked as a replacement.
 */
object BypassController {
    private const val BYPASS_PARALLEL = 8
    private const val BYPASS_MAX_CANDIDATES = 48
    private const val PER_CANDIDATE_MS = 15_000L
    private const val TEST_ALL_BUDGET_MS = 90_000L
    private const val TEST_ALL_MAX = 60
    private const val RETURN_MAX_CANDIDATES = 24
    private const val RETURN_CHECK_INTERVAL_MS = 150_000L
    private const val IDENTITY_INTERVAL_MS = 60_000L
    private const val IDENTITY_MIN_GAP_MS = 10_000L
    private const val VERIFY_DELAY_MS = 3_000L
    private const val STABILITY_DELAY_MS = 9_000L
    private const val FULL_CHECK_LIMIT_MS = 30_000L
    private const val SOFT_CHECK_LIMIT_MS = 12_000L
    private const val NOTIFY_SWITCH_GAP_MS = 5 * 60_000L
    private const val NOTIFY_PROBLEM_GAP_MS = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var returnJob: Job? = null
    private var eventJob: Job? = null
    private var identityJob: Job? = null

    /** The user picked a server during a bypass: leave it alone until the network type changes. */
    @Volatile
    private var userOverride = false
    private var failedSearches = 0
    private var nextSearchAt = 0L
    private var lastPeriodicReturnAt = 0L
    private var lastIdentityAt = 0L
    private val lastNotified = HashMap<String, Long>()

    // ------------------------------------------------------------------
    // Lifecycle (called by the watchdog)
    // ------------------------------------------------------------------

    fun start(service: Service) {
        PhysicalNetwork.start(service) { old, new -> onNetworkChanged(old, new) }
        detectUserChoice()
        if (WhitelistBypass.isEnabled()) {
            if (NetInfoCache.read().bypass == BypassState.OFF) NetInfoCache.writeBypass(BypassState.IDLE)
            scheduleIdentity(delayMs = 3_000L, force = true)
        } else {
            NetInfoCache.writeBypass(BypassState.OFF)
        }
    }

    fun stop() {
        returnJob?.cancel()
        eventJob?.cancel()
        identityJob?.cancel()
        returnJob = null
        eventJob = null
        identityJob = null
        PhysicalNetwork.stop()
        NetInfoCache.clearOnStop()
    }

    /** The user turned the connection off himself: the episode is over and nothing is kept. */
    fun onUserStop() {
        WhitelistBypass.clearState()
        userOverride = false
        failedSearches = 0
        nextSearchAt = 0L
    }

    /** True while WE change the server: the restart that follows must not look like a user's choice. */
    @Volatile
    private var switching = false

    /** Every server change made by FlowVeil itself goes through here (bypass, return, failover). */
    internal fun ourSwitch(guid: String): Boolean {
        switching = true
        return try {
            CoreServiceManager.switchServer(guid).also { if (it) lastOurs = guid }
        } finally {
            switching = false
        }
    }

    /** The server FlowVeil itself selected last; a different one in MMKV means the user stepped in. */
    @Volatile
    private var lastOurs: String? = null

    private fun userChangedServer(): Boolean {
        val ours = lastOurs ?: return false
        return CoreServiceManager.currentServerGuid() != ours
    }

    fun applies(): Boolean =
        WhitelistBypass.isEnabled() && PhysicalNetwork.snapshot.type == NetType.CELLULAR && !userOverride

    // ------------------------------------------------------------------
    // The watchdog loop asks
    // ------------------------------------------------------------------

    /** Once per watchdog tick (screen on, feature on), under the switch lock. */
    suspend fun beforeCheck() {
        detectUserChoice()
        if (System.currentTimeMillis() - lastIdentityAt >= IDENTITY_INTERVAL_MS) scheduleIdentity(0L, force = false)
        maybeReturnPeriodically()
    }

    /**
     * The current server failed twice. Returns true when the caller should run the ordinary
     * failover (the failure is a plain dead server, not a restricted network).
     */
    suspend fun handleFailure(): Boolean {
        val current = CoreServiceManager.currentServerGuid() ?: return false
        val manual = WhitelistBypass.mode() == WhitelistBypass.MODE_MANUAL
        val onOurBypass = WhitelistBypass.active == current
        // A bypass server the user picked himself is his choice: do not move him off it.
        if (!onOurBypass && manual && current in WhitelistBypass.servers()) {
            BypassLog.add("the current server is a user-chosen bypass server, leaving it")
            return false
        }
        when (diagnoseNow()) {
            Diagnosis.OK -> Unit
            Diagnosis.NO_NETWORK -> {
                NetInfoCache.writeBypass(BypassState.NO_NETWORK, returnTo = returnToName())
                BypassLog.add("no network at all, not switching")
            }
            Diagnosis.WHITELIST -> {
                if (onOurBypass) WhitelistBypass.markBad(current)
                searchBypass(current)
            }
            Diagnosis.SERVER_DOWN -> {
                // The open internet works: this is a dead server, not a restriction.
                if (onOurBypass && WhitelistBypass.autoReturn() && returnOnlyIfOriginWorks()) return false
                if (onOurBypass) WhitelistBypass.clearState()
                return true
            }
        }
        return false
    }

    /** Checks right after the phone moved to the mobile network (called from the network event). */
    private suspend fun quickCheckOnCellular() {
        if (!CoreServiceManager.isRunning() || !applies()) return
        if (CoreServiceManager.measureCurrentDelay() >= 0L) return
        delay(5_000L)
        if (!CoreServiceManager.isRunning() || !applies() || CoreServiceManager.measureCurrentDelay() >= 0L) return
        handleFailure()
    }

    /** The "check now" button of the UI: fresh network type, both IPs and a light check of the server. */
    suspend fun checkNow() {
        PhysicalNetwork.refreshNow()
        withContext(Dispatchers.IO) { refreshIdentity(clearOnFail = true) }
        if (!WhitelistBypass.isEnabled() || !CoreServiceManager.isRunning()) return
        val verdict = verifyAfterSwitch(full = false) { CoreServiceManager.isRunning() }
        writeVerdict(verdict)
    }

    // ------------------------------------------------------------------
    // Network changes
    // ------------------------------------------------------------------

    private fun onNetworkChanged(old: PhysicalNetwork.Snapshot, new: PhysicalNetwork.Snapshot) {
        if (old.type != new.type) {
            userOverride = false
            failedSearches = 0
            nextSearchAt = 0L
        }
        if (!WhitelistBypass.isEnabled()) return
        scheduleIdentity(0L, force = true)
        eventJob?.cancel()
        returnJob?.cancel()
        when {
            new.type.isLan -> if (WhitelistBypass.snapshot != null && WhitelistBypass.autoReturn()) scheduleReturn()
            new.type == NetType.CELLULAR -> {
                eventJob = scope.launch {
                    ConnectionWatchdog.switchLock.withLock { quickCheckOnCellular() }
                }
            }
            else -> NetInfoCache.writeBypass(BypassState.NO_NETWORK, returnTo = returnToName())
        }
    }

    /** Waits until the normal network has been up long enough, then returns. */
    private fun scheduleReturn() {
        returnJob?.cancel()
        returnJob = scope.launch {
            val stableMs = WhitelistBypass.stableSeconds() * 1000L
            while (isActive) {
                val net = PhysicalNetwork.snapshot
                if (!net.type.isLan) return@launch // flapped back: the new event decides
                val left = stableMs - (System.currentTimeMillis() - net.since)
                if (left <= 0L) break
                delay(left.coerceIn(200L, 1_000L))
            }
            ConnectionWatchdog.switchLock.withLock { returnToNormalNetwork() }
        }
    }

    // ------------------------------------------------------------------
    // Identity: real IP (physical network) and exit IP (through the server)
    // ------------------------------------------------------------------

    private fun scheduleIdentity(delayMs: Long, force: Boolean) {
        // Only the developer-mode card shows the addresses; the checks that need them fetch them themselves.
        if (!DevMode.isOn()) return
        identityJob?.cancel()
        identityJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (!CoreServiceManager.isRunning()) return@launch
            if (!force && System.currentTimeMillis() - lastIdentityAt < IDENTITY_MIN_GAP_MS) return@launch
            refreshIdentity(clearOnFail = force)
        }
    }

    /** Never logs an address: only which route was used and whether it worked. */
    private fun refreshIdentity(clearOnFail: Boolean) {
        lastIdentityAt = System.currentTimeMillis()
        val net = PhysicalNetwork.snapshot
        if (net.type != NetType.NONE) {
            val (v4, v6) = NetProbe.physical(net.network).use { client ->
                client.fetchIp(NetProbe.IP_SERVICES) to (if (net.hasIpv6) client.fetchIp(NetProbe.IPV6_SERVICES) else null)
            }
            if (v4 != null || clearOnFail) NetInfoCache.writeRealIp(v4, v6)
        }
        if (CoreServiceManager.isRunning()) {
            val route = CoreServiceManager.currentServerGuid()?.let { LocalProxy.resolve(it) }
            val exit = route?.let { r -> NetProbe.tunnel(r).use { it.fetchIp(NetProbe.IP_SERVICES) } }
            if (exit != null || clearOnFail) NetInfoCache.writeExitIp(exit)
            val real = NetInfoCache.readRealIp()
            if (exit != null && real != null && exit == real) {
                BypassLog.add("the address through the server equals the real one: the tunnel does not carry this traffic")
            }
        }
    }

    private fun fetchRealIp(): String? {
        val net = PhysicalNetwork.snapshot
        if (net.type == NetType.NONE) return null
        return NetProbe.physical(net.network).use { it.fetchIp(NetProbe.IP_SERVICES) }
    }

    // ------------------------------------------------------------------
    // Diagnosis of a restricted network
    // ------------------------------------------------------------------

    /**
     * Russian sites and a foreign reference, both directly over the mobile network (bound to it,
     * never through the tunnel). Domestic answers and foreign does not: a whitelist.
     */
    private suspend fun diagnoseNow(): Diagnosis {
        val network = PhysicalNetwork.snapshot.network
        val (domestic, foreign) = withContext(Dispatchers.IO) {
            NetProbe.physical(network).use { client ->
                coroutineScope {
                    val d = async { NetProbe.DOMESTIC_SITES.map { url -> async { client.reachable(url) } }.awaitAll().any { it } }
                    val f = async { NetProbe.FOREIGN_SITES.map { url -> async { client.reachable(url) } }.awaitAll().any { it } }
                    d.await() to f.await()
                }
            }
        }
        val diagnosis = WhitelistBypass.diagnose(viaProxy = false, domesticDirect = domestic, foreignDirect = foreign)
        WhitelistBypass.lastDiagnosis = "${diagnosis.name}: ru-direct=$domestic foreign-direct=$foreign @${System.currentTimeMillis()}"
        BypassLog.add("diagnosis ${diagnosis.name} (ru-direct=$domestic foreign-direct=$foreign)")
        return diagnosis
    }

    /** Wi-Fi may be up with no internet behind it (login page, broken router): then do not go back. */
    private suspend fun lanHasInternet(net: PhysicalNetwork.Snapshot): Boolean = withContext(Dispatchers.IO) {
        NetProbe.physical(net.network).use { client ->
            coroutineScope { NetProbe.DOMESTIC_SITES.map { url -> async { client.reachable(url) } }.awaitAll().any { it } }
        }
    }

    // ------------------------------------------------------------------
    // Verification of a server that carries traffic (after every automatic switch)
    // ------------------------------------------------------------------

    /**
     * Steps: gstatic 204 twice through the server (the first one only warms the connection up),
     * a small real download and the exit IP together, and, for [full], the same gstatic request
     * again several seconds later (some servers die right after the handshake).
     * A step-by-step verdict comes from the pure [BypassVerdict]; [alive] going false mid-check
     * (connection stopped, network changed) gives UNKNOWN, never a verdict about the server.
     */
    private suspend fun verifyAfterSwitch(full: Boolean, alive: () -> Boolean): Verdict {
        NetInfoCache.writeBypass(BypassState.CHECKING, returnTo = returnToName())
        val limit = if (full) FULL_CHECK_LIMIT_MS else SOFT_CHECK_LIMIT_MS
        return withTimeoutOrNull(limit) { withContext(Dispatchers.IO) { runChecks(full, alive) } }
            ?: Verdict(Outcome.FAIL, FailReason.NO_GSTATIC)
    }

    private suspend fun runChecks(full: Boolean, alive: () -> Boolean): Verdict {
        val guid = CoreServiceManager.currentServerGuid()
        val route = guid?.let { LocalProxy.resolve(it) }
        if (route == null) return runChecksWithoutInbound(full, alive)
        BypassLog.add("check through the server via local ${route.label}")
        val tunnel = NetProbe.tunnel(route)
        try {
            val first = tunnel.get(NetProbe.GSTATIC_204).step
            if (first.kind == ProbeStep.Kind.REFUSED) return BypassVerdict.decide(first, null, null, null, null)
            val gstatic = tunnel.get(NetProbe.GSTATIC_204).step
            if (!alive()) return Verdict(Outcome.UNKNOWN)
            val quick = BypassVerdict.decide(gstatic, null, null, null, null)
            if (quick.outcome != Outcome.OK) return quick

            val (content, exit, real) = coroutineScope {
                val c = async { contentStep(tunnel) }
                val e = async { tunnel.fetchIp(NetProbe.IP_SERVICES) }
                val r = async { NetInfoCache.readRealIp() ?: fetchRealIp() }
                Triple(c.await(), e.await(), r.await())
            }
            if (!alive()) return Verdict(Outcome.UNKNOWN)
            NetInfoCache.writeExitIp(exit)
            if (real != null) NetInfoCache.writeRealIp(real)
            val verdict = BypassVerdict.decide(gstatic, content, exit, real, null)
            if (!full || verdict.outcome != Outcome.OK) return verdict

            // Step 4: still alive after a few seconds?
            var waited = 0L
            while (waited < STABILITY_DELAY_MS) {
                delay(500L)
                waited += 500L
                if (!alive()) return Verdict(Outcome.UNKNOWN)
            }
            val again = tunnel.get(NetProbe.GSTATIC_204).step
            return BypassVerdict.decide(gstatic, content, exit, real, again, cancelled = !alive())
        } finally {
            tunnel.close()
        }
    }

    /**
     * A custom profile without a local HTTP/SOCKS inbound (only the TUN): there is nothing to send a
     * request to, so the core's own delay test is used, two hosts and a repeat. Weaker than the exact
     * HTTP 204 and the data check, and said so in the log.
     */
    private suspend fun runChecksWithoutInbound(full: Boolean, alive: () -> Boolean): Verdict {
        BypassLog.add("check: the profile has no local proxy port, using the core's delay test (weaker)")
        suspend fun delayOf(url: String) = withContext(Dispatchers.IO) { CoreServiceManager.measureLive(url) }
        delayOf(PingUrls.PRIMARY) // warms the connection up
        if (!alive()) return Verdict(Outcome.UNKNOWN)
        if (delayOf(PingUrls.PRIMARY) <= 0) return Verdict(Outcome.FAIL, FailReason.NO_GSTATIC)
        if (delayOf(PingUrls.FALLBACK) <= 0) return Verdict(Outcome.FAIL, FailReason.NO_DATA)
        if (full) {
            var waited = 0L
            while (waited < STABILITY_DELAY_MS) {
                delay(500L)
                waited += 500L
                if (!alive()) return Verdict(Outcome.UNKNOWN)
            }
            if (delayOf(PingUrls.PRIMARY) <= 0) return Verdict(Outcome.FAIL, FailReason.UNSTABLE)
        }
        return Verdict(Outcome.OK, ipUnknown = true)
    }

    /** Several small downloads at once: it is enough that one of them completes. */
    private suspend fun contentStep(tunnel: NetProbe.Client): ProbeStep = coroutineScope {
        val results = NetProbe.CONTENT_URLS.map { url -> async { tunnel.get(url, readBody = true).step } }.awaitAll()
        results.firstOrNull { it.kind == ProbeStep.Kind.OK && it.bytes >= BypassVerdict.MIN_CONTENT_BYTES } ?: results.first()
    }

    private fun writeVerdict(verdict: Verdict) {
        when (verdict.outcome) {
            Outcome.OK -> NetInfoCache.writeBypass(BypassState.OK, returnTo = returnToName())
            Outcome.FAIL -> NetInfoCache.writeBypass(BypassState.FAIL, verdict.reason, returnToName())
            Outcome.UNKNOWN -> Unit
        }
    }

    // ------------------------------------------------------------------
    // Searching and switching
    // ------------------------------------------------------------------

    private data class TryResult(val found: String?, val lastReason: FailReason?, val aborted: Boolean, val tested: Int = 0, val passed: Int = 0)

    /** What the candidate list was made of, for the log and the messages. */
    private data class Plan(
        val tier1: List<BypassRating.Rated>,
        val tier2: List<BypassRating.Rated>,
        val skippedBad: Int,
        val total: Int,
    )

    private fun limits(testOnly: Boolean = false) = SearchLimits(
        parallel = BYPASS_PARALLEL,
        earlyExit = if (testOnly) Int.MAX_VALUE else 2,
        totalMs = if (testOnly) TEST_ALL_BUDGET_MS else WhitelistBypass.searchBudgetMs(),
        perCandidateMs = PER_CANDIDATE_MS,
        pingLimitMs = WhitelistBypass.bypassPingMs(),
        maxLive = 3,
        testOnly = testOnly,
    )

    /**
     * One search over [candidates] (best first): the isolated test of each on the phone's real
     * network, then a switch to the best that passed and the live check; whatever does not pass is
     * marked bad, and a search that ends without a verified server puts the connection back (see
     * [BypassSearch]). [rated] carries the fingerprint and score of each server for the history and
     * the log; the return flow has none and writes no history.
     */
    private suspend fun runSearch(
        service: Service,
        candidates: List<SearchCandidate>,
        rated: Map<String, BypassRating.Rated>,
        full: Boolean,
        testOnly: Boolean,
        stillNeeded: () -> Boolean,
    ): TryResult {
        val startGuid = CoreServiceManager.currentServerGuid()
        lastOurs = startGuid
        val bucket = BypassHistory.bucket(service)
        var lastReason: FailReason? = null
        fun onCellular() = PhysicalNetwork.snapshot.type == NetType.CELLULAR
        val alive = { CoreServiceManager.isRunning() && stillNeeded() && !userChangedServer() }

        val env = object : SearchEnv {
            override suspend fun isolated(id: String): IsoResult {
                val ping = try {
                    withContext(Dispatchers.IO) {
                        RealPingExecutionLimiter.run(SpeedtestConfig.limiterType(id)) { SpeedtestConfig.measureStrict(service, id) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    -1L
                }
                return IsoResult(ping > 0, ping)
            }

            override suspend fun switchTo(id: String): Boolean {
                if (!ourSwitch(id)) return false
                delay(VERIFY_DELAY_MS)
                return true
            }

            override suspend fun verify(id: String): LiveOutcome {
                val verdict = verifyAfterSwitch(full, alive)
                if (verdict.outcome == Outcome.FAIL) lastReason = verdict.reason
                BypassLog.add("live check of ${serverName(id)}: ${verdict.outcome}${verdict.reason?.let { " (${it.name})" }.orEmpty()}")
                return when (verdict.outcome) {
                    Outcome.OK -> LiveOutcome.OK
                    Outcome.FAIL -> LiveOutcome.FAIL
                    Outcome.UNKNOWN -> LiveOutcome.UNKNOWN
                }
            }

            override suspend fun rollback() {
                if (startGuid != null && CoreServiceManager.isRunning() &&
                    CoreServiceManager.currentServerGuid() != startGuid && !userChangedServer()
                ) {
                    BypassLog.add("no verified server: back to ${serverName(startGuid)}")
                    ourSwitch(startGuid)
                }
            }

            override fun stillNeeded(): Boolean = alive()

            override fun log(message: String) = BypassLog.add(message)

            override fun onIsolated(id: String, result: IsoResult) {
                MmkvManager.encodeServerTestDelayMillis(id, result.pingMs)
                val meta = rated[id]
                BypassLog.add(
                    "test on the network: ${serverName(id)}${meta?.let { " [score ${it.rating.score} ${it.rating.level}]" }.orEmpty()} -> " +
                        (if (result.ok) "passed ${result.pingMs} ms" else "no answer")
                )
                if (meta != null) {
                    if (result.ok) BypassHistory.recordOk(meta.fingerprint, bucket, result.pingMs, onCellular())
                    else BypassHistory.recordFail(meta.fingerprint, bucket, onCellular())
                }
                if (!result.ok) WhitelistBypass.markBad(id)
            }

            override fun onLiveFail(id: String) {
                WhitelistBypass.markBad(id)
                rated[id]?.let { BypassHistory.recordFail(it.fingerprint, bucket, onCellular()) }
            }

            override fun onProgress(checked: Int, total: Int) = NetInfoCache.writeProgress(checked, total)
        }
        val result = BypassSearch.run(candidates, limits(testOnly), env)
        return TryResult(result.found, lastReason, result.aborted, result.tested, result.passed)
    }

    /** Return flow: the ordered [guids] (best first), no classifier and no history. */
    private suspend fun tryCandidates(service: Service, guids: List<String>, full: Boolean, stillNeeded: () -> Boolean): TryResult =
        runSearch(
            service,
            guids.mapIndexed { i, g -> SearchCandidate(g, score = guids.size - i, order = i) },
            emptyMap(), full, testOnly = false, stillNeeded = stillNeeded,
        )

    /**
     * Who may be tried on the mobile network. Auto: only servers that look like bypass servers
     * (STRONG, LIKELY); the weak ones only when the user allowed "try the rest"; never the
     * unlikely ones. Manual: what the user picked, in any case, best score first, ties by list order.
     * Servers that failed recently on this operator are left out.
     */
    private fun buildPlan(service: Service, current: String?, includeCurrent: Boolean = false): Plan {
        val now = System.currentTimeMillis()
        val bucket = BypassHistory.bucket(service)
        val manual = WhitelistBypass.mode() == WhitelistBypass.MODE_MANUAL
        val guids = if (manual) WhitelistBypass.servers() else MmkvManager.decodeAllServerList()
        val rated = guids.mapIndexedNotNull { i, g ->
            if (!includeCurrent && g == current) null else BypassRating.rate(g, bucket, now, order = if (manual) i else null)
        }
        BypassHistory.prune(MmkvManager.decodeAllServerList().mapNotNull { BypassRating.fingerprintOf(it) }.toSet())
        val usable = rated.filter { !WhitelistBypass.isBad(it.guid, now) && !BypassHistory.isBad(it.fingerprint, bucket, now) }
        fun best(list: List<BypassRating.Rated>) = list.sortedWith(compareBy({ -it.rating.score }, { it.order })).take(BYPASS_MAX_CANDIDATES)
        val plan = if (manual) {
            Plan(best(usable), emptyList(), rated.size - usable.size, rated.size)
        } else {
            Plan(
                best(usable.filter { BypassRating.looksLikeBypass(it.rating) }),
                best(usable.filter { it.rating.level == BypassLevel.WEAK }),
                rated.size - usable.size, rated.size,
            )
        }
        val by = rated.groupingBy { it.rating.level }.eachCount()
        BypassLog.add(
            "candidates (${if (manual) "manual" else "auto"}): ${plan.tier1.size} first choice, ${plan.tier2.size} rest; " +
                "of ${plan.total}: strong ${by[BypassLevel.STRONG] ?: 0}, likely ${by[BypassLevel.LIKELY] ?: 0}, " +
                "weak ${by[BypassLevel.WEAK] ?: 0}, unlikely ${by[BypassLevel.UNLIKELY] ?: 0}; skipped as recently failed: ${plan.skippedBad}"
        )
        plan.tier1.take(10).forEach { BypassLog.add("  ${serverName(it.guid)}: ${it.rating.score} ${it.rating.level} (${it.rating.explain()})") }
        return plan
    }

    private fun toCandidates(list: List<BypassRating.Rated>) = list.mapIndexed { i, r -> SearchCandidate(r.guid, r.rating.score, i) }

    private fun isSimple(guid: String): Boolean = MmkvManager.decodeServerConfig(guid)?.configType?.let {
        it != EConfigType.POLICYGROUP && it != EConfigType.PROXYCHAIN
    } == true

    private fun delayOf(guid: String): Long = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L

    private suspend fun searchBypass(current: String) {
        val service = ConnectionWatchdog.currentService() ?: return
        val now = System.currentTimeMillis()
        if (now < nextSearchAt) {
            BypassLog.add("backing off for ${(nextSearchAt - now) / 1000}s")
            return
        }
        val manual = WhitelistBypass.mode() == WhitelistBypass.MODE_MANUAL
        val plan = buildPlan(service, current)
        val restAllowed = !manual && WhitelistBypass.tryRest() && plan.tier2.isNotEmpty()
        if (plan.tier1.isEmpty() && !restAllowed) {
            // Nothing to try: say so and keep the connection as it is. No silent fall back to "all servers".
            val text = when {
                manual && plan.total == 0 -> R.string.whitelist_bypass_notify_empty_manual
                plan.total > 0 && plan.skippedBad == plan.total -> R.string.whitelist_bypass_notify_all_bad
                else -> R.string.whitelist_bypass_notify_no_match
            }
            BypassLog.add("nothing to try (${service.getString(text)})")
            NetInfoCache.writeBypass(BypassState.FAIL, FailReason.NO_GSTATIC, returnToName())
            failedSearches++
            nextSearchAt = System.currentTimeMillis() + WhitelistBypass.backoffMillis(failedSearches)
            postEvent(service, "empty", service.getString(text), NOTIFY_PROBLEM_GAP_MS)
            return
        }
        NetInfoCache.writeBypass(BypassState.SEARCHING, returnTo = returnToName())
        NetInfoCache.writeProgress(0, plan.tier1.size.coerceAtLeast(plan.tier2.size))

        // One snapshot per episode, taken before the first switch: a chain of bypass servers keeps it.
        val createdSnapshot = ensureSnapshot(current)
        val stillOnCellular = { applies() }
        val rated = (plan.tier1 + plan.tier2).associateBy { it.guid }
        var result = if (plan.tier1.isNotEmpty()) {
            runSearch(service, toCandidates(plan.tier1), rated, full = true, testOnly = false, stillNeeded = stillOnCellular)
        } else {
            TryResult(null, null, aborted = false)
        }
        if (result.found == null && !result.aborted && restAllowed) {
            BypassLog.add("the first choice gave nothing: trying the rest of the servers")
            NetInfoCache.writeProgress(0, plan.tier2.size)
            result = runSearch(service, toCandidates(plan.tier2), rated, full = true, testOnly = false, stillNeeded = stillOnCellular)
        }
        if (result.found != null) {
            val guid = result.found
            WhitelistBypass.active = guid
            WhitelistBypass.recordSuccess(guid)
            failedSearches = 0
            nextSearchAt = 0L
            lastPeriodicReturnAt = System.currentTimeMillis()
            announceSwitch(service, guid)
            NetInfoCache.writeTarget(serverName(guid))
            NetInfoCache.writeBypass(BypassState.OK, returnTo = returnToName())
            BypassLog.add("connected to ${serverName(guid)}: verified")
            postEvent(service, "switched", service.getString(R.string.whitelist_bypass_notify_switched, serverName(guid)), NOTIFY_SWITCH_GAP_MS)
            return
        }
        if (result.aborted) {
            // The network changed or the user stepped in: the next event decides.
            if (createdSnapshot && WhitelistBypass.active == null) WhitelistBypass.snapshot = null
            return
        }
        // Nothing carried traffic: the search already put the connection back; wait before retrying.
        if (createdSnapshot) WhitelistBypass.snapshot = null
        failedSearches++
        val wait = WhitelistBypass.backoffMillis(failedSearches)
        nextSearchAt = System.currentTimeMillis() + wait
        BypassLog.add("no working server (${result.tested} tested, ${result.passed} passed the test), retry in ${wait / 1000}s")
        NetInfoCache.writeBypass(BypassState.FAIL, result.lastReason ?: FailReason.NO_GSTATIC, returnToName())
        postEvent(service, "not_found", service.getString(R.string.whitelist_bypass_notify_not_found), NOTIFY_PROBLEM_GAP_MS)
    }

    /**
     * "Check on the mobile network now": tests every server that could be a bypass server (and the
     * ones the user picked) in isolation and records the result in the history. Nothing is switched.
     */
    suspend fun testAll() {
        val service = ConnectionWatchdog.currentService() ?: return
        PhysicalNetwork.refreshNow()
        if (PhysicalNetwork.snapshot.type != NetType.CELLULAR) {
            BypassLog.add("test of all servers skipped: the phone is not on the mobile network")
            return
        }
        val manual = WhitelistBypass.mode() == WhitelistBypass.MODE_MANUAL
        val bucket = BypassHistory.bucket(service)
        val now = System.currentTimeMillis()
        val chosen = WhitelistBypass.servers()
        val guids = (chosen + MmkvManager.decodeAllServerList().filter { it !in chosen }).distinct()
        val rated = guids.mapIndexedNotNull { i, g -> BypassRating.rate(g, bucket, now, order = i) }
            .filter { it.guid in chosen || it.rating.level != BypassLevel.UNLIKELY }
            .sortedWith(compareBy({ -it.rating.score }, { it.order }))
            .take(TEST_ALL_MAX)
        BypassLog.add("test of all servers on the mobile network: ${rated.size} servers (${if (manual) "manual" else "auto"} mode)")
        NetInfoCache.writeBypass(BypassState.SEARCHING, returnTo = returnToName())
        NetInfoCache.writeProgress(0, rated.size)
        val result = runSearch(
            service, toCandidates(rated), rated.associateBy { it.guid },
            full = false, testOnly = true, stillNeeded = { PhysicalNetwork.snapshot.type == NetType.CELLULAR },
        )
        NetInfoCache.writeTestResult(result.passed, result.tested)
        NetInfoCache.writeBypass(if (WhitelistBypass.active != null) BypassState.OK else BypassState.IDLE, returnTo = returnToName())
        BypassLog.add("test of all servers: ${result.passed} of ${result.tested} work on this network")
    }

    /** Takes the snapshot when none exists; returns true when this call created it. */
    private fun ensureSnapshot(current: String): Boolean {
        if (!BypassSnapshot.shouldCreate(WhitelistBypass.snapshot)) return false
        val profile = MmkvManager.decodeServerConfig(current) ?: return false
        WhitelistBypass.snapshot = BypassSnapshot(
            mode = if (WhitelistBypass.isBestSelection(current)) BypassSnapshot.MODE_BEST else BypassSnapshot.MODE_SERVER,
            guid = current,
            name = profile.remarks,
            addr = "${profile.server.orEmpty()}:${profile.serverPort.orEmpty()}",
            country = ServerCountry.code(profile.remarks).orEmpty(),
            netType = PhysicalNetwork.snapshot.type.id,
            at = System.currentTimeMillis(),
        )
        return true
    }

    // ------------------------------------------------------------------
    // Return
    // ------------------------------------------------------------------

    private fun returnToName(): String? {
        val snap = WhitelistBypass.snapshot ?: return null
        return if (snap.mode == BypassSnapshot.MODE_BEST) NetInfoCache.RETURN_BEST else snap.name.ifBlank { null }
    }

    /**
     * The phone is on Wi-Fi/Ethernet (stable, see [scheduleReturn]). If the server from the
     * snapshot is fine, go back to it; if it is "very bad", find a replacement, never a Russian
     * one; for a "Best" snapshot pick a new best the same way.
     */
    private suspend fun returnToNormalNetwork() {
        if (!WhitelistBypass.isEnabled() || !WhitelistBypass.autoReturn() || userOverride) return
        val snap = WhitelistBypass.snapshot ?: return
        // The user turned the connection off: never connect on our own.
        if (!CoreServiceManager.isRunning()) return
        val net = PhysicalNetwork.snapshot
        if (!net.type.isLan || net.captive) return
        if (System.currentTimeMillis() < nextSearchAt) return
        if (!lanHasInternet(net)) {
            BypassLog.add("the normal network has no internet yet, staying on the current server")
            return
        }
        val service = ConnectionWatchdog.currentService() ?: return
        val current = CoreServiceManager.currentServerGuid() ?: return
        val origin = resolveOrigin(snap)
        if (origin == current) {
            WhitelistBypass.clearState()
            return
        }

        val originState = measureOrigin(service, origin)
        val action = ReturnLogic.decide(snap.mode, currentIsOrigin = false, origin = originState, limitMs = WhitelistBypass.pingLimitMs(), allowReplacement = true)
        BypassLog.add("back on the normal network, action $action")
        val stillNeeded = { PhysicalNetwork.snapshot.type.isLan && !userOverride && WhitelistBypass.isEnabled() }
        when (action) {
            ReturnAction.STAY -> Unit
            ReturnAction.RETURN -> {
                val verdict = switchAndVerify(origin!!, stillNeeded)
                when (verdict.outcome) {
                    Outcome.OK -> finishReturn(service, origin, replaced = false)
                    Outcome.UNKNOWN -> Unit
                    Outcome.FAIL -> {
                        WhitelistBypass.markBad(origin)
                        replaceOrigin(service, snap, current, origin, bestMode = false, stillNeeded = stillNeeded)
                    }
                }
            }
            ReturnAction.SEARCH_REPLACEMENT -> replaceOrigin(service, snap, current, origin, bestMode = false, stillNeeded = stillNeeded)
            ReturnAction.RUN_BEST -> replaceOrigin(service, snap, current, origin, bestMode = true, stillNeeded = stillNeeded)
        }
    }

    private suspend fun switchAndVerify(guid: String, stillNeeded: () -> Boolean): Verdict {
        if (!ourSwitch(guid)) return Verdict(Outcome.UNKNOWN)
        delay(VERIFY_DELAY_MS)
        return verifyAfterSwitch(full = false) { CoreServiceManager.isRunning() && stillNeeded() }
    }

    private suspend fun replaceOrigin(
        service: Service,
        snap: BypassSnapshot,
        current: String,
        origin: String?,
        bestMode: Boolean,
        stillNeeded: () -> Boolean,
    ) {
        val subId = origin?.let { MmkvManager.decodeServerConfig(it)?.subscriptionId }
            ?: MmkvManager.decodeServerConfig(current)?.subscriptionId
        val pool = subId?.let { MmkvManager.decodeServerList(it) }?.takeIf { it.isNotEmpty() } ?: MmkvManager.decodeAllServerList()
        val now = System.currentTimeMillis()
        val favorites = if (bestMode) emptySet() else FavoriteServers.all()
        val candidates = ReturnLogic.orderReplacement(
            guids = pool.filter { it != origin && isSimple(it) && !WhitelistBypass.isBad(it, now) },
            favorites = favorites,
            sameCountry = { !bestMode && snap.country.isNotEmpty() && ServerCountry.code(MmkvManager.decodeServerConfig(it)?.remarks) == snap.country },
            isRussian = { ServerCountry.isRussian(MmkvManager.decodeServerConfig(it)?.remarks) },
            knownDelay = { delayOf(it) },
            excludeRussia = true, // a replacement on a normal network is never a Russian server
        ).take(RETURN_MAX_CANDIDATES)

        NetInfoCache.writeBypass(BypassState.SEARCHING, returnTo = returnToName())
        val result = tryCandidates(service, candidates, full = false, stillNeeded = stillNeeded)
        if (result.found != null) {
            finishReturn(service, result.found, replaced = !bestMode)
            return
        }
        if (result.aborted) return
        // Back to the server that worked before this search (a failed candidate must not stay active).
        if (CoreServiceManager.currentServerGuid() != current) ourSwitch(current)
        failedSearches++
        val wait = WhitelistBypass.backoffMillis(failedSearches)
        nextSearchAt = System.currentTimeMillis() + wait
        BypassLog.add("no replacement found on the normal network, retry in ${wait / 1000}s")
        NetInfoCache.writeBypass(BypassState.FAIL, result.lastReason ?: FailReason.NO_GSTATIC, returnToName())
        postEvent(service, "not_found_lan", service.getString(R.string.whitelist_bypass_notify_not_found), NOTIFY_PROBLEM_GAP_MS)
    }

    private fun finishReturn(service: Service, guid: String, replaced: Boolean) {
        WhitelistBypass.clearState()
        failedSearches = 0
        nextSearchAt = 0L
        announceSwitch(service, guid)
        NetInfoCache.writeBypass(BypassState.IDLE)
        val text = service.getString(
            if (replaced) R.string.bypass_notify_origin_bad else R.string.bypass_notify_wifi_returned,
            serverName(guid)
        )
        postEvent(service, if (replaced) "replaced" else "returned", text, NOTIFY_SWITCH_GAP_MS)
    }

    /** Wi-Fi (or a normal network) joined: nothing to do on mobile, otherwise the return flow runs. */
    private suspend fun maybeReturnPeriodically() {
        if (WhitelistBypass.snapshot == null || WhitelistBypass.active == null || userOverride || !WhitelistBypass.autoReturn()) return
        val now = System.currentTimeMillis()
        if (now - lastPeriodicReturnAt < RETURN_CHECK_INTERVAL_MS) return
        lastPeriodicReturnAt = now
        if (PhysicalNetwork.snapshot.type.isLan) {
            returnToNormalNetwork()
        } else {
            returnOnlyIfOriginWorks()
        }
    }

    /** On the mobile network the only reason to go back is that the old server works again. */
    private suspend fun returnOnlyIfOriginWorks(): Boolean {
        val snap = WhitelistBypass.snapshot ?: return false
        val service = ConnectionWatchdog.currentService() ?: return false
        val current = CoreServiceManager.currentServerGuid() ?: return false
        val origin = resolveOrigin(snap) ?: return false
        if (origin == current) return false
        val action = ReturnLogic.decide(BypassSnapshot.MODE_SERVER, false, measureOrigin(service, origin), WhitelistBypass.pingLimitMs(), allowReplacement = false)
        if (action != ReturnAction.RETURN) return false
        val verdict = switchAndVerify(origin) { PhysicalNetwork.snapshot.type != NetType.NONE }
        if (verdict.outcome == Outcome.OK) {
            finishReturn(service, origin, replaced = false)
            return true
        }
        // The old server did not hold: back to the bypass server that worked.
        WhitelistBypass.active?.let { if (it != current) ourSwitch(it) }
        return false
    }

    /** The server of the snapshot in the current lists: by GUID, else by name and address (after a subscription update). */
    private fun resolveOrigin(snap: BypassSnapshot): String? {
        if (MmkvManager.decodeServerConfig(snap.guid) != null) return snap.guid
        return MmkvManager.decodeAllServerList().firstOrNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@firstOrNull false
            profile.remarks == snap.name && "${profile.server.orEmpty()}:${profile.serverPort.orEmpty()}" == snap.addr
        }
    }

    /**
     * Two tests at most: one that passes decides (with its delay); two timeouts in a row make the
     * server "dead". The test is the same real request through the server as the list check
     * (gstatic 204), run in a separate core instance, so it does not disturb the live connection.
     */
    private suspend fun measureOrigin(service: Service, guid: String?): OriginState {
        if (guid == null || MmkvManager.decodeServerConfig(guid) == null) return OriginState(exists = false, alive = false, pingMs = 0)
        suspend fun once(): Long = withContext(Dispatchers.IO) {
            runCatching {
                RealPingExecutionLimiter.run(SpeedtestConfig.limiterType(guid)) { SpeedtestConfig.measure(service, guid) }
            }.getOrDefault(-1L)
        }
        var ping = once()
        if (ping <= 0) ping = once()
        if (ping > 0) MmkvManager.encodeServerTestDelayMillis(guid, ping)
        return OriginState(exists = true, alive = ping > 0, pingMs = ping.coerceAtLeast(0))
    }

    // ------------------------------------------------------------------
    // The user steps in
    // ------------------------------------------------------------------

    /** The user picked another server while we were on a bypass one: stop managing until the network changes. */
    fun detectUserChoice() {
        if (switching) return
        val active = WhitelistBypass.active ?: return
        val current = CoreServiceManager.currentServerGuid()
        if (current != null && current != active) {
            BypassLog.add("the user picked another server, the episode is over")
            WhitelistBypass.clearState()
            userOverride = true
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun serverName(guid: String): String = MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()

    private fun announceSwitch(service: Service, guid: String) {
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_SERVER_SWITCHED, guid)
        WidgetProvider.refresh(service)
        scheduleIdentity(delayMs = 1_500L, force = true)
    }

    private fun postEvent(service: Service, event: String, text: String, minGapMs: Long) {
        val now = System.currentTimeMillis()
        synchronized(lastNotified) {
            if (now - (lastNotified[event] ?: 0L) < minGapMs) return
            lastNotified[event] = now
        }
        ConnectionWatchdog.postNotification(service, ConnectionWatchdog.BYPASS_NOTIFICATION_ID, service.getString(R.string.whitelist_bypass_notify_title), text)
    }
}
