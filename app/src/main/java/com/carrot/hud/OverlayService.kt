package com.carrot.hud

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.ImageButton

/**
 * Always-on-top floating HUD. Draws a small draggable window (a WebView of
 * the device's /?view=hud) over other apps using SYSTEM_ALERT_WINDOW.
 * Runs as a foreground service so the system keeps it alive.
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var overlay: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Carrot HUD 오버레이", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Carrot HUD")
            .setContentText("플로팅 HUD 실행 중 — 탭하면 앱 열림")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("InflateParams", "SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun addOverlay() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = LayoutInflater.from(this).inflate(R.layout.overlay_hud, null)

        val device = DeviceStore.getActive(this)
        if (device == null) {
            stopSelf()
            return
        }
        val web = v.findViewById<WebView>(R.id.overlayWeb)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.loadUrl(device.hudUrl())

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            dp(230), dp(168), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(10)
        lp.y = dp(90)

        // drag to move
        val handle = v.findViewById<View>(R.id.overlayDrag)
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var initX = 0
            private var initY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(view: View?, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = lp.x; initY = lp.y; touchX = e.rawX; touchY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = initX + (e.rawX - touchX).toInt()
                        lp.y = initY + (e.rawY - touchY).toInt()
                        wm?.updateViewLayout(v, lp)
                    }
                }
                return true
            }
        })

        v.findViewById<ImageButton>(R.id.overlayClose).setOnClickListener { stopSelf() }
        v.findViewById<ImageButton>(R.id.overlayOpen).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        wm?.addView(v, lp)
        overlay = v
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        try {
            overlay?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "carrot_hud_overlay"
    }
}
