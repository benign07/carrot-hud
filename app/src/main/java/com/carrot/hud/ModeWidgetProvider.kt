package com.carrot.hud

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.concurrent.atomic.AtomicBoolean

/** One click issues one transaction, regardless of the number of widget instances. */
class ModeWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) { HudWidgetProvider.scheduleNext(context) }
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) { update(context, mgr, ids, false) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CYCLE || intent.action == ACTION_READ) {
            val mgr = AppWidgetManager.getInstance(context)
            update(context, mgr, mgr.getAppWidgetIds(ComponentName(context, ModeWidgetProvider::class.java)), intent.action == ACTION_CYCLE)
        }
    }
    private fun update(context: Context, mgr: AppWidgetManager, ids: IntArray, cycle: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        val pending = goAsync()
        Thread {
            try {
                if (cycle) ids.forEach { render(context, mgr, it, ModeResult(null, null, "변경 확인 중…")) }
                val device = DeviceStore.getActive(context)
                val result = if (device == null) ModeResult(null, null, "앱에서 기기를 등록하세요")
                    else if (cycle) ModeApi.forDevice(device).cycle() else ModeApi.forDevice(device).read()
                ids.forEach { render(context, mgr, it, result) }
            } finally { busy.set(false); pending.finish() }
        }.start()
    }
    private fun render(context: Context, mgr: AppWidgetManager, id: Int, state: ModeResult) {
        val views = RemoteViews(context.packageName, R.layout.widget_mode)
        views.setOnClickPendingIntent(R.id.modeRoot, PendingIntent.getBroadcast(context, 0,
            Intent(context, ModeWidgetProvider::class.java).setAction(ACTION_CYCLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        val (text, color) = modeTextColor(state.effective ?: state.saved ?: 0)
        views.setTextViewText(R.id.modeText, text)
        views.setTextColor(R.id.modeText, color)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        views.setTextViewText(R.id.modeTitle, if (state.effective != null) "확인한 실제 모드 · $time" else "저장 모드 · 실제 확인 대기")
        val saved = if (state.saved != null && state.effective != null && state.saved != state.effective)
            "저장: ${modeTextColor(state.saved).first} · " else ""
        views.setTextViewText(R.id.modeHint, saved + state.message + if (state.effective == 4) " · 신호 정지 OFF" else "")
        ArchiveWidgetStatus.fill(context, views, R.id.modeTransfer)
        mgr.updateAppWidget(id, views)
    }
    companion object {
        const val ACTION_CYCLE = "com.carrot.hud.MODE_CYCLE"
        const val ACTION_READ = "com.carrot.hud.MODE_READ"
        private val busy = AtomicBoolean(false)
        fun modeTextColor(mode: Int): Pair<String, Int> = when (mode) {
            1 -> "연비" to 0xFF3DDC84.toInt()
            2 -> "완만" to 0xFFFFB64D.toInt()
            3 -> "일반" to 0xFFE6EBE8.toInt()
            4 -> "고속" to 0xFFFF5C5C.toInt()
            else -> "--" to 0xFFAAAAAA.toInt()
        }
    }
}
