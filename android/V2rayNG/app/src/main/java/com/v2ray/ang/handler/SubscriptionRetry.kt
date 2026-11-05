package com.v2ray.ang.handler

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.net.SubRetryLogic
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * A subscription link that could not be downloaded (a restricted mobile network, the provider's site is not let through) stays
 * saved and is downloaded again by itself, soon at first and then less often, until the servers arrive. The ordinary
 * periodic update would only look at it after the whole interval.
 */
object SubscriptionRetry {
    private const val KEY_SUB_ID = "subId"
    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_retry_$subId"
    private fun failKey(subId: String) = "pref_sub_retry_fail_$subId"

    /** A download of [subId] failed: plan the next try if the subscription still has no servers. */
    fun onFailed(subId: String) {
        val sub = MmkvManager.decodeSubscription(subId) ?: return
        val servers = MmkvManager.decodeServerList(subId).size
        if (!SubRetryLogic.needsRetry(sub.enabled, sub.url, servers)) {
            cancel(subId)
            return
        }
        val failures = (MmkvManager.decodeSettingsInt(failKey(subId), 0) + 1).coerceAtMost(1000)
        MmkvManager.encodeSettings(failKey(subId), failures)
        schedule(subId, SubRetryLogic.delayMs(failures - 1, kotlin.random.Random.nextDouble()))
    }

    /** The servers arrived: nothing more to try. */
    fun onDone(subId: String) {
        MmkvManager.encodeSettings(failKey(subId), 0)
        cancel(subId)
    }

    /** The app was opened (or a new link was just added): look at every empty subscription again without waiting out a pause. */
    fun kick() {
        MmkvManager.decodeSubscriptions()
            .filter { it.guid != AppConfig.DEFAULT_SUBSCRIPTION_ID }
            .filter { SubRetryLogic.needsRetry(it.subscription.enabled, it.subscription.url, MmkvManager.decodeServerList(it.guid).size) }
            .forEach { schedule(it.guid, 1_000L) }
    }

    private fun cancel(subId: String) {
        runCatching { RemoteWorkManager.getInstance(AngApplication.application).cancelUniqueWork(taskName(subId)) }
            .onFailure { LogUtil.w(AppConfig.TAG, "SubscriptionRetry: cancel failed", it) }
    }

    private fun schedule(subId: String, delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<RetryTask>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_SUB_ID to subId))
            .build()
        runCatching {
            RemoteWorkManager.getInstance(AngApplication.application)
                .enqueueUniqueWork(taskName(subId), ExistingWorkPolicy.REPLACE, request)
        }.onFailure { LogUtil.w(AppConfig.TAG, "SubscriptionRetry: schedule failed", it) }
    }

    class RetryTask(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val subId = inputData.getString(KEY_SUB_ID) ?: return Result.success()
            val sub = MmkvManager.decodeSubscriptions().firstOrNull { it.guid == subId } ?: return Result.success()
            if (!SubRetryLogic.needsRetry(sub.subscription.enabled, sub.subscription.url, MmkvManager.decodeServerList(subId).size)) {
                onDone(subId)
                return Result.success()
            }
            // The update itself calls onDone / onFailed, which plans the next step.
            withContext(Dispatchers.IO) { AngConfigManager.updateConfigViaSub(sub) }
            return Result.success()
        }
    }
}
