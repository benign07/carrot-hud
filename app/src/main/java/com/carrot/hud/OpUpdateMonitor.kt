package com.carrot.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Started by the existing boot/reconnect archival path; never sends queue/cancel. */
object OpUpdateMonitor {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var schedule = OpUpdateSchedule()
    private var target = ""
    private const val CHANNEL = "op_update_available"
    private const val NOTIFICATION = 1013
    private fun prefs(context: Context) = context.getSharedPreferences("op_update_monitor", Context.MODE_PRIVATE)
    fun endpoint(context: Context): OpEndpoint? = try {
        val ep = OpEndpoint.parse(File(context.noBackupFilesDir, "op-update-pairing.json").readText())
        val device = DeviceStore.getActive(context) ?: error("No active device")
        require(ep.url == "http://${device.ip}:${device.port}")
        ep
    } catch (_: Exception) { null }

    @Synchronized fun connected(context: Context) {
        val app = context.applicationContext
        val ep = endpoint(app) ?: return
        if (target != ep.url) { target = ep.url; schedule = OpUpdateSchedule() }
        val current = schedule
        if (!current.due(SystemClock.elapsedRealtime()) || !busy.compareAndSet(false, true)) return
        executor.execute {
            try {
                var state = OpUpdateApi.status(ep)
                val check = synchronized(this) { current.checkDue(SystemClock.elapsedRealtime()) } &&
                    state.optString("phase") !in OpUpdatePolicy.activePhases
                if (check) state = OpUpdateApi.action(ep, "check")
                if (endpoint(app)?.url == ep.url) observe(app, ep, state)
                synchronized(this) { current.success(SystemClock.elapsedRealtime(), check) }
            } catch (_: Exception) {
                synchronized(this) { current.failed(SystemClock.elapsedRealtime()) }
            } finally { busy.set(false) }
        }
    }
    @Synchronized fun disconnected() { schedule.disconnected() }

    @Synchronized fun observe(context: Context, ep: OpEndpoint, state: JSONObject) {
        if (endpoint(context)?.url != ep.url) return
        val latest = OpUpdatePolicy.available(state)
        val p = prefs(context)
        p.edit().putString("url", ep.url).putString("release", latest?.optString("release_id") ?: "")
            .putLong("checked_at", System.currentTimeMillis()).apply()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "오파 새 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
        if (latest == null) manager.cancel(NOTIFICATION)
        else {
            val key = OpUpdatePolicy.identity(ep.url, latest)
            val seen = p.getStringSet("notified", emptySet()) ?: emptySet()
            if (key !in seen && manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
                val notes = latest.optJSONArray("notes")
                val text = "${latest.getString("release_id")} · 눌러서 변경점 확인\n" +
                    (if (notes == null) "" else (0 until minOf(notes.length(), 3)).joinToString("\n") { notes.optString(it) })
                val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("오파 새 업데이트가 있습니다").setContentText("눌러서 변경점과 설치 예약 확인")
                    .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(openIntent(context))
                    .setAutoCancel(true).setOnlyAlertOnce(true).build()
                try {
                    manager.notify(NOTIFICATION, notification)
                    p.edit().putStringSet("notified", (seen + key).toSet()).apply()
                } catch (_: SecurityException) { /* Widget badge remains available without notification permission. */ }
            }
        }
        ArchiveWidgetStatus.publish(context)
    }
    fun badge(context: Context): String {
        val device = DeviceStore.getActive(context) ?: return ""
        val p = prefs(context)
        if (p.getString("url", "") != "http://${device.ip}:${device.port}") return ""
        val release = p.getString("release", "") ?: ""
        return if (release.isBlank()) "" else "오파 새 업데이트 · 눌러서 확인"
    }
    fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, NOTIFICATION,
        Intent(context, OpUpdateActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
