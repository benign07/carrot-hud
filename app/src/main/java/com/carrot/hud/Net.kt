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
data class HudSnapshot(val online: Boolean, val speed: String, val setSpeed: String, val dataFresh: Boolean = false)

/** Network helpers for reaching a device's :7000 web server (+ auto-discovery). */
object Net {
    private fun base(d: Device) = "http://${d.ip}:${d.port}"

    private data class HttpReply(val code: Int, val body: String?)

    private fun httpReply(url: String, timeoutMs: Int): HttpReply? {
        var c: HttpURLConnection? = null
        var code: Int? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs
                requestMethod = "GET"; useCaches = false
                setRequestProperty("User-Agent", "CarrotHud")
            }
            val receivedCode = c.responseCode
            code = receivedCode
            HttpReply(receivedCode, if (receivedCode == 200) c.inputStream.use { it.readBytesLimited(2 * 1024 * 1024).toString(Charsets.UTF_8) } else null)
        } catch (e: Exception) {
            code?.let { HttpReply(it, null) }
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? = httpReply(url, timeoutMs)?.takeIf { it.code == 200 }?.body

    fun isUp(device: Device, timeoutMs: Int = 1500): Boolean =
        httpReply("${base(device)}/api/heartbeat_status", timeoutMs) != null

    fun fetchSnapshot(device: Device, timeoutMs: Int = 2500): HudSnapshot {
        val reply = httpReply("${base(device)}/api/live_runtime", timeoutMs)
            ?: return HudSnapshot(false, "--", "--")
        // A 503 broker/startup response proves the server is reachable, while
        // containing no valid driving data. Do not label it vehicle offline.
        return if (reply.code == 200 && reply.body != null) parseSnapshot(reply.body) else HudSnapshot(true, "--", "--")
    }

    /** HTTP reachability is independent of valid, recent vehicle publications. */
    internal fun parseSnapshot(txt: String): HudSnapshot {
        val waiting = HudSnapshot(true, "--", "--")
        return try {
            val payload = JSONObject(txt)
            if (payload.opt("ok") != true) return waiting
            val runtime = payload.optJSONObject("runtime") ?: return waiting
            val snapshotAge = (payload.opt("snapshotAgeMs") as? Number)?.toDouble() ?: return waiting
            if (!snapshotAge.isFinite() || snapshotAge !in 0.0..1000.0) return waiting
            fun fresh(name: String): Boolean {
                val valid = runtime.optJSONObject("serviceValid")?.opt(name) == true
                val age = (runtime.optJSONObject("serviceAgeMs")?.opt(name) as? Number)?.toDouble()
                return valid && age != null && age.isFinite() && age in 0.0..1000.0
            }
            if (!fresh("carState")) return waiting
            val services = payload.optJSONObject("services") ?: return waiting
            val cs = services.optJSONObject("carState") ?: return waiting
            if (cs.opt("canValid") != true || cs.opt("canTimeout") == true) return waiting
            fun number(row: JSONObject, name: String): Double? =
                (row.opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }
            val speed = number(cs, "vEgoCluster") ?: number(cs, "vEgo") ?: return waiting
            // The actual compact server schema carries vCruiseCluster in km/h;
            // legacy/full schemas may additionally carry cruiseState.speed m/s.
            val cluster = number(cs, "vCruiseCluster")?.takeIf { it > 0 && it < 255 }
            val cruise = cs.optJSONObject("cruiseState")?.let { number(it, "speed") }?.takeIf { it > 0 }
            val desired = if (fresh("carrotMan")) services.optJSONObject("carrotMan")?.let { number(it, "desiredSpeed") }?.takeIf { it > 0 } else null
            val set = cluster?.let { Math.round(it).toString() } ?: cruise?.let { Math.round(it * 3.6).toString() } ?: desired?.let { Math.round(it).toString() } ?: "--"
            HudSnapshot(true, Math.round(speed * 3.6).toString(), set, true)
        } catch (_: Exception) { waiting }
    }

    // ---------- auto-discovery (hotspot IP keeps changing) ----------
    fun fetchDongle(ip: String, port: Int = 7000, timeoutMs: Int = 600): String? {
        val txt = httpGet("http://$ip:$port/api/params_bulk?names=DongleId", timeoutMs) ?: return null
        return parseDongle(txt)
    }

    internal fun parseDongle(txt: String): String? {
        return try {
            (JSONObject(txt).optJSONObject("values")?.opt("DongleId") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
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
        // A Tailscale address is stable. Do not replace it with an unrelated LAN
        // device while the registered OP or VPN is temporarily offline.
        val parts = device.ip.split('.').map { it.toIntOrNull() ?: -1 }
        if (parts.size == 4 && parts[0] == 100 && parts[1] in 64..127 && parts.all { it in 0..255 }) return null
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
