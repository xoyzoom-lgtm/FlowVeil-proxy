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
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.util.LogUtil

/**
 * Warns before a subscription runs out: 3 days and 1 day before it expires, when it expired,
 * and when 90% / 100% of the traffic is used. Each warning is shown once per subscription
 * period; a renewal (new expiry date or traffic limit) re-arms them.
 */
object SubscriptionReminders {
    private const val CHANNEL_ID = "subscription_reminders"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    fun isEnabled(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_SUB_REMINDERS, true)

    fun check(context: Context) {
        if (!isEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            val now = System.currentTimeMillis()
            MmkvManager.decodeSubscriptions().forEach { sub ->
                val item = sub.subscription
                if (item.url.isBlank() || !item.enabled) return@forEach
                val name = item.profileTitle?.takeIf { it.isNotBlank() } ?: item.remarks
                val sent = sentKeys(sub.guid)

                item.expireAt?.takeIf { it > 0 }?.let { expire ->
                    val left = expire - now
                    val level = when {
                        left <= 0 -> "exp0"
                        left <= DAY_MS -> "exp1"
                        left <= 3 * DAY_MS -> "exp3"
                        else -> null
                    }
                    val key = level?.let { "$it:$expire" }
                    if (key != null && key !in sent) {
                        val text = when (level) {
                            "exp0" -> context.getString(R.string.reminder_expired)
                            "exp1" -> context.getString(R.string.reminder_expires_tomorrow)
                            else -> context.getString(R.string.reminder_expires_days, ((left + DAY_MS - 1) / DAY_MS).toInt())
                        }
                        show(context, sub.guid, "exp", name, text)
                        markSent(sub.guid, sent + key)
                    }
                }

                val total = item.trafficTotal ?: 0L
                val used = item.trafficUsed ?: 0L
                if (total > 0) {
                    val level = when {
                        used >= total -> "traf100"
                        used * 10 >= total * 9 -> "traf90"
                        else -> null
                    }
                    val key = level?.let { "$it:$total" }
                    if (key != null && key !in sent) {
                        val text = if (level == "traf100") {
                            context.getString(R.string.reminder_traffic_over)
                        } else {
                            context.getString(R.string.reminder_traffic_left, (total - used).coerceAtLeast(0).toTrafficString())
                        }
                        show(context, sub.guid, "traf", name, text)
                        markSent(sub.guid, sent + key)
                    }
                }
            }
        }.onFailure { LogUtil.e(AppConfig.TAG, "Subscription reminders failed", it) }
    }

    private fun sentKeys(subId: String): Set<String> =
        MmkvManager.decodeSettingsString("reminders_sent_$subId").orEmpty().split('|').filter { it.isNotBlank() }.toSet()

    private fun markSent(subId: String, keys: Set<String>) {
        // Keep only the most recent entries so the list cannot grow forever.
        MmkvManager.encodeSettings("reminders_sent_$subId", keys.toList().takeLast(8).joinToString("|"))
    }

    private fun show(context: Context, subId: String, kind: String, title: String, text: String) {
        NotificationHelper.ensureNotificationChannel(
            context = context,
            channelId = CHANNEL_ID,
            channelNameRes = R.string.notification_channel_reminders,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fv)
            .setColor(0xFF106B7E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(("$subId:$kind").hashCode(), notification)
    }
}
