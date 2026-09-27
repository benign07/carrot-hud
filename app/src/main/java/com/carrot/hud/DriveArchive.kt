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

/** One native downloader shared by the visible HUD and existing overlay service. */
object DriveArchive {
    private val owners = mutableSetOf<Any>()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null
    private val checked = mutableMapOf<String, String>() // touched only by the single worker
    @Volatile var status = "자동 보관 대기 · HUD/오버레이 사용 중 보관"
        private set

    fun root(context: Context) = File(context.noBackupFilesDir, "drive_records")

    @Synchronized fun attach(context: Context, owner: Any) {
        owners.add(owner)
        if (future == null) {
            val app = context.applicationContext
            future = executor.scheduleWithFixedDelay({ sync(app) }, 0, 20, TimeUnit.SECONDS)
        }
    }

    @Synchronized fun detach(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) {
            future?.cancel(false)
            future = null
            status = "휴대폰 보관 대기 · 기기 자동 기록은 계속됩니다"
        }
    }

    @Synchronized private fun active() = owners.isNotEmpty()

    private fun sync(context: Context) {
        if (!active()) return
        try {
            val device = DeviceStore.getActive(context) ?: return
            val folder = root(context)
            folder.mkdirs()
            val base = "http://${device.ip}:${device.port}"
            val connection = URL("$base/api/automatic_drive/chunks").openConnection() as HttpURLConnection
            val raw = try {
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                if (connection.responseCode == 404) {
                    status = "기기 자동 기록 업데이트 대기"
                    return
                }
                require(connection.responseCode == 200)
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
            var bytesUsed = folder.listFiles()?.sumOf { it.length() } ?: 0L
            var downloaded = 0
            for (i in 0 until rows.length()) {
                if (!active()) break
                val row = rows.getJSONObject(i)
                val info = ChunkInfo(row.getString("id"), row.getLong("bytes"), row.getString("sha256"))
                val target = File(folder, "${info.id}.jsonl.gz")
                val signature = "${info.sha256}:${target.length()}:${target.lastModified()}"
                if (checked[info.id] == signature) continue
                if (!ChunkTransfer.verified(target, info)) {
                    if (bytesUsed + info.bytes > 2L * 1024 * 1024 * 1024 || folder.usableSpace < 256L * 1024 * 1024 + info.bytes) {
                        status = "보관 공간 부족 · 기존 기록 보존 중 · PC로 내보내세요"
                        return
                    }
                    status = "주행 기록 자동 보관 중…"
                    ChunkTransfer.download(base, folder, info)
                    bytesUsed += info.bytes
                    downloaded++
                }
                val manifest = File(folder, "${info.id}.manifest.json")
                val temporary = File(folder, "${info.id}.manifest.tmp")
                FileOutputStream(temporary).use { stream ->
                    stream.write(row.toString().toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                java.nio.file.Files.move(temporary.toPath(), manifest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                checked[info.id] = "${info.sha256}:${target.length()}:${target.lastModified()}"
                if (downloaded >= 6) break
            }
            val count = folder.listFiles()?.count { it.name.endsWith(".manifest.json") } ?: 0
            status = "휴대폰 보관 $count 개 · ${bytesUsed / 1048576} MB · 기기 연결됨"
        } catch (_: Exception) {
            status = "기기 연결/전송 대기 · 다음 연결 시 자동으로 이어받습니다"
        }
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
