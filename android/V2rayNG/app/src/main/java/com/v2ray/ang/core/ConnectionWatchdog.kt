package com.v2ray.ang.core

import android.app.Service
import android.content.Context
import android.os.PowerManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.ServerCountry
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.receiver.WidgetProvider
import com.v2ray.ang.service.SpeedtestConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Keeps the connection alive: while connected it checks the current server every
 * [CHECK_INTERVAL_MS]. After [FAILS_BEFORE_SWITCH] failed checks in a row, and only while the
 * phone itself is online, it switches to the next working server of the same subscription
 * without turning the VPN off.
 */
object ConnectionWatchdog {
    private const val CHECK_INTERVAL_MS = 45_000L
    private const val FAILS_BEFORE_SWITCH = 2
    private const val MAX_CANDIDATES = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var serviceRef: WeakReference<Service>? = null

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_FAILOVER, true)

    fun start(service: Service) {
        serviceRef = WeakReference(service)
        if (job?.isActive == true) return
        job = scope.launch {
            var fails = 0
            while (isActive) {
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
                if (!isEnabled() || !CoreServiceManager.isRunning() || !isScreenOn()) {
                    fails = 0
                    continue
                }
                val delay = CoreServiceManager.measureCurrentDelay()
                when {
                    delay == 0L -> Unit // reloading, check again later
                    delay > 0 -> fails = 0
                    !deviceOnline() -> fails = 0 // no internet at all: switching would not help
                    else -> {
                        fails++
                        LogUtil.w(AppConfig.TAG, "Watchdog: current server failed check $fails/$FAILS_BEFORE_SWITCH")
                        if (fails >= FAILS_BEFORE_SWITCH) {
                            fails = 0
                            failover()
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun isScreenOn(): Boolean {
        val service = serviceRef?.get() ?: return false
        val pm = service.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /** The app is excluded from its own VPN, so a direct connect shows whether the phone is online. */
    private fun deviceOnline(): Boolean =
        listOf("1.1.1.1" to 443, "8.8.8.8" to 443, "77.88.8.8" to 443)
            .any { (host, port) -> SpeedtestManager.socketConnectTime(host, port, 3000) >= 0 }

    private fun failover() {
        val service = serviceRef?.get() ?: return
        val current = CoreServiceManager.currentServerGuid() ?: return
        val subId = MmkvManager.decodeServerConfig(current)?.subscriptionId ?: return
        // Try the servers that tested fastest before, then the untested ones, in list order.
        val candidates = MmkvManager.decodeServerList(subId)
            .filter { it != current }
            // Never fall back to a server in Russia: it would "work" but unblock nothing.
            .filterNot { ServerCountry.isRussian(MmkvManager.decodeServerConfig(it)?.remarks) }
            .map { it to (MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: 0L) }
            .sortedWith(compareBy({ if (it.second > 0) 0 else 1 }, { if (it.second > 0) it.second else 0L }))
            .map { it.first }
            .take(MAX_CANDIDATES)
        for (guid in candidates) {
            if (!CoreServiceManager.isRunning()) return
            val delay = SpeedtestConfig.measure(service, guid)
            MmkvManager.encodeServerTestDelayMillis(guid, delay)
            if (delay > 0) {
                LogUtil.i(AppConfig.TAG, "Watchdog: switching to $guid (${delay} ms)")
                if (CoreServiceManager.switchServer(guid)) {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_SERVER_SWITCHED, guid)
                    WidgetProvider.refresh(service)
                }
                return
            }
        }
        LogUtil.w(AppConfig.TAG, "Watchdog: no working server to switch to")
    }
}
