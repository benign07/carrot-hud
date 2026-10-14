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
    private lateinit var rollback: Button
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var visible = false
    private var state: JSONObject? = null
    private var preview: JSONObject? = null
    private var stateTarget: String? = null
    private var stateDeviceId: String? = null
    private fun pairing() = File(noBackupFilesDir, "op-update-pairing.json")
    private fun endpoint(): OpEndpoint? = OpUpdateMonitor.endpoint(this)
    private val importPairing = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val raw = contentResolver.openInputStream(uri)!!.use { it.readBytesLimited(4096) }.toString(Charsets.UTF_8)
            val device = DeviceStore.getActive(this) ?: error("기기 등록 필요")
            Thread {
                val error = try { OpPairingStore.bind(applicationContext, device, raw); null }
                    catch (e: Exception) { e.message ?: "연결 파일 확인 실패" }
                runOnUiThread { if (error == null) refresh(true) else status.text = error }
            }.start()
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
        queue = button("오파에 설치·재부팅 · 주행 중이면 정차 후 적용") { command("queue") }
        cancel = button("업데이트 예약 취소") { command("cancel") }
        rollback = button("이전 설치본으로 되돌리기") { command("rollback") }
        rollback.isEnabled = false
        text("앱으로 설치한 직전 오파 수정본을 백업으로 복원합니다. 복원할 버전이 확인될 때만 버튼이 켜집니다. P 정차·제어 해제 후 재부팅해 적용하며, 주행 기록과 차량 설정은 보존합니다.", 14f)
        queue.isEnabled = false; cancel.isEnabled = false
        text("운행 중에는 예약만 합니다. P 정차·속도 0·오파 제어 해제가 유지되면 검증과 백업 후 재부팅합니다. 설치 파일은 재부팅 시 적용됩니다. 차량 상태가 바뀌면 다시 대기합니다.")
        text("업데이트 이력", 19f)
        history = text("이력 확인 대기", 14f)
        button("오파 업데이트 연결 파일 등록") { importPairing.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        text("등록된 기기는 Tailscale 주소로 직접 업데이트합니다. GitHub에 검증·서명된 새 버전이 있으면 위 설치 버튼만 누르세요. Wi-Fi 주소가 바뀌어도 같은 기기인지 식별자로 확인합니다. 최초 연결 파일 등록은 한 번 필요하며, 펌웨어·모델·네이티브 빌드는 별도 설치 대상입니다.", 14f)
        setContentView(ScrollView(this).apply { addView(box) })
        val cached = getSharedPreferences("op_update", MODE_PRIVATE).getString("preview", null)
        preview = try { cached?.let { JSONObject(it) } } catch (_: Exception) { null }
        render(false)
        refresh(true)
    }
    private fun render(connected: Boolean) {
        val matched = connected && stateTarget == endpoint()?.url && stateDeviceId == DeviceStore.getActiveId(this)
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
        queue.isEnabled = matched && !busy.get() && !active && state?.let { OpUpdatePolicy.available(it) } != null
        cancel.isEnabled = matched && !busy.get() && state?.optBoolean("cancel_available", false) == true
        val restore = state?.let { OpUpdatePolicy.rollback(it) }
        rollback.isEnabled = matched && !busy.get() && restore != null
        rollback.text = if (restore == null) "이전 설치본으로 되돌리기 · 백업 확인 대기"
            else "이전 설치본으로 되돌리기\n${restore.optString("target_release_id")}"
        val rows = state?.optJSONArray("history")
        if (rows != null) history.text = (rows.length()-1 downTo 0).joinToString("\n\n") { i ->
            val row = rows.getJSONObject(i)
            val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((row.optDouble("at", 0.0)*1000).toLong()))
            "$at · ${row.optString("release_id")}\n${row.optString("message")}" }
    }
    private fun refresh(check: Boolean) {
        if (!busy.compareAndSet(false, true)) return
        val requestedId = DeviceStore.getActiveId(this)
        Thread {
            val ep = OpPairingStore.resolve(applicationContext)
            var result: JSONObject? = null
            var latest: JSONObject? = null
            var message = OpPairingStore.reason(applicationContext) ?: "등록 기기의 Tailscale 연결 또는 최초 연결 파일을 확인하세요."
            try {
                if (ep != null) result = if (check) OpUpdateApi.action(ep, "check") else OpUpdateApi.status(ep)
                if (ep != null && result != null) OpUpdateMonitor.observe(applicationContext, ep, result!!)
            } catch (e: Exception) { message = if (e is OpUpdateException) e.message ?: "연결 대기" else "오파 연결 대기 · 설치 완료 여부는 재연결 후 확인합니다." }
            if (check && result == null) try { latest = OpUpdateApi.latest() } catch (_: Exception) { }
            runOnUiThread {
                busy.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (DeviceStore.getActiveId(this) != requestedId || (ep != null && endpoint()?.url != ep.url)) {
                    state = null; stateTarget = null; stateDeviceId = null; render(false); return@runOnUiThread
                }
                if (latest != null) {
                    preview = latest
                    getSharedPreferences("op_update", MODE_PRIVATE).edit().putString("preview", latest.toString()).apply()
                }
                if (result != null) {
                    state = result
                    stateTarget = ep?.url; stateDeviceId = requestedId
                    status.text = OpUpdateApi.label(result!!.optString("phase")) + "\n" + result!!.optString("message")
                } else { state = null; stateTarget = null; stateDeviceId = null; status.text = message }
                render(result != null)
            }
        }.start()
    }
    private fun command(action: String) {
        val ep = endpoint() ?: return
        val requestedId = DeviceStore.getActiveId(this)
        if (stateTarget != ep.url || stateDeviceId != requestedId) { state = null; refresh(true); return }
        val selected = state?.optJSONObject(if (action == "rollback") "installed" else "latest")
        if (!busy.compareAndSet(false, true)) return
        queue.isEnabled = false; cancel.isEnabled = false; rollback.isEnabled = false
        Thread {
            var result: JSONObject? = null
            var error = "요청 응답을 받지 못했습니다. 상태를 확인합니다."
            try {
                require(endpoint()?.url == ep.url && DeviceStore.getActiveId(this) == requestedId)
                result = OpUpdateApi.action(ep, action, selected); OpUpdateMonitor.observe(applicationContext, ep, result!!)
            }
            catch (e: Exception) { if (e is OpUpdateException) error = e.message ?: error }
            runOnUiThread {
                busy.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (endpoint()?.url != ep.url || DeviceStore.getActiveId(this) != requestedId) {
                    state = null; stateTarget = null; stateDeviceId = null; render(false); return@runOnUiThread
                }
                if (result != null) { state = result; status.text = result!!.optString("message"); render(true) }
                else { status.text = error; state = null; render(false) }
            }
        }.start()
    }
    override fun onStart() { super.onStart(); visible = true; handler.postDelayed(poll, 5000) }
    override fun onStop() { visible = false; handler.removeCallbacks(poll); super.onStop() }
}
