package com.carrot.hud

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A glanceable snapshot for the home-screen HUD widget. */
data class HudSnapshot(val online: Boolean, val speed: String, val setSpeed: String)

/** Network helpers for reaching a device's :7000 web server (+ auto-discovery). */
object Net {
    private fun base(d: Device) = "http://${d.ip}:${d.port}"

    private fun httpGet(url: String, timeoutMs: Int): String? {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
                requestMethod = "GET"; useCaches = false
                setRequestProperty("User-Agent", "CarrotHud")
            }
            if (c.responseCode != 200) null else c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun httpPostJson(url: String, body: String, timeoutMs: Int): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "CarrotHud")
            }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            c.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    fun isUp(device: Device, timeoutMs: Int = 1500): Boolean =
        httpGet("${base(device)}/api/heartbeat_status", timeoutMs) != null

    fun fetchSnapshot(device: Device, timeoutMs: Int = 2500): HudSnapshot {
        val txt = httpGet("${base(device)}/api/live_runtime", timeoutMs)
            ?: return HudSnapshot(false, "--", "--")
        return try {
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
            HudSnapshot(true, "--", "--")
        }
    }

    // ---------- driving mode ----------
    fun readMode(device: Device, timeoutMs: Int = 1500): Int {
        val txt = httpGet("${base(device)}/api/params_bulk?names=MyDrivingMode", timeoutMs) ?: return 0
        return try { JSONObject(txt).optJSONObject("values")?.optInt("MyDrivingMode", 0) ?: 0 }
        catch (e: Exception) { 0 }
    }

    fun setMode(device: Device, value: Int, timeoutMs: Int = 2500): Boolean =
        httpPostJson(
            "${base(device)}/api/param_set",
            JSONObject().put("name", "MyDrivingMode").put("value", value.toString()).toString(),
            timeoutMs
        )

    /** cycle 1->2->3->4->1 (same as the device's own logic); returns the new mode. */
    fun cycleMode(device: Device): Int {
        val cur = readMode(device)
        val next = if (cur in 1..4) cur % 4 + 1 else 1
        setMode(device, next)
        return next
    }

    // ---------- auto-discovery (hotspot IP keeps changing) ----------
    fun fetchDongle(ip: String, port: Int = 7000, timeoutMs: Int = 600): String? {
        val txt = httpGet("http://$ip:$port/api/params_bulk?names=DongleId", timeoutMs) ?: return null
        return try { JSONObject(txt).optJSONObject("values")?.optString("DongleId", "") } catch (e: Exception) { "" }
    }

    /** The phone's current Wi-Fi/hotspot IPv4 subnet prefix, e.g. "192.168.68". */
    fun wifiSubnet(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return null
            val lp = cm.getLinkProperties(net) ?: return null
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    val h = a.hostAddress ?: continue
                    val parts = h.split(".")
                    if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** All of the phone's site-local IPv4 /24 subnet prefixes (Wi-Fi + hotspot AP
     *  + mobile). Enumerating interfaces (not just activeNetwork) matters because
     *  when the phone itself is the hotspot, the AP subnet (192.168.x, where the
     *  comma lives) is NOT the active/internet network. */
    fun localSubnets(): List<String> {
        val subs = LinkedHashSet<String>()
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                try {
                    if (!iface.isUp || iface.isLoopback) continue
                } catch (e: Exception) { continue }
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val h = addr.hostAddress ?: continue
                        val p = h.split(".")
                        if (p.size == 4) subs.add("${p[0]}.${p[1]}.${p[2]}")
                    }
                }
            }
        } catch (e: Exception) {
        }
        return subs.toList()
    }

    /** Scan the phone's local /24 subnet(s) for a comma serving :7000; match the
     *  dongle if known (else accept any comma). Returns its IP or null. */
    fun discover(ctx: Context, wantDongle: String?, port: Int = 7000): String? {
        val subnets = localSubnets()
        if (subnets.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(64)
        val found = AtomicReference<String?>(null)
        val want = (wantDongle ?: "").trim()
        val latch = CountDownLatch(subnets.size * 254)
        for (subnet in subnets) {
            for (i in 1..254) {
                val ip = "$subnet.$i"
                pool.execute {
                    try {
                        if (found.get() == null) {
                            val dongle = fetchDongle(ip, port, 500)
                            if (dongle != null) {
                                if (want.isEmpty() || want == "UnregisteredDevice" || dongle == want) {
                                    found.compareAndSet(null, ip)
                                }
                            }
                        }
                    } catch (e: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
            }
        }
        try { latch.await(12, TimeUnit.SECONDS) } catch (e: Exception) {}
        pool.shutdownNow()
        return found.get()
    }

    /**
     * Return the device with a currently-reachable IP: the stored IP if it
     * answers, otherwise discover it on the LAN and persist the new IP.
     * Returns null if the device can't be found. Blocking — call off the UI thread.
     */
    fun resolve(ctx: Context, device: Device): Device? {
        if (isUp(device, 1200)) {
            if (device.dongle.isBlank()) {
                val d = fetchDongle(device.ip, device.port, 800)
                if (!d.isNullOrBlank()) DeviceStore.setDongle(ctx, device.id, d)
            }
            return device
        }
        val ip = discover(ctx, device.dongle.ifBlank { null }, device.port) ?: return null
        DeviceStore.setIp(ctx, device.id, ip)
        val updated = device.copy(ip = ip)
        if (updated.dongle.isBlank()) {
            val d = fetchDongle(ip, updated.port, 800)
            if (!d.isNullOrBlank()) DeviceStore.setDongle(ctx, device.id, d)
        }
        return updated
    }
}
