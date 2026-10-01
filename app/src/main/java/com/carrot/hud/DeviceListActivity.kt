package com.carrot.hud

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Launcher screen: list of registered comma devices (by IP).
 * Shows each device's online/offline status (reachable on :7000 — independent
 * of whether the car is connected), so you can jump straight into settings
 * even when the HUD has no driving data. Add / edit / delete supported.
 */
class DeviceListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: DeviceAdapter
    private lateinit var wifiBanner: TextView

    private var devices: List<Device> = emptyList()
    private val status = HashMap<String, Boolean?>() // id -> null=checking, true=online, false=offline
    private val io: ExecutorService = Executors.newFixedThreadPool(4)
    private val handler = Handler(Looper.getMainLooper())
    private var checkGeneration = 0L
    private val refreshTicker = object : Runnable {
        override fun run() {
            wifiBanner.visibility = if (isOnNetwork()) View.GONE else View.VISIBLE
            refresh()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ArchiveService.start(this)
        setContentView(R.layout.activity_devices)
        listView = findViewById(R.id.deviceList)
        emptyView = findViewById(R.id.empty)
        findViewById<View>(R.id.btnAddDevice).setOnClickListener { showEditDialog(null) }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        wifiBanner = findViewById(R.id.wifiBanner)
        wifiBanner.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
            }
        }
        adapter = DeviceAdapter()
        listView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        wifiBanner.visibility = if (isOnNetwork()) View.GONE else View.VISIBLE
        refresh()
        handler.postDelayed(refreshTicker, 20_000)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTicker)
        checkGeneration++ // Ignore responses from a previous visible check.
        super.onPause()
    }

    /** True if the phone is on Wi-Fi/Ethernet — i.e. it can reach a LAN device. */
    private fun isOnNetwork(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            false
        }
    }

    private fun refresh() {
        devices = DeviceStore.getDevices(this)
        val generation = ++checkGeneration
        status.keys.retainAll(devices.map { it.id }.toSet())
        emptyView.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        for (d in devices) {
            if (d.id !in status) status[d.id] = null
            checkDevice(d, generation)
        }
    }

    private fun checkDevice(d: Device, generation: Long) {
        io.execute {
            val up = Net.isUp(d)
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != checkGeneration) return@runOnUiThread
                status[d.id] = up
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun openHud(d: Device, settings: Boolean = false) {
        DeviceStore.setActive(this, d.id)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_DEVICE_ID, d.id)
                .putExtra(MainActivity.EXTRA_SETTINGS, settings)
        )
    }

    private fun showRowMenu(d: Device) {
        AlertDialog.Builder(this)
            .setTitle(d.name)
            .setItems(arrayOf("HUD 열기", "설정(:7000) 열기", "수정", "삭제")) { _, which ->
                when (which) {
                    0 -> openHud(d, false)
                    1 -> openHud(d, true)
                    2 -> showEditDialog(d)
                    3 -> confirmDelete(d)
                }
            }
            .show()
    }

    private fun confirmDelete(d: Device) {
        AlertDialog.Builder(this)
            .setMessage("'${d.name}' 기기를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ -> DeviceStore.removeDevice(this, d.id); refresh() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditDialog(existing: Device?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "이름 (예: 펠리세이드)"
            setText(existing?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val ipInput = EditText(this).apply {
            hint = "IP 주소 (예: 192.168.0.130)"
            setText(existing?.ip ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(nameInput)
        box.addView(ipInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "기기 추가" else "기기 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val ip = ipInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                if (ip.isNotEmpty()) {
                    if (existing == null) DeviceStore.addDevice(this, name, ip)
                    else DeviceStore.updateDevice(this, existing.id, name, ip)
                    refresh()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
    }

    // ---------- overflow menu ----------

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "HUD 앱 업데이트")
        pm.menu.add(0, 4, 3, "오파 업데이트")
        pm.menu.add(0, 2, 1, "위젯 갱신 주기")
        pm.menu.add(0, 3, 2, "자동 기록 · PC 전송")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> checkUpdate()
                2 -> showIntervalDialog()
                3 -> startActivity(Intent(this, ArchiveSettingsActivity::class.java))
                4 -> startActivity(Intent(this, OpUpdateActivity::class.java))
            }
            true
        }
        pm.show()
    }

    private fun showIntervalDialog() {
        val labels = arrayOf("30초", "1분", "5분", "15분", "30분")
        val values = longArrayOf(30_000, 60_000, 300_000, 900_000, 1_800_000)
        val cur = DeviceStore.getWidgetIntervalMs(this)
        val checked = values.indexOfFirst { it == cur }.let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle("위젯 갱신 주기")
            .setSingleChoiceItems(labels, checked) { d, which ->
                DeviceStore.setWidgetIntervalMs(this, values[which])
                HudWidgetProvider.scheduleNext(this)
                toast("위젯 갱신 주기: ${labels[which]} (대략, 충전·화면켜짐 시 정확)")
                d.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ---------- in-app update (GitHub Releases) ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun checkUpdate() {
        toast("업데이트 확인 중…")
        io.execute {
            val latest = Updater.fetchLatest()
            val current = Updater.currentVersionCode(this)
            runOnUiThread {
                when {
                    latest == null ->
                        toast("업데이트 정보를 못 가져왔습니다. (저장소가 공개인지 확인)")
                    latest.versionCode <= current ->
                        toast("이미 최신 버전입니다 (현재 v$current)")
                    else -> promptInstall(latest, current)
                }
            }
        }
    }

    private fun promptInstall(latest: Updater.Latest, current: Long) {
        AlertDialog.Builder(this)
            .setTitle("새 버전 ${latest.tag}")
            .setMessage("새 버전이 있습니다.\n현재 v$current → 최신 v${latest.versionCode}\n다운로드 후 설치할까요?")
            .setPositiveButton("다운로드·설치") { _, _ -> startDownload() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startDownload() {
        if (!Updater.canInstall(this)) {
            toast("‘이 출처의 앱 설치 허용’을 켠 뒤 다시 시도하세요")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
            }
            return
        }
        val spinner = ProgressBar(this)
        val pad = (24 * resources.displayMetrics.density).toInt()
        spinner.setPadding(pad, pad, pad, pad)
        val dialog = AlertDialog.Builder(this)
            .setTitle("다운로드 중…")
            .setView(spinner)
            .setCancelable(false)
            .create()
        dialog.show()
        io.execute {
            val file = Updater.download(this)
            runOnUiThread {
                if (dialog.isShowing) dialog.dismiss()
                if (file != null) Updater.install(this, file)
                else toast("다운로드 실패 (네트워크/저장소 확인)")
            }
        }
    }

    private inner class DeviceAdapter : BaseAdapter() {
        override fun getCount(): Int = devices.size
        override fun getItem(position: Int): Any = devices[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.device_row, parent, false)
            val d = devices[position]

            v.findViewById<TextView>(R.id.rowName).text = d.name

            val up = status[d.id]
            val statusText = when (up) {
                null -> getString(R.string.status_checking)
                true -> getString(R.string.status_online)
                else -> getString(R.string.status_offline)
            }
            v.findViewById<TextView>(R.id.rowIp).text = "${d.label()}  ·  $statusText"

            val dotColor = when (up) {
                null -> Color.parseColor("#888888")
                true -> Color.parseColor("#3DDC84")
                else -> Color.parseColor("#E5534B")
            }
            v.findViewById<View>(R.id.statusDot).backgroundTintList = ColorStateList.valueOf(dotColor)

            v.findViewById<Button>(R.id.rowSettings).setOnClickListener { openHud(d, true) }
            v.setOnClickListener { openHud(d, false) }
            v.setOnLongClickListener { showRowMenu(d); true }
            return v
        }
    }
}
