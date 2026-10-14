package com.carrot.hud

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DiagnosticStatusPolicy {
    fun label(row: JSONObject): String {
        val error = row.opt("error")
        if (error != null && error != JSONObject.NULL && error.toString().isNotBlank()) return "진단 기록 오류"
        return when (row.optString("state")) {
            "recording" -> "진단 작동 중"
            "error" -> "진단 기록 오류"
            "starting" -> "진단 시작 대기"
            "waiting", "idle", "stopped" -> "진단 대기"
            else -> "진단 상태 확인 대기"
        }
    }
}

/** Only the widget displays this status; fetching it never enables diagnostics. */
object DiagnosticWidgetStatus {
    fun refresh(context: Context, device: Device) {
        var connection: HttpURLConnection? = null
        val text = try {
            connection = URL("http://${device.ip}:${device.port}/api/automatic_drive/status").openConnection() as HttpURLConnection
            connection.connectTimeout = 2000; connection.readTimeout = 2000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            when (connection.responseCode) {
                200 -> DiagnosticStatusPolicy.label(JSONObject(connection.inputStream.use { it.readBytesLimited(32768) }.toString(Charsets.UTF_8)))
                404 -> "진단 상태 미지원"
                else -> "진단 상태 확인 대기"
            }
        } catch (_: Exception) { "진단 연결 대기" }
        finally { connection?.disconnect() }
        if (DeviceStore.getActiveId(context) != device.id) return
        context.getSharedPreferences("diagnostic_widget", Context.MODE_PRIVATE).edit()
            .putString("device_id", device.id).putString("text", text).putLong("at", System.currentTimeMillis()).apply()
    }
    fun text(context: Context): String {
        val p = context.getSharedPreferences("diagnostic_widget", Context.MODE_PRIVATE)
        val age = System.currentTimeMillis() - p.getLong("at", 0)
        if (p.getString("device_id", "") != DeviceStore.getActiveId(context) || age !in 0..60_000) return "진단 연결 대기"
        return p.getString("text", "진단 상태 확인 대기") ?: "진단 상태 확인 대기"
    }
}
