package com.carrot.hud

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget: shows the active device's name, current/set speed and
 * online status. Tap the widget to open the app; tap ⟳ to refresh now.
 * Auto-refreshes on the system period (see hud_widget_info.xml) — live speed
 * isn't possible on a home widget, so this is a periodic + on-demand snapshot.
 */
class HudWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                ids.forEach { renderWidget(context, mgr, it) }
            } finally {
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
                    pending.finish()
                }
            }.start()
        }
    }

    private fun renderWidget(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_hud)

        // tap the widget -> open the app
        views.setOnClickPendingIntent(
            R.id.wRoot,
            PendingIntent.getActivity(
                context, 0,
                Intent(context, DeviceListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        // tap refresh -> re-run this provider
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
    }
}
