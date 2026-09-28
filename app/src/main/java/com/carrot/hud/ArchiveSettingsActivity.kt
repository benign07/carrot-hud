package com.carrot.hud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ArchiveSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val pc = ArchiveConfig.endpoint(this@ArchiveSettingsActivity)
            status.text = "${ArchiveService.connectionStatus}\n${DriveArchive.status}\n${PcArchive.status}\nPC: ${pc?.url ?: "연결 파일을 등록하세요"}"
            handler.postDelayed(this, 2000)
        }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val raw = contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(4096) }.toString(Charsets.UTF_8)
                ArchiveConfig.import(this, raw)
                ArchiveService.start(this)
                Toast.makeText(this, "PC 연결 등록 완료 · 자동 전송을 시도합니다", Toast.LENGTH_LONG).show()
            } catch (_: Exception) { Toast.makeText(this, "연결 파일을 확인하세요. 기존 설정은 유지됩니다.", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun text(value: String) { box.addView(TextView(this).apply { text = value; textSize = 16f; setPadding(0, 8, 0, 8) }) }
        fun button(label: String, action: () -> Unit) { box.addView(Button(this).apply { text = label; setOnClickListener { action() } }) }
        text("자동 기록 · PC 전송")
        status = TextView(this).apply { textSize = 15f }
        box.addView(status)
        box.addView(Switch(this).apply {
            text = "부팅 후 자동 연결 · PC 전송"
            isChecked = ArchiveConfig.enabled(this@ArchiveSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                ArchiveConfig.setEnabled(this@ArchiveSettingsActivity, checked)
                if (checked) ArchiveService.start(this@ArchiveSettingsActivity)
                else { ArchiveJobs.cancel(this@ArchiveSettingsActivity); stopService(Intent(this@ArchiveSettingsActivity, ArchiveService::class.java)) }
            }
        })
        button("PC 연결 파일 등록") { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        button("지금 연결·전송 재시도") { ArchiveService.start(this) }
        button("HUD 열기") { startActivity(Intent(this, MainActivity::class.java)) }
        button("앱 자동 시작·배터리 설정 확인") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        text("폰을 켜고 처음 잠금을 해제하면 연결 서비스를 시작합니다. HUD 화면은 알림이나 버튼으로 엽니다. 오파가 꺼져 있어도 보관된 기록은 PC로 전송합니다. PC·Tailscale이 꺼져 있으면 대기 후 재시도합니다.")
        text("샤오미 설정에서 Carrot HUD와 Tailscale의 자동 시작을 허용하고 배터리 제한을 해제해야 할 수 있습니다. 강제 종료 후에는 앱을 다시 열어야 합니다. 절전 중 예약 전송은 지연될 수 있습니다.")
        text("모바일 데이터 사용: 압축 진단 기록만 전송합니다. 전체 rlog·영상은 포함하지 않습니다. PC 확인 뒤에도 폰 원본은 보존합니다. 폰 보관 상한은 2 GiB입니다.")
        setContentView(ScrollView(this).apply { addView(box) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        ArchiveService.start(this)
    }
    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
}
