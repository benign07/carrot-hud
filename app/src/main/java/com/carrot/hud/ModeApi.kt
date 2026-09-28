package com.carrot.hud

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ModeResult(val saved: Int?, val effective: Int?, val message: String, val changed: Boolean = false)

/** Server-authoritative transaction; failed/uncertain writes are never retried. */
class ModeApi(private val exchange: (String, String, String?) -> JSONObject) {
    private fun saved(): Int {
        val response = exchange("GET", "/api/params_bulk?names=MyDrivingMode", null)
        require(response.optBoolean("ok")) { "저장 모드 확인 실패" }
        val value = response.getJSONObject("values").getInt("MyDrivingMode")
        require(value in 1..4) { "저장 모드 값 오류" }
        return value
    }
    private fun effective(): Int? = try { parseEffective(exchange("GET", "/api/live_runtime", null)) } catch (_: Exception) { null }
    fun read(): ModeResult = try {
        val value = saved()
        ModeResult(value, effective(), "변경은 정차/P·제어 해제 후")
    } catch (_: Exception) { ModeResult(null, null, "연결/모드 확인 실패") }
    fun cycle(): ModeResult {
        var current: Int? = null
        return try {
            current = saved()
            val next = current % 4 + 1
            val response = exchange("POST", "/api/param_set", JSONObject().put("name", "MyDrivingMode").put("value", next).toString())
            if (!response.optBoolean("ok") || !response.optBoolean("has_params") || response.optInt("value") != next) {
                ModeResult(current, null, response.optString("error", "기기 저장 확인 실패"))
            } else {
                val confirmed = saved()
                if (confirmed == next) ModeResult(confirmed, effective(), "저장 확인 · 실제 모드는 별도 표시", true)
                else ModeResult(confirmed, effective(), "저장값 불일치 · 다시 확인하세요")
            }
        } catch (error: ModeApiError) { ModeResult(current, null, error.message ?: "기기 요청 실패") }
        catch (_: Exception) { ModeResult(current, null, "연결/응답 확인 실패 · 자동 재변경 없음") }
    }
    companion object {
        fun parseEffective(data: JSONObject): Int? {
            val age = data.optDouble("snapshotAgeMs", Double.NaN)
            if (!data.optBoolean("ok") || !age.isFinite() || age < 0 || age >= 1500) return null
            val runtime = data.optJSONObject("runtime") ?: return null
            if (runtime.optJSONObject("serviceAlive")?.optBoolean("longitudinalPlan") != true ||
                runtime.optJSONObject("serviceValid")?.optBoolean("longitudinalPlan") != true) return null
            val serviceAge = runtime.optJSONObject("serviceAgeMs")?.optDouble("longitudinalPlan", Double.NaN) ?: return null
            if (!serviceAge.isFinite() || serviceAge < 0 || serviceAge >= 1000) return null
            return data.optJSONObject("services")?.optJSONObject("longitudinalPlan")?.optInt("myDrivingMode")?.takeIf { it in 1..4 }
        }
        fun forDevice(device: Device) = ModeApi { method, path, body ->
            val connection = URL("http://${device.ip}:${device.port}$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 800; connection.readTimeout = 800
                connection.instanceFollowRedirects = false; connection.useCaches = false
                if (body != null) {
                    connection.doOutput = true
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(bytes) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val data = JSONObject(stream?.use { it.readBytesLimited(1024 * 1024) }?.toString(Charsets.UTF_8) ?: "{}")
                if (code !in 200..299) throw ModeApiError(data.optString("error", "기기 응답 $code"))
                data
            } finally { connection.disconnect() }
        }
    }
}
class ModeApiError(message: String) : Exception(message)
