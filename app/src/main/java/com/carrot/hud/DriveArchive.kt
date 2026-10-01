package com.carrot.hud

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.locks.ReentrantLock

/** One native downloader shared by the visible HUD and existing overlay service. */
object DriveArchive {
    private val owners = mutableSetOf<Any>()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null
    private val checked = mutableMapOf<String, String>() // touched only by the single worker
    private val syncLock = ReentrantLock()
    private var cursor = ArchiveBatchCursor()
    private var cursorEndpoint = ""
    @Volatile var status = "오파 자동 연결 대기"
        private set

    fun root(context: Context) = File(context.noBackupFilesDir, "drive_records")

    @Synchronized fun attach(context: Context, owner: Any) {
        owners.add(owner)
        if (future == null) {
            val app = context.applicationContext
            future = executor.scheduleWithFixedDelay({ syncOnce(app) { !active() } }, 0, 20, TimeUnit.SECONDS)
        }
    }

    @Synchronized fun detach(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) {
            future?.cancel(false)
            future = null
            status = "휴대폰 보관 대기 · 예약 연결 또는 HUD에서 재개"
        }
    }

    @Synchronized private fun active() = owners.isNotEmpty()

    fun syncOnce(context: Context, cancelled: () -> Boolean = { false }) {
        if (cancelled() || !syncLock.tryLock()) return
        var deviceResponded = false
        var stage = ArchiveStage.STORAGE
        try {
            val device = DeviceStore.getActive(context) ?: run { status = "오파 기기 등록 필요"; return }
            val folder = root(context)
            if (!folder.isDirectory && !folder.mkdirs()) throw java.io.IOException("record directory unavailable")
            stage = ArchiveStage.INDEX
            val base = "http://${device.ip}:${device.port}"
            val connection = URL("$base/api/automatic_drive/chunks").openConnection() as HttpURLConnection
            val raw = try {
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                val response = connection.responseCode
                deviceResponded = true
                if (response == 404) {
                    status = "기기 자동 기록 업데이트 대기"
                    return
                }
                require(response == 200)
                connection.inputStream.use { stream ->
                    val bytes = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(bytes.size() + count <= 8 * 1024 * 1024)
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                }
            } finally { connection.disconnect() }
            val rows = JSONObject(raw).getJSONArray("chunks")
            OpUpdateMonitor.connected(context)
            var bytesUsed = folder.listFiles()?.sumOf { it.length() } ?: 0L
            var downloaded = 0
            var chunkFailures = 0
            var lastChunkError: Exception? = null
            var storageFull = false
            if (cursorEndpoint != base) { cursorEndpoint = base; cursor = ArchiveBatchCursor() }
            cursor.run(rows.length(), cancelled = cancelled) { i ->
              try {
                stage = ArchiveStage.DOWNLOAD
                val row = rows.getJSONObject(i)
                val info = ChunkInfo(row.getString("id"), row.getLong("bytes"), row.getString("sha256"))
                val target = File(folder, "${info.id}.jsonl.gz")
                val signature = "${info.sha256}:${target.length()}:${target.lastModified()}"
                if (checked[info.id] == signature) return@run ArchiveBatchCursor.Visit.CACHED
                if (!ChunkTransfer.verified(target, info)) {
                    if (bytesUsed + info.bytes > 2L * 1024 * 1024 * 1024 || folder.usableSpace < 256L * 1024 * 1024 + info.bytes) {
                        storageFull = true
                        return@run ArchiveBatchCursor.Visit.STOP
                    }
                    status = "주행 기록 자동 보관 중…"
                    ChunkTransfer.download(base, folder, info)
                    bytesUsed += info.bytes
                    downloaded++
                }
                stage = ArchiveStage.MANIFEST
                val manifest = File(folder, "${info.id}.manifest.json")
                val temporary = File(folder, "${info.id}.manifest.tmp")
                FileOutputStream(temporary).use { stream ->
                    stream.write(row.toString().toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                java.nio.file.Files.move(temporary.toPath(), manifest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                checked[info.id] = "${info.sha256}:${target.length()}:${target.lastModified()}"
                ArchiveBatchCursor.Visit.ATTEMPTED
              } catch (error: Exception) {
                // Keep local storage failures fatal; preserve old files and pause.
                if (error is ChunkStorageException || stage == ArchiveStage.MANIFEST) throw error
                if (error !is ChunkServerException && error !is ChunkIntegrityException &&
                    error !is org.json.JSONException && error !is IllegalArgumentException &&
                    error !is java.io.IOException) throw error
                chunkFailures++
                lastChunkError = error
                ArchiveBatchCursor.Visit.ATTEMPTED
              }
            }
            if (downloaded > 0) ArchiveJobs.request(context)
            if (storageFull) { status = "보관 공간 부족 · 기존 기록 보존 중 · PC로 내보내세요"; return }
            val count = folder.listFiles()?.count { it.name.endsWith(".manifest.json") } ?: 0
            status = "휴대폰 보관 $count 개 · ${bytesUsed / 1048576} MB · 기기 연결됨"
            lastChunkError?.let { status = ArchiveFailure.message(it, ArchiveStage.DOWNLOAD, true, count) + " · 실패 $chunkFailures 개, 다음 기록 계속 보관" }
        } catch (error: Exception) {
            if (!deviceResponded && stage == ArchiveStage.INDEX) OpUpdateMonitor.disconnected()
            val count = root(context).listFiles()?.count { it.name.endsWith(".manifest.json") } ?: 0
            status = ArchiveFailure.message(error, stage, deviceResponded, count)
        } finally { syncLock.unlock(); ArchiveWidgetStatus.publish(context) }
    }

    /** SAF destination selected by the user; source chunks remain on both devices. */
    fun export(context: Context, destination: Uri) {
        val folder = root(context)
        val manifests = folder.listFiles()?.filter { it.name.endsWith(".manifest.json") } ?: emptyList()
        require(manifests.isNotEmpty()) { "보관된 기록이 없습니다" }
        val output = context.contentResolver.openOutputStream(destination) ?: error("파일을 열 수 없습니다")
        ZipOutputStream(output).use { zip ->
            zip.setLevel(0) // Chunks are already compressed.
            for (manifest in manifests) {
                val row = JSONObject(manifest.readText())
                val info = ChunkInfo(row.getString("id"), row.getLong("bytes"), row.getString("sha256"))
                val chunk = File(folder, "${info.id}.jsonl.gz")
                require(ChunkTransfer.verified(chunk, info)) { "기록 무결성 확인 실패" }
                for (file in listOf(manifest, chunk)) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }
}
