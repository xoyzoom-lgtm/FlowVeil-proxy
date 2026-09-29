package com.v2ray.ang.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.FavoriteServers
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServerCountry
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.handler.WhitelistBypass.Diagnosis
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.service.RealPingExecutionLimiter
import com.v2ray.ang.service.SpeedtestConfig
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the connection alive: while connected (and the screen is on) it checks the current
 * server every [CHECK_INTERVAL_MS]. A failed check is repeated after [RECHECK_DELAY_MS]; if it
 * fails again while the phone itself is online, FlowVeil switches to the fastest working server
 * of the same subscription (never one in Russia) without turning the connection off, and tells
 * the user with a notification.
 *
 * Whitelist bypass (off by default, see [WhitelistBypass]): on a mobile network whose operator
 * only lets whitelisted resources through, the current server fails while Russian sites still
 * answer. Then a working "bypass" server is searched (Russian servers allowed here) and used; when
 * the phone moves to Wi-Fi or the original server works again, it switches back.
 *
 * All switching (failover, bypass, return) happens under [switchLock], so two mechanisms never
 * switch at the same time, and at most one bypass search runs.
 */
object ConnectionWatchdog {
    private const val CHECK_INTERVAL_MS = 30_000L
    private const val RECHECK_DELAY_MS = 5_000L
    private const val MAX_CANDIDATES = 12
    private const val PARALLEL = 6
    private const val CHANNEL_ID = "connection_switch"
    private const val NOTIFICATION_ID = 7301
    private const val BYPASS_NOTIFICATION_ID = 7302

    private const val NETWORK_DEBOUNCE_MS = 2_500L
    private const val BYPASS_PARALLEL = 8
    private const val BYPASS_MAX_CANDIDATES = 48
    private const val RETURN_CHECK_INTERVAL_MS = 150_000L
    private const val VERIFY_DELAY_MS = 3_000L
    private const val PROBE_TIMEOUT_MS = 4_000
    private const val NOTIFY_SWITCH_GAP_MS = 5 * 60_000L
    private const val NOTIFY_PROBLEM_GAP_MS = 30 * 60_000L

    private val DOMESTIC_PROBES = listOf("https://ya.ru", "https://vk.com", "https://mail.ru")
    private val FOREIGN_PROBES = listOf("https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var serviceRef: WeakReference<Service>? = null

    private val switchLock = Mutex()

    enum class NetType { NONE, CELLULAR, WIFI }

    @Volatile
    private var netType = NetType.NONE
    @Volatile
    private var cellularNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null
    private var bypassEventJob: Job? = null

    /** The user picked a server during bypass: leave it alone until the network type changes. */
    @Volatile
    private var userOverride = false
    private var failedSearches = 0
    private var nextSearchAt = 0L
    private var lastReturnCheckAt = 0L
    private val lastNotified = HashMap<String, Long>()

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, true)

    fun start(service: Service) {
        serviceRef = WeakReference(service)
        registerNetworkCallback(service)
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                if (!CoreServiceManager.isRunning() || !isScreenOn()) continue
                val bypassOn = WhitelistBypass.isEnabled()
                if (!isEnabled() && !bypassOn) continue
                switchLock.withLock {
                    if (bypassOn) {
                        detectUserChoice()
                        maybeReturnPeriodically()
                    }
                    if (currentServerWorks()) return@withLock
                    delay(RECHECK_DELAY_MS)
                    if (!CoreServiceManager.isRunning() || currentServerWorks()) return@withLock

                    if (bypassApplies()) {
                        handleFailureOnCellular(allowFailover = true)
                        return@withLock
                    }
                    if (!isEnabled()) return@withLock
                    if (!deviceOnline()) {
                        LogUtil.w(AppConfig.TAG, "Watchdog: server check failed but the phone is offline, not switching")
                        return@withLock
                    }
                    LogUtil.w(AppConfig.TAG, "Watchdog: current server failed twice, switching")
                    failover()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        debounceJob?.cancel()
        debounceJob = null
        bypassEventJob?.cancel()
        bypassEventJob = null
        unregisterNetworkCallback()
    }

    /** True while reloading too (0 = not measured), so a reload never triggers a switch. */
    private fun currentServerWorks(): Boolean = CoreServiceManager.measureCurrentDelay() >= 0L

    private fun isScreenOn(): Boolean {
        val service = serviceRef?.get() ?: return false
        val pm = service.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /**
     * The app is excluded from its own tunnel, so direct connections show whether the phone
     * itself is online. Domestic hosts first: foreign DNS addresses are often blocked in Russia.
     */
    private fun deviceOnline(): Boolean =
        listOf("ya.ru" to 443, "vk.com" to 443, "mail.ru" to 443, "1.1.1.1" to 443, "8.8.8.8" to 443)
            .any { (host, port) -> runCatching { SpeedtestManager.socketConnectTime(host, port, 3000) >= 0 }.getOrDefault(false) }

    private suspend fun failover() {
        val service = serviceRef?.get() ?: return
        val current = CoreServiceManager.currentServerGuid() ?: return
        val subId = MmkvManager.decodeServerConfig(current)?.subscriptionId ?: return
        // Servers that tested fastest before come first, then untested ones in list order.
        val candidates = MmkvManager.decodeServerList(subId)
            .filter { it != current }
            .filterNot { ServerCountry.isRussian(MmkvManager.decodeServerConfig(it)?.remarks) }
            .map { it to (MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L) }
            .sortedWith(compareBy({ if (it.second > 0) 0 else 1 }, { if (it.second > 0) it.second else 0L }))
            .map { it.first }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return

        // Test in parallel batches and take the fastest working server of the first batch that has one.
        for (batch in candidates.chunked(PARALLEL)) {
            if (!CoreServiceManager.isRunning()) return
            val results = coroutineScope {
                batch.map { guid -> async { guid to runCatching { SpeedtestConfig.measure(service, guid) }.getOrDefault(-1L) } }.awaitAll()
            }
            results.forEach { (guid, delay) -> MmkvManager.encodeServerTestDelayMillis(guid, delay) }
            val best = results.filter { it.second > 0 }.minByOrNull { it.second } ?: continue
            LogUtil.i(AppConfig.TAG, "Watchdog: switching to ${best.first} (${best.second} ms)")
            if (CoreServiceManager.switchServer(best.first)) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_SERVER_SWITCHED, best.first)
                WidgetProvider.refresh(service)
                notifySwitched(service, current, best.first)
            }
            return
        }
        LogUtil.w(AppConfig.TAG, "Watchdog: no working server to switch to")
    }

    // ------------------------------------------------------------------
    // Whitelist bypass
    // ------------------------------------------------------------------

    private fun bypassApplies(): Boolean =
        WhitelistBypass.isEnabled() && netType == NetType.CELLULAR && !userOverride

    /**
     * The current server failed twice on a mobile network. Decide whether it is a whitelist
     * (search a bypass server), a dead server (normal failover, or back to the original server
     * when we are on a bypass one) or no network at all (do nothing).
     */
    private suspend fun handleFailureOnCellular(allowFailover: Boolean) {
        val current = CoreServiceManager.currentServerGuid() ?: return
        val manual = WhitelistBypass.mode() == WhitelistBypass.MODE_MANUAL
        val onOurBypass = WhitelistBypass.active == current
        // A bypass server the user picked himself is his choice: do not move him off it.
        if (!onOurBypass && manual && current in WhitelistBypass.servers()) {
            LogUtil.i(AppConfig.TAG, "Watchdog/bypass: current server is a user-chosen bypass server, leaving it")
            return
        }

        val diagnosis = diagnoseNow(viaProxy = false)
        when (diagnosis) {
            Diagnosis.OK -> Unit
            Diagnosis.NO_NETWORK -> LogUtil.w(AppConfig.TAG, "Watchdog/bypass: no network at all, not switching")
            Diagnosis.WHITELIST -> searchBypass(current)
            Diagnosis.SERVER_DOWN -> {
                // The open internet works again: a bypass server is no longer needed.
                val origin = WhitelistBypass.origin
                if (onOurBypass && origin != null && WhitelistBypass.autoReturn()) {
                    returnToOrigin(reason = "server down, no whitelist")
                } else if (allowFailover && isEnabled()) {
                    if (onOurBypass) WhitelistBypass.clearState()
                    failover()
                }
            }
        }
    }

    /** Runs the direct probes over the mobile network and stores the result for developer mode. */
    private suspend fun diagnoseNow(viaProxy: Boolean): Diagnosis {
        val network = cellularNetwork
        val (domestic, foreign) = coroutineScope {
            val d = async { anyReachable(network, DOMESTIC_PROBES) }
            val f = async { anyReachable(network, FOREIGN_PROBES) }
            d.await() to f.await()
        }
        val diagnosis = WhitelistBypass.diagnose(viaProxy, domestic, foreign)
        val text = "${diagnosis.name}: proxy=$viaProxy ru-direct=$domestic foreign-direct=$foreign @${System.currentTimeMillis()}"
        WhitelistBypass.lastDiagnosis = text
        LogUtil.i(AppConfig.TAG, "Watchdog/bypass: $text")
        return diagnosis
    }

    private suspend fun anyReachable(network: Network?, urls: List<String>): Boolean = coroutineScope {
        urls.map { url -> async { httpReachable(network, url) } }.awaitAll().any { it }
    }

    /**
     * A real HTTPS request (TLS with SNI, not a bare TCP connect: whitelists often let the
     * handshake start and cut it by SNI) bound to the mobile network, so it never goes through
     * our own tunnel whatever the per-app settings are.
     */
    private fun httpReachable(network: Network?, url: String): Boolean = try {
        val target = URL(url)
        val conn = (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
        conn.connectTimeout = PROBE_TIMEOUT_MS
        conn.readTimeout = PROBE_TIMEOUT_MS
        conn.instanceFollowRedirects = false
        conn.useCaches = false
        conn.requestMethod = "HEAD"
        try {
            conn.responseCode > 0
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        false
    }

    private fun bypassCandidates(current: String): List<String> {
        if (WhitelistBypass.mode() == WhitelistBypass.MODE_MANUAL) {
            return WhitelistBypass.servers().filter { it != current }
        }
        // Automatic: every server (Russian ones included: under a whitelist they are often the
        // only ones that work). Complex profiles (groups/chains) are skipped.
        val all = MmkvManager.decodeAllServerList().filter { guid ->
            guid != current && MmkvManager.decodeServerConfig(guid)?.configType?.let {
                it != EConfigType.POLICYGROUP && it != EConfigType.PROXYCHAIN
            } == true
        }
        return WhitelistBypass.orderCandidates(
            all,
            FavoriteServers.all(),
            WhitelistBypass.recentSuccess(),
        ) { MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L }
            .take(BYPASS_MAX_CANDIDATES)
    }

    private suspend fun searchBypass(current: String) {
        val service = serviceRef?.get() ?: return
        val now = System.currentTimeMillis()
        if (now < nextSearchAt) {
            LogUtil.i(AppConfig.TAG, "Watchdog/bypass: backing off for ${(nextSearchAt - now) / 1000}s")
            return
        }
        val candidates = bypassCandidates(current)
        if (candidates.isEmpty()) {
            val manual = WhitelistBypass.mode() == WhitelistBypass.MODE_MANUAL
            notifyBypass(
                service, "empty",
                service.getString(R.string.whitelist_bypass_notify_title),
                service.getString(if (manual) R.string.whitelist_bypass_notify_empty_manual else R.string.whitelist_bypass_notify_not_found),
                NOTIFY_PROBLEM_GAP_MS,
            )
            return
        }
        LogUtil.i(AppConfig.TAG, "Watchdog/bypass: whitelist detected, testing ${candidates.size} servers")

        for (batch in candidates.chunked(BYPASS_PARALLEL)) {
            currentCoroutineContext().ensureActive()
            if (!CoreServiceManager.isRunning() || !bypassApplies()) return
            val results = coroutineScope {
                batch.map { guid ->
                    async {
                        guid to runCatching {
                            RealPingExecutionLimiter.run(SpeedtestConfig.limiterType(guid)) { SpeedtestConfig.measure(service, guid) }
                        }.getOrDefault(-1L)
                    }
                }.awaitAll()
            }
            results.forEach { (guid, d) -> MmkvManager.encodeServerTestDelayMillis(guid, d) }
            // Fastest first; a server that tests fine but fails once in use is skipped.
            for ((guid, d) in results.filter { it.second > 0 }.sortedBy { it.second }) {
                if (!bypassApplies()) return
                val alreadyBypassing = WhitelistBypass.active == current
                if (!CoreServiceManager.switchServer(guid)) continue
                delay(VERIFY_DELAY_MS)
                if (CoreServiceManager.measureCurrentDelay() > 0) {
                    if (!alreadyBypassing) WhitelistBypass.origin = current
                    WhitelistBypass.active = guid
                    WhitelistBypass.recordSuccess(guid)
                    failedSearches = 0
                    nextSearchAt = 0L
                    lastReturnCheckAt = System.currentTimeMillis()
                    LogUtil.i(AppConfig.TAG, "Watchdog/bypass: switched to $guid ($d ms)")
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_SERVER_SWITCHED, guid)
                    WidgetProvider.refresh(service)
                    notifyBypass(
                        service, "switched",
                        service.getString(R.string.whitelist_bypass_notify_title),
                        service.getString(R.string.whitelist_bypass_notify_switched, serverName(guid)),
                        NOTIFY_SWITCH_GAP_MS,
                    )
                    return
                }
                LogUtil.w(AppConfig.TAG, "Watchdog/bypass: $guid tested fine but fails in use, next")
            }
        }

        // Nothing worked: go back to where we were (the tunnel stays up) and wait before retrying.
        if (CoreServiceManager.currentServerGuid() != current) CoreServiceManager.switchServer(current)
        failedSearches++
        nextSearchAt = System.currentTimeMillis() + WhitelistBypass.backoffMillis(failedSearches)
        LogUtil.w(AppConfig.TAG, "Watchdog/bypass: no working bypass server, retry in ${WhitelistBypass.backoffMillis(failedSearches) / 1000}s")
        notifyBypass(
            service, "not_found",
            service.getString(R.string.whitelist_bypass_notify_title),
            service.getString(R.string.whitelist_bypass_notify_not_found),
            NOTIFY_PROBLEM_GAP_MS,
        )
    }

    /** While on a bypass server, try the original one every few minutes (screen on only). */
    private suspend fun maybeReturnPeriodically() {
        val origin = WhitelistBypass.origin ?: return
        if (WhitelistBypass.active == null || userOverride || !WhitelistBypass.autoReturn()) return
        val now = System.currentTimeMillis()
        if (now - lastReturnCheckAt < RETURN_CHECK_INTERVAL_MS) return
        lastReturnCheckAt = now
        val service = serviceRef?.get() ?: return
        if (MmkvManager.decodeServerConfig(origin) == null) {
            WhitelistBypass.clearState()
            return
        }
        val originDelay = runCatching {
            RealPingExecutionLimiter.run(SpeedtestConfig.limiterType(origin)) { SpeedtestConfig.measure(service, origin) }
        }.getOrDefault(-1L)
        if (originDelay > 0) returnToOrigin(reason = "original server works again")
    }

    private fun returnToOrigin(reason: String) {
        val service = serviceRef?.get() ?: return
        val origin = WhitelistBypass.origin ?: return
        WhitelistBypass.clearState()
        if (MmkvManager.decodeServerConfig(origin) == null || !CoreServiceManager.isRunning()) return
        if (CoreServiceManager.currentServerGuid() == origin) return
        LogUtil.i(AppConfig.TAG, "Watchdog/bypass: returning to $origin ($reason)")
        if (CoreServiceManager.switchServer(origin)) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_SERVER_SWITCHED, origin)
            WidgetProvider.refresh(service)
            notifyBypass(
                service, "returned",
                service.getString(R.string.whitelist_bypass_notify_title),
                service.getString(R.string.whitelist_bypass_notify_returned, serverName(origin)),
                NOTIFY_SWITCH_GAP_MS,
            )
        }
    }

    /** The user switched servers while we were on a bypass one: stop managing until the network changes. */
    private fun detectUserChoice() {
        val active = WhitelistBypass.active ?: return
        if (CoreServiceManager.currentServerGuid() != active) {
            LogUtil.i(AppConfig.TAG, "Watchdog/bypass: user picked another server, pausing auto switching")
            WhitelistBypass.clearState()
            userOverride = true
        }
    }

    /** Debounced network type change (Wi-Fi <-> mobile). */
    private fun onNetworkTypeChanged(old: NetType, new: NetType) {
        LogUtil.i(AppConfig.TAG, "Watchdog/bypass: network $old -> $new")
        userOverride = false
        failedSearches = 0
        nextSearchAt = 0L
        if (!WhitelistBypass.isEnabled()) return
        bypassEventJob?.cancel()
        bypassEventJob = scope.launch {
            switchLock.withLock {
                if (!CoreServiceManager.isRunning()) return@withLock
                if (new != NetType.CELLULAR) {
                    if (WhitelistBypass.active != null && WhitelistBypass.autoReturn()) {
                        returnToOrigin(reason = "left the mobile network")
                    }
                    return@withLock
                }
                // Just moved to mobile: check right away instead of waiting for the next tick.
                if (currentServerWorks()) return@withLock
                delay(RECHECK_DELAY_MS)
                if (!CoreServiceManager.isRunning() || currentServerWorks()) return@withLock
                handleFailureOnCellular(allowFailover = false)
            }
        }
    }

    // ------------------------------------------------------------------
    // Network type
    // ------------------------------------------------------------------

    private fun registerNetworkCallback(service: Service) {
        if (networkCallback != null) return
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        refreshNetType(cm, notify = false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleRefresh(cm)
            override fun onLost(network: Network) = scheduleRefresh(cm)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = scheduleRefresh(cm)
        }
        try {
            // A plain request excludes VPN networks (NOT_VPN is a default capability), so this
            // follows the real mobile / Wi-Fi networks, not our own tunnel.
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
            networkCallback = callback
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Watchdog: failed to watch networks", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = serviceRef?.get()?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun scheduleRefresh(cm: ConnectivityManager) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(NETWORK_DEBOUNCE_MS)
            refreshNetType(cm, notify = true)
        }
    }

    private fun refreshNetType(cm: ConnectivityManager, notify: Boolean) {
        val (type, cellular) = underlyingType(cm)
        cellularNetwork = cellular
        val old = netType
        netType = type
        if (old == type) return
        if (notify) {
            onNetworkTypeChanged(old, type)
        } else {
            // Changed while we were not watching (service restart): a fresh network, fresh start.
            userOverride = false
        }
    }

    /**
     * The network under the tunnel: the default network would be our VPN, so the non-VPN
     * networks are scanned. A validated Wi-Fi/Ethernet wins (that is what Android routes over);
     * otherwise mobile data, even unvalidated (a whitelist often fails Android's own check).
     */
    @Suppress("DEPRECATION")
    private fun underlyingType(cm: ConnectivityManager): Pair<NetType, Network?> {
        val nets = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList()).mapNotNull { n ->
            cm.getNetworkCapabilities(n)?.let { n to it }
        }.filter { (_, c) ->
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        fun NetworkCapabilities.isLan() =
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val cellular = nets.firstOrNull { it.second.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }?.first
        return when {
            nets.any { it.second.isLan() && it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } -> NetType.WIFI to null
            cellular != null -> NetType.CELLULAR to cellular
            nets.any { it.second.isLan() } -> NetType.WIFI to null
            else -> NetType.NONE to null
        }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun serverName(guid: String): String = MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()

    private fun notifyBypass(service: Service, event: String, title: String, text: String, minGapMs: Long) {
        val now = System.currentTimeMillis()
        synchronized(lastNotified) {
            val last = lastNotified[event] ?: 0L
            if (now - last < minGapMs) return
            lastNotified[event] = now
        }
        postNotification(service, BYPASS_NOTIFICATION_ID, title, text)
    }

    private fun notifySwitched(service: Service, from: String, to: String) {
        val text = service.getString(R.string.failover_notification_text, serverName(from), serverName(to))
        postNotification(service, NOTIFICATION_ID, service.getString(R.string.failover_notification_title), text)
    }

    private fun postNotification(service: Service, id: Int, title: String, text: String) {
        runCatching {
            NotificationHelper.ensureNotificationChannel(
                context = service,
                channelId = CHANNEL_ID,
                channelNameRes = R.string.notification_channel_switch,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
            )
            val open = PendingIntent.getActivity(
                service, 0,
                Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_fv)
                .setColor(0xFF106B7E.toInt())
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            (service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
        }.onFailure { LogUtil.e(AppConfig.TAG, "Watchdog notification failed", it) }
    }
}
