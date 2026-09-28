package com.carrot.hud

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.*
import androidx.core.content.ContextCompat

/** Maintains the explicitly enabled external OP connection, without displaying a HUD at boot. */
class ArchiveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val notificationUpdate = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(1012, notification())
            handler.postDelayed(this, 20_000)
        }
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { ArchiveJobs.request(applicationContext) }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("carrot_archive", "자동 기록 연결", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) startForeground(1012, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(1012, notification())
        if (!ArchiveConfig.enabled(this)) { stopSelf(); return }
        connectionStatus = "부팅 자동 연결 실행 중"
        DriveArchive.attach(this, this)
        ArchiveJobs.schedule(this)
        ArchiveJobs.request(this)
        handler.post(notificationUpdate)
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) } catch (_: Exception) { }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.carrot.hud.STOP_ARCHIVE" || !ArchiveConfig.enabled(this)) {
            ArchiveConfig.setEnabled(this, false)
            ArchiveJobs.cancel(this)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, ArchiveSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ArchiveService::class.java).setAction("com.carrot.hud.STOP_ARCHIVE"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "carrot_archive").setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Carrot 자동 기록 연결")
            .setContentText(DriveArchive.status).setStyle(Notification.BigTextStyle().bigText("${DriveArchive.status}\n${PcArchive.status}"))
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "자동 연결 중지", stop).build())
            .setOnlyAlertOnce(true).setOngoing(true).build()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) { }
        DriveArchive.detach(this)
        connectionStatus = "자동 연결 서비스 대기"
        super.onDestroy()
    }
    companion object {
        @Volatile var connectionStatus = "자동 연결 서비스 대기"
        fun start(context: Context) {
            if (!ArchiveConfig.enabled(context)) return
            ArchiveJobs.schedule(context)
            ArchiveJobs.request(context)
            try { ContextCompat.startForegroundService(context, Intent(context, ArchiveService::class.java)) }
            catch (_: RuntimeException) { connectionStatus = "자동 실행 제한 · 앱을 열면 연결 재개 / 예약 전송 대기" }
        }
    }
}

class ArchiveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) ArchiveService.start(context)
    }
}
