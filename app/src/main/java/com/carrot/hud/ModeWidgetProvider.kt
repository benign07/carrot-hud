package com.carrot.hud

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget that is a single driving-mode toggle button.
 * Tap = cycle MyDrivingMode 연비→안전→일반→고속→연비 on the active device
 * (same as the device's own logic). Auto-resolves the device IP (hotspot).
 */
class ModeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try { ids.forEach { render(context, mgr, it, cycle = false) } }
            finally { pending.finish() }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CYCLE) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ModeWidgetProvider::class.java))
            val pending = goAsync()
            Thread {
                try { ids.forEach { render(context, mgr, it, cycle = true) } }
                finally { pending.finish() }
            }.start()
        }
    }

    private fun render(context: Context, mgr: AppWidgetManager, id: Int, cycle: Boolean) {
        val views = RemoteViews(context.packageName, R.layout.widget_mode)
        views.setOnClickPendingIntent(
            R.id.modeRoot,
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, ModeWidgetProvider::class.java).setAction(ACTION_CYCLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )

        val device = DeviceStore.getActive(context)
        if (device == null) {
            views.setTextViewText(R.id.modeText, "기기없음")
            views.setTextColor(R.id.modeText, 0xFFAAAAAA.toInt())
            mgr.updateAppWidget(id, views)
            return
        }

        if (cycle) {
            views.setTextViewText(R.id.modeText, "…")
            mgr.updateAppWidget(id, views)
        }

        val resolved = Net.resolve(context, device)
        if (resolved == null) {
            views.setTextViewText(R.id.modeText, "오프라인")
            views.setTextColor(R.id.modeText, 0xFFE5534B.toInt())
            mgr.updateAppWidget(id, views)
            return
        }

        val mode = if (cycle) Net.cycleMode(resolved) else Net.readMode(resolved)
        val (txt, color) = modeTextColor(mode)
        views.setTextViewText(R.id.modeText, txt)
        views.setTextColor(R.id.modeText, color)
        mgr.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_CYCLE = "com.carrot.hud.MODE_CYCLE"

        fun modeTextColor(m: Int): Pair<String, Int> = when (m) {
            1 -> "연비" to 0xFF3DDC84.toInt()
            2 -> "안전" to 0xFFFFB64D.toInt()
            3 -> "일반" to 0xFFE6EBE8.toInt()
            4 -> "고속" to 0xFFFF5C5C.toInt()
            else -> "--" to 0xFFAAAAAA.toInt()
        }
    }
}
