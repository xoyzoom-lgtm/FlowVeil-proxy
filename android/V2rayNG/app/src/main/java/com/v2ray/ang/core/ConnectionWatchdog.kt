package com.v2ray.ang.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.BypassLog
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServerCountry
import com.v2ray.ang.handler.SubscriptionErrors
import com.v2ray.ang.net.FailoverPlan
import com.v2ray.ang.net.SubIssue
import com.v2ray.ang.net.SubscriptionHealth
import com.v2ray.ang.net.SwitchResult
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.handler.WhitelistBypass
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.service.SpeedtestConfig
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * Keeps the connection alive: while connected (and the screen is on) it checks the current
 * server every [CHECK_INTERVAL_MS]. A failed check is repeated after [RECHECK_DELAY_MS]; if it
 * fails again while the phone itself is online, FlowVeil switches to the fastest working server
 * of the same subscription, then of the other usable ones (never one in Russia) without turning the connection off, and tells
 * the user with a notification.
 *
 * On a restricted mobile network (an opt-in feature, see [BypassController]) the same failure
 * leads to a search for a server that really carries traffic, and back to the usual one on Wi-Fi.
 *
 * All switching (failover, bypass, return) happens under [switchLock], so two mechanisms never
 * switch at the same time.
 */
object ConnectionWatchdog {
    private const val CHECK_INTERVAL_MS = 20_000L
    private const val RECHECK_DELAY_MS = 5_000L
    private const val CHECK_INTERVAL_CELLULAR_MS = 10_000L
    private const val RECHECK_DELAY_FAST_MS = 2_000L
    private const val SCREEN_ON_DELAY_MS = 2_000L
    private const val MAX_CANDIDATES = 12
    private const val PARALLEL = 6
    private const val CHANNEL_ID = "connection_switch"
    private const val NOTIFICATION_ID = 7301
    internal const val BYPASS_NOTIFICATION_ID = 7302

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var checkNowJob: Job? = null
    private var serviceRef: WeakReference<Service>? = null
    private var lastNoServerNoticeAt = 0L

    /** Screen on = the user is about to use the phone: check now, do not wait out the interval. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var screenReceiver: BroadcastReceiver? = null

    internal val switchLock = Mutex()

    /** On the mobile network with the switch on a restriction costs seconds, so the check comes more often (the screen is on). */
    private fun checkInterval(): Long {
        val restricted = WhitelistBypass.isEnabled() && BypassController.applies()
        return com.v2ray.ang.net.FastMode.checkInterval(
            if (restricted) CHECK_INTERVAL_CELLULAR_MS else CHECK_INTERVAL_MS,
            MmkvManager.decodeSettingsBool(AppConfig.PREF_FAST_MODE, false),
            restricted,
        )
    }

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, true)

    internal fun currentService(): Service? = serviceRef?.get()

    fun start(service: Service) {
        serviceRef = WeakReference(service)
        BypassController.start(service)
        registerScreenReceiver(service)
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                withTimeoutOrNull(checkInterval()) { wake.receive() }
                if (!CoreServiceManager.isRunning() || !isScreenOn()) continue
                val bypassOn = WhitelistBypass.isEnabled()
                if (!isEnabled() && !bypassOn) continue
                switchLock.withLock {
                    if (bypassOn) BypassController.beforeCheck()
                    if (currentServerWorks()) return@withLock
                    if (bypassOn) BypassLog.add("the current server does not answer (${serverName(CoreServiceManager.currentServerGuid().orEmpty())}), checking again in 5 s")
                    delay(if (BypassController.applies()) RECHECK_DELAY_FAST_MS else RECHECK_DELAY_MS)
                    if (!CoreServiceManager.isRunning() || currentServerWorks()) return@withLock

                    if (BypassController.applies()) {
                        // Restricted mobile network or a dead server: the controller decides.
                        if (!BypassController.handleFailure()) return@withLock
                    }
                    if (!isEnabled()) return@withLock
                    if (!deviceOnline()) {
                        BypassLog.add("the server does not answer and the phone is offline: not switching")
                        return@withLock
                    }
                    BypassLog.add("the current server failed twice: ordinary failover")
                    failover()
                }
            }
        }
    }

    private fun registerScreenReceiver(service: Service) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Give the network a moment to wake up, then check once; an old pause before a search does not hold the user up.
                BypassController.onScreenOn()
                scope.launch {
                    delay(SCREEN_ON_DELAY_MS)
                    wake.trySend(Unit)
                }
            }
        }
        runCatching {
            service.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            screenReceiver = receiver
        }
    }

    fun stop() {
        screenReceiver?.let { r -> runCatching { serviceRef?.get()?.unregisterReceiver(r) } }
        screenReceiver = null
        job?.cancel()
        job = null
        checkNowJob?.cancel()
        checkNowJob = null
        BypassController.stop()
    }

    /** The UI asked for an immediate network check (real IP, exit IP, state of the server). */
    fun requestCheckNow() {
        if (!CoreServiceManager.isRunning()) return
        checkNowJob?.cancel()
        checkNowJob = scope.launch { switchLock.withLock { BypassController.checkNow() } }
    }

    /** The UI asked to test the candidate servers on the mobile network now (nothing is switched). */
    fun requestTestAll() {
        if (!CoreServiceManager.isRunning()) return
        checkNowJob?.cancel()
        checkNowJob = scope.launch { switchLock.withLock { BypassController.testAll() } }
    }

    /** True while reloading too (0 = not measured), so a reload never triggers a switch. */
    private fun currentServerWorks(): Boolean = CoreServiceManager.measureCurrentDelay(whitelist = BypassController.applies()) >= 0L

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

    /** A subscription whose servers make sense as a replacement: on, not expired, not out of traffic, no known access problem. */
    private fun subscriptionUsable(subId: String): Boolean {
        val sub = MmkvManager.decodeSubscription(subId) ?: return subId.isEmpty() || subId == AppConfig.DEFAULT_SUBSCRIPTION_ID
        if (!sub.enabled) return false
        if (SubscriptionHealth.byInfo(sub.expireAt, sub.trafficUsed, sub.trafficTotal, System.currentTimeMillis()) != SubIssue.NONE) return false
        return when (SubscriptionErrors.issue(subId)) {
            SubIssue.EXPIRED, SubIssue.TRAFFIC_OVER, SubIssue.BLOCKED, SubIssue.DEVICE_LIMIT, SubIssue.LINK_UNKNOWN, SubIssue.ACCESS_DENIED -> false
            else -> true
        }
    }

    private suspend fun failover() {
        val service = serviceRef?.get() ?: return
        val current = CoreServiceManager.currentServerGuid() ?: return
        val currentSub = MmkvManager.decodeServerConfig(current)?.subscriptionId ?: return
        val subs = MmkvManager.decodeSubscriptions().map { it.guid }
        val all = (subs + currentSub).distinct().flatMap { subId ->
            MmkvManager.decodeServerList(subId).mapIndexed { i, guid ->
                FailoverPlan.Server(
                    guid, subId, MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty(),
                    MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L, i
                )
            }
        }
        val groups = FailoverPlan.groups(
            all, current, currentSub, subs, ::subscriptionUsable, ServerCountry::isRussian,
            // Off unless the user turns it on: a subscription from someone else's link must not get traffic silently.
            MmkvManager.decodeSettingsBool(AppConfig.PREF_FAILOVER_ACROSS_SUBS, false)
        )
        if (groups.isEmpty()) {
            BypassLog.add("failover: no candidates (the subscription has no other servers and other subscriptions are off or unusable)")
            return
        }

        // Test each group in parallel batches; take the fastest working server of the first batch that has one.
        // The current subscription is a group of its own and is tried before the others.
        for (group in groups) {
            for (batch in group.take(MAX_CANDIDATES).chunked(PARALLEL)) {
                if (!CoreServiceManager.isRunning()) return
                val results = coroutineScope {
                    batch.map { guid -> async { guid to runCatching { SpeedtestConfig.measure(service, guid) }.getOrDefault(-1L) } }.awaitAll()
                }
                results.forEach { (guid, delay) -> MmkvManager.encodeServerTestDelayMillis(guid, delay) }
                val best = results.filter { it.second > 0 }.minByOrNull { it.second } ?: continue
                LogUtil.i(AppConfig.TAG, "Watchdog: switching to ${best.first} (${best.second} ms)")
                if (BypassController.ourSwitch(best.first) == SwitchResult.OK) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_SERVER_SWITCHED, best.first)
                    WidgetProvider.refresh(service)
                    notifySwitched(service, current, best.first)
                }
                return
            }
        }
        LogUtil.w(AppConfig.TAG, "Watchdog: no working server to switch to")
        BypassLog.add("failover: none of the ${groups.sumOf { it.size }} candidates answered")
        notifyNoServer(service)
    }

    private fun notifyNoServer(service: Service) {
        val now = System.currentTimeMillis()
        if (now - lastNoServerNoticeAt < 10 * 60_000L) return
        lastNoServerNoticeAt = now
        postNotification(service, NOTIFICATION_ID, service.getString(R.string.failover_none_title), service.getString(R.string.failover_none_text))
    }

    private fun serverName(guid: String): String = MmkvManager.decodeServerConfig(guid)?.remarks.orEmpty()

    private fun notifySwitched(service: Service, from: String, to: String) {
        val text = service.getString(R.string.failover_notification_text, serverName(from), serverName(to))
        postNotification(service, NOTIFICATION_ID, service.getString(R.string.failover_notification_title), text)
    }

    internal fun postNotification(service: Service, id: Int, title: String, text: String) {
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
