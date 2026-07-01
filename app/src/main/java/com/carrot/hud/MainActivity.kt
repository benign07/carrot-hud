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
 * HUD screen for one device: WebView of <device>/hud.html. The device IP is
 * auto-resolved (Net.resolve) before loading, so a changed hotspot IP is found
 * automatically. Native controls: overlay / settings / device list / reload.
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

        findViewById<View>(R.id.btnOverlay).setOnClickListener { startOverlay() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            device?.let { web.loadUrl(it.settingsUrl()) }
        }
        findViewById<View>(R.id.btnDevices).setOnClickListener {
            startActivity(Intent(this, DeviceListActivity::class.java))
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { loadResolved(false) }

        loadResolved(intent.getBooleanExtra(EXTRA_SETTINGS, false))
    }

    private fun showMsg(msg: String) {
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style=\"margin:0;height:100vh;background:#0a0e12;color:#9fb3a8;" +
            "font-family:sans-serif;font-size:16px;display:flex;align-items:center;" +
            "justify-content:center;text-align:center;padding:6vw\">$msg</body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    /** Resolve the device's current IP (may scan the LAN) then load its page. */
    private fun loadResolved(settings: Boolean) {
        val d = device ?: return
        showMsg("기기 찾는 중… (${d.name})")
        Thread {
            val resolved = Net.resolve(this, d)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (resolved == null) {
                    showMsg("기기를 찾을 수 없습니다.<br><br>폰과 같은 WiFi/핫스팟인지,<br>콤마가 켜져 있는지 확인하세요.<br><br>(‘기기’ 버튼 → 목록에서 다시 시도)")
                    return@runOnUiThread
                }
                device = resolved
                web.loadUrl(if (settings) resolved.settingsUrl() else resolved.hudUrl())
            }
        }.start()
    }

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "‘다른 앱 위에 표시’ 권한을 켠 뒤 다시 눌러주세요", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "오버레이 시작됨 — 알림에서 종료할 수 있어요", Toast.LENGTH_SHORT).show()
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
