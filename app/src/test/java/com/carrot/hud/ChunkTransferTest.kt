package com.carrot.hud

import java.io.File
import java.net.ServerSocket
import java.net.InetAddress
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChunkTransferTest {
    private lateinit var root: File
    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private val payload = "record-payload".toByteArray()
    private lateinit var row: ChunkInfo
    @Volatile private var requests = 0
    @Volatile private var mode = "range"

    @Before fun setup() {
        root = Files.createTempDirectory("chunk-test").toFile()
        val sample = File(root, "sample").apply { writeBytes(payload) }
        row = ChunkInfo("a".repeat(32), payload.size.toLong(), ChunkTransfer.digest(sample))
        sample.delete()
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader()
                        input.readLine()
                        var range: String? = null
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                        }
                        requests++
                        val offset = if (range == null || mode == "ignore") 0 else range!!.removePrefix("bytes=").removeSuffix("-").toInt()
                        val body = if (mode == "corrupt") ByteArray(payload.size) else payload.copyOfRange(offset, payload.size)
                        val partial = range != null && mode != "ignore"
                        val contentRange = if (!partial) "" else "Content-Range: " +
                            (if (mode == "bad-range") "bytes 9-12/13" else "bytes $offset-${payload.size - 1}/${payload.size}") + "\r\n"
                        val headers = "HTTP/1.1 ${if (mode == "http-error") "500 Server Error" else if (partial) "206 Partial Content" else "200 OK"}\r\n" +
                            "Content-Length: ${body.size}\r\n${contentRange}Connection: close\r\n\r\n"
                        socket.getOutputStream().apply { write(headers.toByteArray()); write(body); flush() }
                    }
                } catch (error: Exception) {
                    if (!server.isClosed) throw error
                }
            }
        }
        worker.start()
    }

    @After fun cleanup() {
        server.close()
        worker.join(3000)
        root.deleteRecursively()
    }

    private fun fetch() = ChunkTransfer.download("http://127.0.0.1:${server.localPort}", root, row)

    @Test fun resumeAndDeduplicate() {
        File(root, "${row.id}.download").writeBytes(payload.copyOfRange(0, 3))
        assertArrayEquals(payload, fetch().readBytes())
        assertArrayEquals(payload, fetch().readBytes())
        assertEquals(1, requests)
    }

    @Test fun serverIgnoringRangeReplacesPartial() {
        mode = "ignore"
        File(root, "${row.id}.download").writeBytes(payload.copyOfRange(0, 3))
        assertArrayEquals(payload, fetch().readBytes())
    }

    @Test fun badHashIsNeverPublished() {
        mode = "corrupt"
        assertThrows(ChunkIntegrityException::class.java) { fetch() }
        assertFalse(File(root, "${row.id}.jsonl.gz").exists())
    }

    @Test fun badRangeIsRejected() {
        mode = "bad-range"
        File(root, "${row.id}.download").writeBytes(payload.copyOfRange(0, 3))
        assertThrows(ChunkIntegrityException::class.java) { fetch() }
        assertFalse(File(root, "${row.id}.jsonl.gz").exists())
        assertFalse(File(root, "${row.id}.download").exists())
        mode = "range"
        assertArrayEquals(payload, fetch().readBytes())
    }

    @Test fun noResponseIsConnectionFailureAndDoesNotPublish() {
        server.close(); worker.join(3000)
        assertThrows(ChunkConnectionException::class.java) { fetch() }
        assertFalse(File(root, "${row.id}.jsonl.gz").exists())
    }
    @Test fun serverErrorIsNotHashFailureAndNeverPublishes() {
        mode = "http-error"
        val error = assertThrows(ChunkServerException::class.java) { fetch() }
        assertEquals(500, error.status)
        assertFalse(File(root, "${row.id}.jsonl.gz").exists())
    }
    @Test fun localDirectoryFailureIsTypedStorageAndMakesNoRequest() {
        val blocked = File(root, "not-a-directory").apply { writeText("preserved") }
        assertThrows(ChunkStorageException::class.java) { ChunkTransfer.download("http://127.0.0.1:${server.localPort}", blocked, row) }
        assertEquals(0, requests)
        assertEquals("preserved", blocked.readText())
    }
    @Test fun invalidIdentifiersAndOversizeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChunkInfo("../evil", 10, row.sha256) }
        assertThrows(IllegalArgumentException::class.java) { ChunkInfo(row.id, 5L * 1024 * 1024, row.sha256) }
    }
}
