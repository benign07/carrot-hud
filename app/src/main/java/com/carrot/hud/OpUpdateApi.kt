package com.carrot.hud

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class OpEndpoint(val url: String, val token: String) {
    init {
        val u = URI(url)
        val ip = (u.host ?: "").split('.').map { it.toIntOrNull() ?: -1 }
        require(u.scheme == "http" && ip.size == 4 && ip[0] == 100 && ip[1] in 64..127 &&
            ip.all { it in 0..255 } && u.port in 1..65535 && u.path.isNullOrEmpty() &&
            u.userInfo == null && u.query == null && u.fragment == null)
        require(token.matches(Regex("[A-Za-z0-9_-]{43,128}")))
    }
    companion object {
        fun parse(raw: String): OpEndpoint {
            require(raw.length <= 4096)
            val o = JSONObject(raw)
            require(o.getInt("schema") == 1)
            return OpEndpoint(o.getString("url"), o.getString("token"))
        }
    }
}

class OpUpdateException(message: String) : Exception(message)

/** Queue acknowledgement and installation completion are deliberately different states. */
object OpUpdateApi {
    const val CHANNEL = "https://raw.githubusercontent.com/benign07/openpilot/hud-device-updates-20260928/updates/hud/latest.json"
    fun read(connection: HttpURLConnection, body: JSONObject? = null): JSONObject {
        try {
            connection.connectTimeout = 6000
            connection.readTimeout = 25000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            if (code == 404) throw OpUpdateException("오파 업데이트 기능의 최초 설치 또는 버전 게시가 필요합니다.")
            if (code == 401) throw OpUpdateException("오파 업데이트 연결 파일을 등록하세요.")
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytesLimited(131072) }?.toString(Charsets.UTF_8) ?: ""
            if (code !in 200..299) throw OpUpdateException(raw.take(300).ifBlank { "요청 실패 ($code)" })
            return JSONObject(raw)
        } finally { connection.disconnect() }
    }
    fun status(endpoint: OpEndpoint): JSONObject = request(endpoint, null)
    fun action(endpoint: OpEndpoint, action: String, latest: JSONObject? = null): JSONObject {
        require(action in setOf("check", "queue", "cancel"))
        val body = JSONObject().put("action", action)
        if (action == "queue") {
            require(latest != null)
            body.put("release_id", latest.getString("release_id"))
                .put("bundle_sha256", latest.getString("bundle_sha256"))
        }
        // No automatic POST retry: a lost response is resolved by a status GET.
        return request(endpoint, body)
    }
    private fun request(endpoint: OpEndpoint, body: JSONObject?): JSONObject {
        val path = if (body == null) "status" else "action"
        val connection = URL(endpoint.url + "/api/hud_update/" + path).openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer " + endpoint.token)
        val result = read(connection, body)
        if (!result.optBoolean("ok", false)) throw OpUpdateException("기기 응답을 확인하지 못했습니다.")
        return result
    }
    fun latest(): JSONObject = read(URL(CHANNEL).openConnection() as HttpURLConnection).also {
        require(it.getInt("schema") == 1 && it.getString("release_id").matches(Regex("[a-z0-9][a-z0-9-]{0,63}")))
        require(it.getString("bundle_sha256").matches(Regex("[a-f0-9]{64}")))
        require(it.getJSONArray("notes").length() in 1..30)
    }
    fun label(phase: String): String = when (phase) {
        "idle" -> "업데이트 대기"
        "waiting_parked" -> "예약됨 · P 정차 대기"
        "downloading" -> "다운로드·서명 확인 중"
        "countdown" -> "재부팅 준비 · 예약 취소 가능"
        "armed" -> "재부팅 요청됨"
        "applying" -> "시작 전 파일 적용 중"
        "verifying" -> "적용됨 · 기기 시작 확인 중"
        "complete" -> "업데이트 완료"
        "cancelled" -> "예약 취소됨"
        "rolled_back" -> "이전 파일로 복구됨"
        "health_warning" -> "적용 후 기기 점검 필요"
        "failed" -> "업데이트 실패 · 내역 확인"
        else -> "상태 확인 필요"
    }
}
