package com.carrot.hud

import java.net.HttpURLConnection
import java.net.URL

/** Lightweight reachability check for a device's :7000 web server. */
object Net {
    /**
     * True if the device's carrot web server answers at all — i.e. the device
     * is on the network and you can open its settings — regardless of whether
     * the car/CAN is connected (the HUD may still be empty).
     */
    fun isUp(device: Device, timeoutMs: Int = 1500): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://${device.ip}:${device.port}/api/heartbeat_status")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                useCaches = false
            }
            val code = conn.responseCode
            code in 200..599 // any HTTP reply => device reachable
        } catch (e: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
