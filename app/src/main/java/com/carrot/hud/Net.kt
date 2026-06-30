package com.carrot.hud

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A glanceable snapshot of a device for the home-screen widget. */
data class HudSnapshot(val online: Boolean, val speed: String, val setSpeed: String)

/** Lightweight network helpers for reaching a device's :7000 web server. */
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

    /**
     * Best-effort current/set speed snapshot from /api/live_runtime (decoded
     * JSON). Returns "--" for values that aren't available (e.g. car off).
     */
    fun fetchSnapshot(device: Device, timeoutMs: Int = 2500): HudSnapshot {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://${device.ip}:${device.port}/api/live_runtime")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", "CarrotHud")
            }
            if (conn.responseCode != 200) return HudSnapshot(false, "--", "--")
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val services = JSONObject(txt).optJSONObject("services") ?: JSONObject()
            val cs = services.optJSONObject("carState")
            val speed = cs?.let {
                val v = it.optDouble("vEgoCluster", it.optDouble("vEgo", Double.NaN))
                if (v.isNaN()) null else Math.round(v * 3.6).toString()
            } ?: "--"
            val set = cs?.optJSONObject("cruiseState")?.let {
                val v = it.optDouble("speed", Double.NaN)
                if (v.isNaN() || v <= 0) null else Math.round(v * 3.6).toString()
            } ?: services.optJSONObject("carrotMan")?.let {
                val v = it.optDouble("desiredSpeed", Double.NaN)
                if (v.isNaN() || v <= 0) null else Math.round(v).toString()
            } ?: "--"
            HudSnapshot(true, speed, set)
        } catch (e: Exception) {
            HudSnapshot(false, "--", "--")
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
