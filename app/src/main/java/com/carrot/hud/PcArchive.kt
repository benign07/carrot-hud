package com.carrot.hud

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.locks.ReentrantLock

object PcArchive {
    private val lock = ReentrantLock()
    @Volatile var status = "PC 전송 대기"
        private set

    /** Source files stay on the phone. Stop at a bounded batch or first network error. */
    fun sync(context: Context, cancelled: () -> Boolean = { false }): Boolean {
        if (!lock.tryLock()) return true
        try {
            val endpoint = ArchiveConfig.endpoint(context)
            if (endpoint == null) { status = "PC 연결 설정 필요"; return true }
            val folder = DriveArchive.root(context)
            val receipts = File(context.noBackupFilesDir, "pc_receipts/${endpoint.receiverId}").apply { mkdirs() }
            val manifests = folder.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{32}\\.manifest\\.json")) }?.sortedBy { it.name } ?: emptyList()
            var completed = 0
            val pending = mutableListOf<Pair<ChunkInfo, File>>()
            var damaged = 0
            for (manifest in manifests) {
                try {
                    val row = JSONObject(manifest.readText())
                    val info = ChunkInfo(row.getString("id"), row.getLong("bytes"), row.getString("sha256"), row.optString("reason", "phone_upload"))
                    require(manifest.name == "${info.id}.manifest.json")
                    val receipt = File(receipts, "${info.id}.json")
                    val ack = try { JSONObject(receipt.readText()) } catch (_: Exception) { null }
                    if (ack != null && ack.optString("sha256") == info.sha256 && ack.optLong("bytes") == info.bytes) completed++
                    else pending.add(info to File(folder, "${info.id}.jsonl.gz"))
                } catch (_: Exception) { damaged++ }
            }
            val prefs = context.getSharedPreferences("archive_status", Context.MODE_PRIVATE)
            var last = prefs.getLong("last_pc_success", 0)
            var sent = 0
            val deadline = android.os.SystemClock.elapsedRealtime() + 120_000
            for ((info, file) in pending) {
                if (cancelled() || android.os.SystemClock.elapsedRealtime() >= deadline || sent >= 24) break
                status = "PC 전송 중 · 완료 $completed / 대기 ${pending.size - sent}"
                try {
                    PcTransfer.upload(endpoint, info, file)
                    last = System.currentTimeMillis()
                    ArchiveConfig.writeAtomic(File(receipts, "${info.id}.json"), JSONObject()
                        .put("sha256", info.sha256).put("bytes", info.bytes).put("stored_at", last).toString())
                    prefs.edit().putLong("last_pc_success", last).apply()
                    completed++; sent++
                } catch (_: Exception) {
                    status = "PC 연결/전송 대기 · 완료 $completed / 대기 ${pending.size - sent} · ${lastText(last)}"
                    return false
                }
            }
            status = "PC 완료 $completed · 대기 ${pending.size - sent} · ${lastText(last)}" + if (damaged > 0) " · 기록 오류 $damaged" else ""
            return pending.size == sent
        } finally { lock.unlock() }
    }

    private fun lastText(time: Long) = if (time == 0L) "전송 이력 없음" else "최근 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))}"
}
