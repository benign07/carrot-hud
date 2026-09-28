package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.nio.file.Files

class PcTransferTest {
    private val endpoint = PcEndpoint("http://100.114.242.2:7041", "t".repeat(43), "b".repeat(32))
    private class Connection(val reply: String, val code: Int = 201) : HttpURLConnection(URL("http://127.0.0.1")) {
        val body = ByteArrayOutputStream()
        var disconnected = false
        override fun getOutputStream(): OutputStream = body
        override fun getInputStream(): InputStream = reply.byteInputStream()
        override fun getResponseCode() = code
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }
    private fun withRecord(test: (File, ChunkInfo, JSONObject) -> Unit) {
        val root = Files.createTempDirectory("pc-upload-test").toFile()
        try {
            val file = File(root, "record").apply { writeText("test record") }
            val info = ChunkInfo("a".repeat(32), file.length(), ChunkTransfer.digest(file))
            val receipt = JSONObject().put("stored", true).put("receiver_id", endpoint.receiverId)
                .put("id", info.id).put("bytes", info.bytes).put("sha256", info.sha256)
            test(file, info, receipt)
        } finally { root.deleteRecursively() }
    }
    @Test fun matchingReceiptAndCredentialsAreRequired() = withRecord { file, info, receipt ->
        val connection = Connection(receipt.toString())
        PcTransfer.upload(endpoint, info, file) { url ->
            assertEquals("${endpoint.url}/v1/chunks/${info.id}", url.toString()); connection
        }
        assertArrayEquals(file.readBytes(), connection.body.toByteArray())
        assertEquals("Bearer ${endpoint.token}", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        assertTrue(file.exists())
    }
    @Test fun wrongOrMissingReceiptNeverCountsAsSuccess() = withRecord { file, info, receipt ->
        for (bad in listOf(receipt.toString().replace(info.sha256, "c".repeat(64)), "{}", "x".repeat(4097))) {
            assertThrows(Exception::class.java) { PcTransfer.upload(endpoint, info, file) { Connection(bad) } }
        }
        assertTrue(file.exists())
    }
    @Test fun offlineUnauthorizedOrRedirectPreservesSource() = withRecord { file, info, receipt ->
        for (code in listOf(301, 401, 403, 409, 507)) {
            assertThrows(IllegalArgumentException::class.java) { PcTransfer.upload(endpoint, info, file) { Connection(receipt.toString(), code) } }
        }
        assertThrows(IOException::class.java) { PcTransfer.upload(endpoint, info, file) { throw IOException("offline") } }
        assertTrue(ChunkTransfer.verified(file, info))
    }
    @Test fun corruptedLocalFileIsNotSent() = withRecord { file, info, _ ->
        file.writeText("corrupt")
        assertThrows(IllegalArgumentException::class.java) { PcTransfer.upload(endpoint, info, file) { error("must not connect") } }
    }
    @Test fun pairingRestrictsDestinationAndRejectsCredentialInjection() {
        for (url in listOf("http://example.com:7041", "http://192.168.0.1:7041", "http://100.114.242.2:7041/evil", "http://user@100.114.242.2:7041", "http://100.114.242.2:7041?x=1")) {
            assertThrows(IllegalArgumentException::class.java) { PcEndpoint(url, endpoint.token, endpoint.receiverId) }
        }
        assertThrows(IllegalArgumentException::class.java) { PcEndpoint(endpoint.url, "key\r\nHeader: value", endpoint.receiverId) }
        val raw = JSONObject().put("schema", 1).put("url", endpoint.url).put("token", endpoint.token).put("receiver_id", endpoint.receiverId).toString()
        assertEquals(endpoint, PcEndpoint.parse(raw))
    }
}
