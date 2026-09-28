package com.carrot.hud

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.view.View
import java.text.DateFormat
import java.util.Date

/** Local-only progress updates never trigger a vehicle command. */
object ArchiveWidgetStatus {
    @Synchronized fun publish(context: Context) {
        try {
        val text = "${DriveArchive.status}\n${PcArchive.status}"
        val badge = OpUpdateMonitor.badge(context)
        val prefs = context.getSharedPreferences("archive_widget", Context.MODE_PRIVATE)
        if (prefs.getString("text", "") == text && prefs.getString("badge", "") == badge) return
        prefs.edit().putString("text", text).putString("badge", badge).putLong("at", System.currentTimeMillis()).apply()
        val mgr = AppWidgetManager.getInstance(context)
        for ((provider, layout, field) in listOf(
            Triple(HudWidgetProvider::class.java, R.layout.widget_hud, R.id.wTransfer),
            Triple(ModeWidgetProvider::class.java, R.layout.widget_mode, R.id.modeTransfer))) {
            val ids = mgr.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) {
                val views = RemoteViews(context.packageName, layout)
                fill(context, views, field)
                mgr.partiallyUpdateAppWidget(ids, views)
            }
        }
        } catch (_: RuntimeException) { /* Widget host failures must not stop archival. */ }
    }
    fun fill(context: Context, views: RemoteViews, field: Int) {
        val prefs = context.getSharedPreferences("archive_widget", Context.MODE_PRIVATE)
        val text = prefs.getString("text", "자동 기록·PC 전송 상태 대기") ?: ""
        val at = prefs.getLong("at", 0)
        val checked = if (at == 0L) "" else "\n상태 갱신 ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))}"
        views.setTextViewText(field, text + checked)
        val updateField = if (field == R.id.wTransfer) R.id.wUpdate else R.id.modeUpdate
        val badge = OpUpdateMonitor.badge(context)
        views.setTextViewText(updateField, badge)
        views.setViewVisibility(updateField, if (badge.isEmpty()) View.GONE else View.VISIBLE)
        views.setOnClickPendingIntent(updateField, OpUpdateMonitor.openIntent(context))
        views.setOnClickPendingIntent(field, PendingIntent.getActivity(context, 1012,
            Intent(context, ArchiveSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
}
