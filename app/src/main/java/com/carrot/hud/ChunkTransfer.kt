package com.carrot.hud

import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ChunkInfo(val id: String, val bytes: Long, val sha256: String, val reason: String = "phone_upload") {
    init {
        require(id.matches(Regex("[a-f0-9]{32}")))
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(bytes in 1..4L * 1024 * 1024)
        require(reason.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
    }
}

class ChunkServerException(val status: Int) : IOException("HTTP $status")
class ChunkStorageException(cause: IOException) : IOException("Phone record storage failed", cause)
class ChunkIntegrityException(message: String) : IllegalStateException(message)

/** Immutable chunk transfer. Only a matching length AND hash become a final file. */
object ChunkTransfer {
    private fun <T> storage(action: () -> T): T = try { action() }
        catch (error: IOException) { throw ChunkStorageException(error) }
    private fun removePartial(file: File) {
        if (file.exists() && !file.delete()) throw ChunkStorageException(IOException("Partial file could not be removed"))
    }
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
        if (!folder.isDirectory && !folder.mkdirs()) throw ChunkStorageException(IOException("Record directory unavailable"))
        val target = File(folder, "${row.id}.jsonl.gz")
        if (storage { verified(target, row) }) return target
        val partial = File(folder, "${row.id}.download")
        var offset = if (partial.exists()) partial.length() else 0L
        if (offset >= row.bytes) { removePartial(partial); offset = 0 }
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
            if (status != 200 && status != 206) throw ChunkServerException(status)
            if (status == 206) {
                if (connection.getHeaderField("Content-Range") != "bytes $offset-${row.bytes - 1}/${row.bytes}") throw ChunkIntegrityException("Content-Range mismatch")
            } else offset = 0
            var received = offset
            val output = storage { FileOutputStream(partial, offset > 0) }
            try {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > row.bytes) throw ChunkIntegrityException("Oversized chunk")
                        storage { output.write(buffer, 0, count) }
                    }
                }
                storage { output.fd.sync() }
            } finally { storage { output.close() } }
            if (!storage { verified(partial, row) }) {
                removePartial(partial)
                throw ChunkIntegrityException("Hash verification failed")
            }
            storage { java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            return target
        } finally {
            connection.disconnect()
        }
    }
}
