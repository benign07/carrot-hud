package com.carrot.hud

import android.os.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class OpUpdateActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var history: TextView
    private lateinit var queue: Button
    private lateinit var cancel: Button
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false
    private var state: JSONObject? = null
    private var preview: JSONObject? = null
    private fun pairing() = File(noBackupFilesDir, "op-update-pairing.json")
    private fun endpoint(): OpEndpoint? = OpUpdateMonitor.endpoint(this)
    private val importPairing = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val raw = contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(4096) }.toString(Charsets.UTF_8)
            OpEndpoint.parse(raw)
            ArchiveConfig.writeAtomic(pairing(), raw)
            refresh(true)
        } catch (_: Exception) { status.text = "오파용 연결 파일을 확인하세요. 기존 설정은 보존됩니다." }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (visible) { refresh(false); handler.postDelayed(this, 5000) }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 12); box.addView(this)
        }
        fun button(value: String, action: () -> Unit): Button = Button(this).apply {
            text = value; setOnClickListener { action() }; box.addView(this)
        }
        text("오파 업데이트", 24f)
        status = text("기기 연결 확인 중")
        notes = text("새 버전과 변경점을 확인합니다.")
        button("새 버전·상태 확인") { refresh(true) }
        text("자동 연결을 켜두면 폰 부팅 후 오파 연결·재연결 시 새 버전을 확인하고, 연결 중에는 15분 간격으로 확인합니다. 새 버전은 알림과 위젯에 표시하며, 같은 버전 알림은 한 번만 표시합니다. 자동 설치는 하지 않습니다.", 14f)
        queue = button("업데이트 예약 · P 정차 후 적용") { command("queue") }
        cancel = button("업데이트 예약 취소") { command("cancel") }
        queue.isEnabled = false; cancel.isEnabled = false
        text("운행 중에는 예약만 합니다. P 정차·속도 0·오파 제어 해제가 유지되면 검증과 백업 후 재부팅합니다. 설치 파일은 재부팅 시 적용됩니다. 차량 상태가 바뀌면 다시 대기합니다.")
        text("업데이트 이력", 19f)
        history = text("이력 확인 대기", 14f)
        button("오파 업데이트 연결 파일 등록") { importPairing.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        text("처음 한 번 오파에 업데이트 기능을 설치해야 합니다. 앱 업데이트와 오파 업데이트는 서로 별개입니다. PC에서 게시하고 서명한 소스 업데이트만 받으며, 펌웨어·모델·네이티브 빌드는 별도 설치가 필요합니다.", 14f)
        setContentView(ScrollView(this).apply { addView(box) })
        val cached = getSharedPreferences("op_update", MODE_PRIVATE).getString("preview", null)
        preview = try { cached?.let { JSONObject(it) } } catch (_: Exception) { null }
        render(false)
        refresh(true)
    }
    private fun render(connected: Boolean) {
        val remote = state?.optJSONObject("latest")
        val current = state?.optJSONObject("installed")
        val latest = remote ?: preview
        val list = latest?.optJSONArray("notes")
        notes.text = "설치 버전: ${current?.optString("release_id", "최초 등록 대기") ?: "기기 연결 후 확인"}\n" +
            "게시 버전: ${latest?.optString("release_id", "없음") ?: "확인 전"}\n" +
            (if (!connected) "GitHub 변경점 미리보기 · 설치 상태는 기기 연결 후 확인\n" else "") +
            (if (list == null) "" else (0 until list.length()).joinToString("\n") { "• ${list.optString(it)}" })
        val phase = state?.optString("phase", "idle") ?: "idle"
        val active = phase in setOf("waiting_parked", "downloading", "countdown", "armed", "applying", "verifying", "rolling_back")
        queue.isEnabled = connected && !busy.get() && !active && remote != null &&
            remote.optInt("sequence", 0) > (current?.optInt("sequence", 0) ?: 0)
        cancel.isEnabled = connected && !busy.get() && state?.optBoolean("cancel_available", false) == true
        val rows = state?.optJSONArray("history")
        if (rows != null) history.text = (rows.length()-1 downTo 0).joinToString("\n\n") { i ->
            val row = rows.getJSONObject(i)
            val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((row.optDouble("at", 0.0)*1000).toLong()))
            "$at · ${row.optString("release_id")}\n${row.optString("message")}" }
    }
    private fun refresh(check: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        val ep = endpoint()
        Thread {
            var result: JSONObject? = null
            var latest: JSONObject? = null
            var message = "오파 업데이트 기능 최초 설치·연결 등록이 필요합니다."
            try {
                if (ep != null) result = if (check) OpUpdateApi.action(ep, "check") else OpUpdateApi.status(ep)
                if (ep != null && result != null) OpUpdateMonitor.observe(applicationContext, ep, result!!)
            } catch (e: Exception) { message = if (e is OpUpdateException) e.message ?: "연결 대기" else "오파 연결 대기 · 설치 완료 여부는 재연결 후 확인합니다." }
            if (check && result == null) try { latest = OpUpdateApi.latest() } catch (_: Exception) { }
            runOnUiThread {
                busy.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (latest != null) {
                    preview = latest
                    getSharedPreferences("op_update", MODE_PRIVATE).edit().putString("preview", latest.toString()).apply()
                }
                if (result != null) {
                    state = result
                    status.text = OpUpdateApi.label(result!!.optString("phase")) + "\n" + result!!.optString("message")
                } else { state = null; status.text = message }
                render(result != null)
            }
        }.start()
    }
    private fun command(action: String) {
        val ep = endpoint() ?: return
        val selected = state?.optJSONObject("latest")
        if (!busy.compareAndSet(false, true)) return
        queue.isEnabled = false; cancel.isEnabled = false
        Thread {
            var result: JSONObject? = null
            var error = "요청 응답을 받지 못했습니다. 상태를 확인합니다."
            try { result = OpUpdateApi.action(ep, action, selected); OpUpdateMonitor.observe(applicationContext, ep, result!!) }
            catch (e: Exception) { if (e is OpUpdateException) error = e.message ?: error }
            runOnUiThread {
                busy.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result != null) { state = result; status.text = result!!.optString("message"); render(true) }
                else { status.text = error; state = null; render(false) }
            }
        }.start()
    }
    override fun onStart() { super.onStart(); visible = true; handler.postDelayed(poll, 5000) }
    override fun onStop() { visible = false; handler.removeCallbacks(poll); super.onStop() }
}
