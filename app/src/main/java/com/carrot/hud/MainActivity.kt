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
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * HUD screen for one device: WebView of <device>/hud.html. The device IP is
 * auto-resolved (Net.resolve) before loading, so a changed hotspot IP is found
 * automatically. Native controls: overlay / settings / device list / reload.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var device: Device? = null
    private var needsConnection = true
    private var waitingForSettings = false
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reconnect = object : Runnable {
        override fun run() {
            if (needsConnection) loadResolved(waitingForSettings)
            archiveHandler.postDelayed(this, 20_000)
        }
    }
    private val archiveHandler = Handler(Looper.getMainLooper())
    private val archiveStatus = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.archiveStatus)?.text = "${DriveArchive.status}\n${PcArchive.status}"
            archiveHandler.postDelayed(this, 2000)
        }
    }
    private val exportRecords = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) Thread {
            val text = try {
                DriveArchive.export(applicationContext, uri)
                "주행 기록 내보내기 완료 · PC에서 압축을 풀어 분석하세요"
            } catch (e: Exception) { "내보내기 실패: ${e.message}" }
            runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
        }.start()
    }

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
        ArchiveService.start(this)
        findViewById<View>(R.id.btnOpUpdate).setOnClickListener {
            startActivity(Intent(this, OpUpdateActivity::class.java))
        }
        findViewById<View>(R.id.btnArchiveSettings).setOnClickListener {
            startActivity(Intent(this, ArchiveSettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnExportRecords).setOnClickListener {
            exportRecords.launch("carrot-drive-${System.currentTimeMillis()}.zip")
        }
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

    override fun onStart() {
        super.onStart()
        if (device != null) {
            DriveArchive.attach(this, this)
            archiveHandler.post(archiveStatus)
            archiveHandler.postDelayed(reconnect, 20_000)
        }
    }

    override fun onStop() {
        archiveHandler.removeCallbacks(archiveStatus)
        archiveHandler.removeCallbacks(reconnect)
        DriveArchive.detach(this)
        super.onStop()
    }

    /** Resolve the device's current IP (may scan the LAN) then load its page. */
    private fun loadResolved(settings: Boolean) {
        val d = device ?: return
        if (!resolving.compareAndSet(false, true)) return
        waitingForSettings = settings
        showMsg("기기 찾는 중… (${d.name})")
        Thread {
            val resolved = Net.resolve(this, d)
            runOnUiThread {
                resolving.set(false)
                if (isFinishing) return@runOnUiThread
                if (resolved == null) {
                    needsConnection = true
                    showMsg("오파 연결을 기다리고 있습니다.<br><br>오파와 Tailscale/Wi-Fi 연결을 확인하세요.<br>20초마다 자동으로 다시 연결합니다.<br><br>폰에 보관된 기록의 PC 전송은 별도로 진행됩니다.")
                    return@runOnUiThread
                }
                needsConnection = false
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
