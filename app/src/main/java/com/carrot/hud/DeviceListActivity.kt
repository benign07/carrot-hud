package com.carrot.hud

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Launcher screen: a list of registered comma devices (by IP).
 * Add / edit / delete devices, tap one to open its HUD.
 */
class DeviceListActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private var devices: List<Device> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_devices)

        listView = findViewById(R.id.deviceList)
        emptyView = findViewById(R.id.empty)

        findViewById<View>(R.id.btnAddDevice).setOnClickListener { showEditDialog(null) }

        listView.setOnItemClickListener { _, _, pos, _ -> openHud(devices[pos]) }
        listView.setOnItemLongClickListener { _, _, pos, _ -> showRowMenu(devices[pos]); true }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        devices = DeviceStore.getDevices(this)
        val labels = devices.map { "${it.name}\n${it.label()}" }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        emptyView.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
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
}
