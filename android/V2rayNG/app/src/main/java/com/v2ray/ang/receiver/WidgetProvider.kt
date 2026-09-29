package com.v2ray.ang.receiver

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.compose.HappThemeManager
import com.v2ray.ang.ui.compose.toHappColor

/**
 * 1x1 home-screen button. [WidgetCardProvider] reuses this logic with a wider layout that also
 * shows the selected server. Both follow the active colour theme on Android 12+.
 */
open class WidgetProvider : AppWidgetProvider() {

    companion object {
        /** Redraws all home-screen widgets, e.g. after the colour theme or the server changed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            for (cls in listOf(WidgetProvider::class.java, WidgetCardProvider::class.java)) {
                val ids = manager.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isEmpty()) continue
                context.sendBroadcast(
                    Intent(context, cls)
                        .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                )
            }
        }
    }

    protected open val layoutRes: Int = R.layout.widget_switch

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        render(context, appWidgetManager, appWidgetIds, CoreServiceManager.isRunning())
    }

    private fun render(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray, isRunning: Boolean) {
        if (appWidgetIds.isEmpty()) return
        val views = RemoteViews(context.packageName, layoutRes)

        // The power button toggles the VPN; on the wide card the rest opens the app.
        val toggle = PendingIntent.getBroadcast(
            context,
            layoutRes,
            Intent(context, javaClass).setAction(AppConfig.BROADCAST_ACTION_WIDGET_CLICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.layout_background, toggle)
        if (layoutRes == R.layout.widget_switch) {
            views.setOnClickPendingIntent(R.id.layout_switch, toggle)
        } else {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.layout_card, open)
            views.setTextViewText(R.id.text_server, selectedServerName(context))
        }
        views.setTextViewText(
            R.id.text_status,
            context.getString(if (isRunning) R.string.widget_state_on else R.string.widget_state_off)
        )
        views.setInt(
            R.id.layout_background,
            "setBackgroundResource",
            if (isRunning) R.drawable.widget_button_on else R.drawable.widget_button_off
        )
        views.setInt(R.id.image_switch, "setColorFilter", android.graphics.Color.WHITE)

        val theme = HappThemeManager.selected.value
        val root = if (layoutRes == R.layout.widget_switch) R.id.layout_switch else R.id.layout_card
        if (theme != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Paint the card and the button in the active colour theme (tinting needs Android 12+).
            val background = theme.backgroundColors.firstOrNull().orEmpty().toHappColor(Color.Black)
            val card = theme.serverRowBackgroundColor.toHappColor().compositeOver(background)
            val text = theme.serverRowTitleTextColor.toHappColor(Color.White).toArgb()
            val subText = theme.serverRowSubTitleTextColor.toHappColor(Color.White).toArgb()
            views.setInt(root, "setBackgroundResource", R.drawable.widget_card_solid)
            views.setColorStateList(root, "setBackgroundTintList", ColorStateList.valueOf(card.toArgb()))
            views.setTextColor(R.id.text_status, text)
            if (layoutRes != R.layout.widget_switch) views.setTextColor(R.id.text_server, subText)
            if (isRunning) {
                views.setInt(R.id.layout_background, "setBackgroundResource", R.drawable.widget_circle)
                views.setColorStateList(
                    R.id.layout_background,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(theme.buttonColor.toHappColor().toArgb())
                )
                views.setInt(R.id.image_switch, "setColorFilter", theme.buttonTextColor.toHappColor().toArgb())
            } else {
                views.setInt(R.id.image_switch, "setColorFilter", text)
                views.setColorStateList(
                    R.id.layout_background,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(theme.buttonColor.toHappColor().copy(alpha = 0.25f).toArgb())
                )
            }
        }

        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun selectedServerName(context: Context): String =
        MmkvManager.getSelectServer()
            ?.let { MmkvManager.decodeServerConfig(it)?.remarks }
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.widget_no_server)

    private fun renderAll(context: Context, isRunning: Boolean) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        render(context, manager, manager.getAppWidgetIds(ComponentName(context, javaClass)), isRunning)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (AppConfig.BROADCAST_ACTION_WIDGET_CLICK == intent.action) {
            if (CoreServiceManager.isRunning()) {
                LauncherManager.stopService(context)
            } else {
                LauncherManager.startServiceFromToggle(context)
            }
        } else if (AppConfig.BROADCAST_ACTION_ACTIVITY == intent.action) {
            when (intent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> renderAll(context, true)
                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_START_FAILURE, AppConfig.MSG_STATE_STOP_SUCCESS ->
                    renderAll(context, false)
            }
        }
    }
}
