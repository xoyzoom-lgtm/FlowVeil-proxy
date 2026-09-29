package com.v2ray.ang.core

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServerCountry
import com.v2ray.ang.handler.SpeedtestManager
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Keeps the connection alive: while connected (and the screen is on) it checks the current
 * server every [CHECK_INTERVAL_MS]. A failed check is repeated after [RECHECK_DELAY_MS]; if it
 * fails again while the phone itself is online, FlowVeil switches to the fastest working server
 * of the same subscription (never one in Russia) without turning the connection off, and tells
 * the user with a notification.
 */
object ConnectionWatchdog {
    private const val CHECK_INTERVAL_MS = 30_000L
    private const val RECHECK_DELAY_MS = 5_000L
    private const val MAX_CANDIDATES = 12
    private const val PARALLEL = 6
    private const val CHANNEL_ID = "connection_switch"
    private const val NOTIFICATION_ID = 7301

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var serviceRef: WeakReference<Service>? = null

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, true)

    fun start(service: Service) {
        serviceRef = WeakReference(service)
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
                if (!isEnabled() || !CoreServiceManager.isRunning() || !isScreenOn()) continue
                if (currentServerWorks()) continue
                kotlinx.coroutines.delay(RECHECK_DELAY_MS)
                if (!CoreServiceManager.isRunning() || currentServerWorks()) continue
                if (!deviceOnline()) {
                    LogUtil.w(AppConfig.TAG, "Watchdog: server check failed but the phone is offline, not switching")
                    continue
                }
                LogUtil.w(AppConfig.TAG, "Watchdog: current server failed twice, switching")
                failover()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
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

    private fun notifySwitched(service: Service, from: String, to: String) {
        runCatching {
            val fromName = MmkvManager.decodeServerConfig(from)?.remarks.orEmpty()
            val toName = MmkvManager.decodeServerConfig(to)?.remarks.orEmpty()
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
            val text = service.getString(R.string.failover_notification_text, fromName, toName)
            val notification = NotificationCompat.Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_fv)
                .setColor(0xFF106B7E.toInt())
                .setContentTitle(service.getString(R.string.failover_notification_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            (service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        }.onFailure { LogUtil.e(AppConfig.TAG, "Watchdog notification failed", it) }
    }
}
