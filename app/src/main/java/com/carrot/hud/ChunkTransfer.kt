package com.carrot.hud

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ChunkInfo(val id: String, val bytes: Long, val sha256: String) {
    init {
        require(id.matches(Regex("[a-f0-9]{32}")))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes in 1..4L * 1024 * 1024)
    }
}

/** Immutable chunk transfer. Only a matching length AND hash become a final file. */
object ChunkTransfer {
    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun verified(file: File, row: ChunkInfo): Boolean =
        file.isFile && file.length() == row.bytes && digest(file) == row.sha256

    fun download(base: String, folder: File, row: ChunkInfo): File {
        folder.mkdirs()
        val target = File(folder, "${row.id}.jsonl.gz")
        if (verified(target, row)) return target
        val partial = File(folder, "${row.id}.download")
        var offset = if (partial.exists()) partial.length() else 0L
        if (offset >= row.bytes) { check(partial.delete()); offset = 0 }
        val connection = URL("${base.trimEnd('/')}/api/automatic_drive/chunks/${row.id}")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            val status = connection.responseCode
            require(status == 200 || status == 206) { "HTTP $status" }
            if (status == 206) {
                require(connection.getHeaderField("Content-Range") == "bytes $offset-${row.bytes - 1}/${row.bytes}")
            } else offset = 0
            var received = offset
            FileOutputStream(partial, offset > 0).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        require(received <= row.bytes) { "Oversized chunk" }
                        output.write(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            if (!verified(partial, row)) {
                partial.delete()
                error("Hash verification failed")
            }
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return target
        } finally {
            connection.disconnect()
        }
    }
}
