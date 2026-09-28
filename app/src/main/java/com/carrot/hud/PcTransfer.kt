package com.carrot.hud

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Idempotent upload: a durable, hash-verified PC receipt is required for success. */
object PcTransfer {
    private val timeouts = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "pc-upload-timeout").apply { isDaemon = true }
    }
    fun upload(endpoint: PcEndpoint, row: ChunkInfo, file: File,
               open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
        require(ChunkTransfer.verified(file, row)) { "휴대폰 기록 무결성 오류" }
        val connection = open(URL("${endpoint.url}/v1/chunks/${row.id}"))
        val timeout = timeouts.schedule({ connection.disconnect() }, 45, java.util.concurrent.TimeUnit.SECONDS)
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(row.bytes)
            connection.setRequestProperty("Authorization", "Bearer ${endpoint.token}")
            connection.setRequestProperty("X-Receiver-Id", endpoint.receiverId)
            connection.setRequestProperty("X-Chunk-SHA256", row.sha256)
            connection.setRequestProperty("X-Chunk-Reason", row.reason)
            connection.setRequestProperty("Content-Type", "application/gzip")
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output, 64 * 1024) } }
            require(connection.responseCode in 200..201) { "PC 응답 ${connection.responseCode}" }
            val raw = connection.inputStream.use { it.readBytesLimited(4096) }
            val receipt = JSONObject(raw.toString(Charsets.UTF_8))
            require(receipt.getBoolean("stored") && receipt.getString("receiver_id") == endpoint.receiverId &&
                receipt.getString("id") == row.id && receipt.getLong("bytes") == row.bytes &&
                receipt.getString("sha256") == row.sha256) { "PC 저장 확인 불일치" }
        } finally { timeout.cancel(false); connection.disconnect() }
    }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit) { "응답 크기 초과" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
