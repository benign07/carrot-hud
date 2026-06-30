package com.carrot.hud

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * Home-screen widget: active device's name, current/set speed, online status.
 * Tap = open app; ⟳ = refresh now.
 *
 * Android clamps the widget's own updatePeriodMillis to >= 30 min, so for a
 * shorter cadence we drive refreshes with a self-rescheduling AlarmManager
 * alarm (interval is user-selectable in the app; default 60s). When the device
 * is active/charging this fires near the interval; in deep Doze the OS
 * rate-limits it (~every 9-15 min) to save battery.
 */
class HudWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        cancel(context)
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                ids.forEach { renderWidget(context, mgr, it) }
            } finally {
                scheduleNext(context)
                pending.finish()
            }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, HudWidgetProvider::class.java))
            val pending = goAsync()
            Thread {
                try {
                    ids.forEach { renderWidget(context, mgr, it) }
                } finally {
                    scheduleNext(context)
                    pending.finish()
                }
            }.start()
        }
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_hud)

        views.setOnClickPendingIntent(
            R.id.wRoot,
            PendingIntent.getActivity(
                context, 0,
                Intent(context, DeviceListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        views.setOnClickPendingIntent(
            R.id.wRefresh,
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, HudWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

        val device = DeviceStore.getActive(context)
        if (device == null) {
            views.setTextViewText(R.id.wName, "기기 없음")
            views.setTextViewText(R.id.wSpeed, "--")
            views.setTextViewText(R.id.wSetSpeed, "--")
            views.setTextViewText(R.id.wStatus, "앱에서 기기를 추가하세요")
            mgr.updateAppWidget(id, views)
            return
        }

        views.setTextViewText(R.id.wName, device.name)
        val snap = Net.fetchSnapshot(device)
        views.setTextViewText(R.id.wSpeed, snap.speed)
        views.setTextViewText(R.id.wSetSpeed, snap.setSpeed)
        views.setTextViewText(R.id.wStatus, if (snap.online) "● 온라인" else "○ 오프라인 (앱·WiFi 확인)")
        views.setTextColor(R.id.wStatus, if (snap.online) 0xFF3DDC84.toInt() else 0xFFE5534B.toInt())
        mgr.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_REFRESH = "com.carrot.hud.WIDGET_REFRESH"
        private const val ALARM_REQUEST = 100

        private fun alarmPi(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, ALARM_REQUEST,
                Intent(context, HudWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** Schedule the next refresh alarm (self-repeating; called after each render). */
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val interval = DeviceStore.getWidgetIntervalMs(context).coerceAtLeast(15_000L)
            val triggerAt = System.currentTimeMillis() + interval
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    am.setAndAllowWhileIdle(AlarmManager.RTC, triggerAt, alarmPi(context))
                else
                    am.set(AlarmManager.RTC, triggerAt, alarmPi(context))
            } catch (_: Exception) {
            }
        }

        fun cancel(context: Context) {
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmPi(context))
            } catch (_: Exception) {
            }
        }
    }
}
