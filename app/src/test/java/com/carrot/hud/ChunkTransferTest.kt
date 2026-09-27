package com.carrot.hud

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ChunkTransferTest {
    private lateinit var root: File
    private lateinit var server: HttpServer
    private val payload = "record-payload".toByteArray()
    private lateinit var row: ChunkInfo
    private var requests = 0
    private var mode = "range"

    @Before fun setup() {
        root = Files.createTempDirectory("chunk-test").toFile()
        val sample = File(root, "sample").apply { writeBytes(payload) }
        row = ChunkInfo("a".repeat(32), payload.size.toLong(), ChunkTransfer.digest(sample))
        sample.delete()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/automatic_drive/chunks/${row.id}") { exchange ->
            requests++
            val range = exchange.requestHeaders.getFirst("Range")
            val offset = if (range == null || mode == "ignore") 0 else range.removePrefix("bytes=").removeSuffix("-").toInt()
            val body = if (mode == "corrupt") ByteArray(payload.size) else payload.copyOfRange(offset, payload.size)
            val partial = range != null && mode != "ignore"
            if (partial) exchange.responseHeaders.add("Content-Range",
                if (mode == "bad-range") "bytes 9-12/13" else "bytes $offset-${payload.size - 1}/${payload.size}")
            exchange.sendResponseHeaders(if (partial) 206 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After fun cleanup() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun fetch() = ChunkTransfer.download("http://127.0.0.1:${server.address.port}", root, row)

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
        assertThrows(IllegalStateException::class.java) { fetch() }
        assertFalse(File(root, "${row.id}.jsonl.gz").exists())
    }

    @Test fun badRangeIsRejected() {
        mode = "bad-range"
        File(root, "${row.id}.download").writeBytes(payload.copyOfRange(0, 3))
        assertThrows(IllegalArgumentException::class.java) { fetch() }
        assertFalse(File(root, "${row.id}.jsonl.gz").exists())
    }

    @Test fun invalidIdentifiersAndOversizeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ChunkInfo("../evil", 10, row.sha256) }
        assertThrows(IllegalArgumentException::class.java) { ChunkInfo(row.id, 5L * 1024 * 1024, row.sha256) }
    }
}
