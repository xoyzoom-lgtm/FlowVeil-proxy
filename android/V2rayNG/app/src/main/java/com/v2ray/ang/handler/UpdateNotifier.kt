package com.v2ray.ang.handler

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.net.CheckThrottle
import com.v2ray.ang.net.NotifyPolicy
import com.v2ray.ang.net.NotifyState
import com.v2ray.ang.net.ReleaseNotes
import com.v2ray.ang.net.UpdateCandidate
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Tells the user about a new FlowVeil build without nagging: a check every ~12 h in the background (and when the app opens, at
 * most every 6 h), one quiet notification per version, one reminder after 3 days, "Later" and "Skip this version".
 * The decisions are the pure [NotifyPolicy]/[CheckThrottle]. It is the only request the app makes on its own, only to the GitHub
 * releases API, without any device or account identifier; the switch in the update screen turns it off. Nothing is installed
 * without the user pressing "Update".
 */
object UpdateNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val WORK_NAME = "flowveil_update_check"
    private const val KEY_STATE = "update_notify_state"
    private const val KEY_LAST_ATTEMPT = "update_last_attempt"
    private const val KEY_FAILS = "update_check_failures"
    private const val KEY_ETAG = "update_releases_etag"
    private const val KEY_CANDIDATE = "update_candidate"
    private const val NOTIFICATION_ID = 7301

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_UPDATE_NOTIFY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_UPDATE_NOTIFY, enabled)
        if (enabled) schedule(context) else RemoteWorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Keeps one periodic check (~12 h, only with a network); does nothing when the switch is off. */
    fun schedule(context: Context) {
        if (!isEnabled()) return
        val request = PeriodicWorkRequestBuilder<CheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        RemoteWorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun state(): NotifyState = NotifyState.decode(MmkvManager.decodeSettingsString(KEY_STATE))

    private fun saveState(state: NotifyState) {
        MmkvManager.encodeSettings(KEY_STATE, state.encode())
    }

    /** The newest known update for this device, or null (also when the running build already is that new). */
    fun cached(): UpdateCandidate? =
        MmkvManager.decodeSettingsString(KEY_CANDIDATE)?.takeIf { it.isNotBlank() }
            ?.let { JsonUtil.fromJsonSafe(it, UpdateCandidate::class.java) }
            ?.takeIf { it.build > BuildConfig.HUPP_BUILD }

    /** The update to show in the banner: newer, not skipped, not snoozed. */
    fun bannerCandidate(now: Long = System.currentTimeMillis()): UpdateCandidate? {
        if (!isEnabled()) return null
        val candidate = cached() ?: return null
        return candidate.takeIf { NotifyPolicy.bannerVisible(state(), it.build, BuildConfig.HUPP_BUILD, now) }
    }

    fun remember(candidate: UpdateCandidate?, etag: String?) {
        MmkvManager.encodeSettings(KEY_CANDIDATE, candidate?.let { JsonUtil.toJson(it) } ?: "")
        if (etag != null) MmkvManager.encodeSettings(KEY_ETAG, etag)
    }

    fun later() = saveState(NotifyPolicy.afterLater(state(), System.currentTimeMillis()))

    fun skip(build: Int) = saveState(NotifyPolicy.afterSkip(state(), build))

    /** Asks GitHub when it is time (or [force]) and notifies when the policy says so. Silent on any failure. */
    suspend fun checkIfDue(context: Context, force: Boolean = false): UpdateCandidate? = withContext(Dispatchers.IO) {
        if (!isEnabled() && !force) return@withContext null
        val now = System.currentTimeMillis()
        val failures = MmkvManager.decodeSettingsInt(KEY_FAILS, 0)
        if (!force && !CheckThrottle.mayCheck(MmkvManager.decodeSettingsLong(KEY_LAST_ATTEMPT, 0L), failures, now)) return@withContext cached()
        MmkvManager.encodeSettings(KEY_LAST_ATTEMPT, now)
        val candidate = when (val fetched = UpdateCheckerManager.fetchReleases(MmkvManager.decodeSettingsString(KEY_ETAG))) {
            is UpdateCheckerManager.Fetched.Ok -> {
                MmkvManager.encodeSettings(KEY_FAILS, 0)
                UpdateCheckerManager.candidateFrom(fetched.json).also { remember(it, fetched.etag) }
            }
            UpdateCheckerManager.Fetched.NotModified -> {
                MmkvManager.encodeSettings(KEY_FAILS, 0)
                cached()
            }
            UpdateCheckerManager.Fetched.Failed -> {
                MmkvManager.encodeSettings(KEY_FAILS, failures + 1)
                LogUtil.d(AppConfig.TAG, "Update check failed quietly (failures=${failures + 1})")
                return@withContext cached()
            }
        }
        if (candidate != null) notifyIfNeeded(context, candidate, now)
        candidate
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notifyIfNeeded(context: Context, candidate: UpdateCandidate, now: Long) {
        val state = state()
        if (!NotifyPolicy.shouldNotify(state, candidate.build, BuildConfig.HUPP_BUILD, now)) return
        // Without the permission the banner in the app still shows it; do not mark it as notified.
        if (!canNotify(context)) return
        runCatching {
            NotificationHelper.ensureNotificationChannel(
                context = context,
                channelId = CHANNEL_ID,
                channelNameRes = R.string.notification_channel_app_updates,
                importance = NotificationManager.IMPORTANCE_LOW,
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, CheckUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val firstLine = ReleaseNotes.plain(candidate.notes, 140).lineSequence().map { it.trim('•', ' ') }.firstOrNull { it.isNotBlank() }
                ?: context.getString(R.string.update_notify_text_fallback)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_fv)
                .setColor(0xFF106B7E.toInt())
                .setContentTitle(context.getString(R.string.update_notify_title, "build-${candidate.build}"))
                .setContentText(firstLine)
                .setStyle(NotificationCompat.BigTextStyle().bigText(firstLine))
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
            saveState(NotifyPolicy.afterNotified(state, candidate.build, now))
        }.onFailure { LogUtil.e(AppConfig.TAG, "Update notification failed", it) }
    }

    class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            checkIfDue(applicationContext)
            return Result.success()
        }
    }
}
