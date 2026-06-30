package com.carrot.hud

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * HUD screen for one device: full-screen WebView of <device>/?view=hud,
 * with native controls (overlay / settings / device list / reload).
 * The device is passed via EXTRA_DEVICE_ID (falls back to the active device).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var device: Device? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        device = DeviceStore.getById(this, intent.getStringExtra(EXTRA_DEVICE_ID))
            ?: DeviceStore.getActive(this)
        if (device == null) {
            // no device registered yet -> go pick/add one
            startActivity(Intent(this, DeviceListActivity::class.java))
            finish()
            return
        }
        DeviceStore.setActive(this, device!!.id)

        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()

        val startSettings = intent.getBooleanExtra(EXTRA_SETTINGS, false)
        web.loadUrl(if (startSettings) device!!.settingsUrl() else device!!.hudUrl())

        findViewById<View>(R.id.btnOverlay).setOnClickListener { startOverlay() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { web.loadUrl(device!!.settingsUrl()) }
        findViewById<View>(R.id.btnDevices).setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { web.loadUrl(device!!.hudUrl()) }
    }

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "‘다른 앱 위에 표시’ 권한을 켠 뒤 다시 눌러주세요", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "오버레이 시작됨 — 알림에서 종료할 수 있어요", Toast.LENGTH_SHORT).show()
        // go to the home screen so the floating HUD is visible over other apps
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_SETTINGS = "settings"
    }
}
