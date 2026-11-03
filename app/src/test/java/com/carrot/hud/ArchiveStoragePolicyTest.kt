package com.carrot.hud

import android.content.Context
import java.net.ServerSocket
import java.net.InetAddress
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ArchiveStoragePolicyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("archive_control", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("carrot_hud", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun localStorageDefaultsOffWithoutDisablingConnections() {
        assertFalse(ArchiveConfig.storeOnPhone(context))
        assertTrue(ArchiveConfig.enabled(context))
        ArchiveConfig.setStoreOnPhone(context, true)
        ArchiveConfig.setStoreOnPhone(context, false)
        assertFalse(ArchiveConfig.storeOnPhone(context))
        assertTrue(ArchiveConfig.enabled(context))
    }

    @Test fun disabledStorageStillReadsHealthButNeverFetchesArchiveIndex() {
        val paths = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                try { server.accept().use { request ->
                    request.soTimeout = 1000
                    val input = request.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    while (!input.readLine().isNullOrEmpty()) { }
                    paths.add(path)
                    val health = path == "/api/automatic_drive/status"
                    val body = (if (health) "{\"state\":\"waiting\"}" else "{}").toByteArray()
                    request.getOutputStream().write(("HTTP/1.1 ${if (health) "200 OK" else "404 Not Found"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body)
                } } catch (_: IOException) { if (server.isClosed) break }
            }
        }
        try {
            DeviceStore.addDevice(context, "Storage policy test", "127.0.0.1", server.localPort)
            val existed = DriveArchive.root(context).exists()
            DriveArchive.syncOnce(context)
            assertTrue(paths.contains("/api/automatic_drive/status"))
            assertFalse(paths.any { it.startsWith("/api/automatic_drive/chunks") })
            assertEquals(existed, DriveArchive.root(context).exists())
            assertTrue(DriveArchive.status.contains("휴대폰 보관 꺼짐"))
        } finally { server.close(); worker.join(2000) }
    }
}
