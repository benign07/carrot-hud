package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.InetAddress

class NetSnapshotTest {
    @Test fun heartbeatServerErrorsStillMeanTheRegisteredDeviceIsReachable() {
        for ((code, validHeartbeat) in listOf(200 to true, 200 to false, 500 to false, 503 to false, 301 to false, 400 to false, 401 to false, 403 to false, 404 to false)) {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val worker = Thread { server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                val body = (if (validHeartbeat) "{\"ok\":true,\"hb\":null}" else "<html>unrelated server</html>").toByteArray()
                socket.getOutputStream().apply { write("HTTP/1.1 $code Response\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush() }
            } }
            worker.start(); val port = server.localPort
            try { assertEquals(code in listOf(500, 503) || code == 200 && validHeartbeat, Net.isUp(Device("fixture", "fixture", "127.0.0.1", port))) }
            finally { server.close(); worker.join(3000) }
            assertFalse(Net.isUp(Device("fixture", "fixture", "127.0.0.1", port), 500))
        }
    }
    @Test fun brokerErrorResponseIsReachableButHasNoDrivingData() {
        for (code in listOf(200, 500, 503)) {
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val raw = if (code == 200) payload().toString() else "{\"ok\":false,\"error\":\"broker unavailable\"}"
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val body = raw.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $code Response\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            }
            worker.start()
            val port = server.localPort
            try {
                val result = Net.fetchSnapshot(Device("fixture", "fixture", "127.0.0.1", port))
                assertTrue(result.online)
                assertEquals(code == 200, result.dataFresh)
                if (code != 200) assertEquals("--", result.speed)
            } finally { server.close(); worker.join(3000) }
            assertFalse(Net.fetchSnapshot(Device("fixture", "fixture", "127.0.0.1", port), 500).online)
        }
    }
    private fun payload(): JSONObject = JSONObject().put("ok", true).put("snapshotAgeMs", 10)
        .put("runtime", JSONObject()
            .put("serviceValid", JSONObject().put("carState", true).put("carrotMan", true))
            .put("serviceAgeMs", JSONObject().put("carState", 10).put("carrotMan", 10)))
        .put("services", JSONObject()
            .put("carState", JSONObject().put("canValid", true).put("vEgoCluster", 10.0).put("vEgo", 9.0).put("vCruiseCluster", 80.0))
            .put("carrotMan", JSONObject().put("desiredSpeed", 72.0)))
    private fun parse(row: JSONObject) = Net.parseSnapshot(row.toString())
    private fun assertWaiting(row: JSONObject) {
        val result = parse(row)
        assertTrue(result.online); assertFalse(result.dataFresh)
        assertEquals("--", result.speed); assertEquals("--", result.setSpeed)
    }

    @Test fun actualCompactServerSchemaUsesClusterSpeedAndKphSetSpeed() {
        val snapshot = parse(payload())
        assertTrue(snapshot.online); assertTrue(snapshot.dataFresh)
        assertEquals("36", snapshot.speed); assertEquals("80", snapshot.setSpeed)
    }
    @Test fun staleInvalidFutureOrAbsentCarPublicationNeverShowsCachedSpeeds() {
        for (age in listOf(-1, 1001)) {
            val row = payload()
            row.getJSONObject("runtime").getJSONObject("serviceAgeMs").put("carState", age)
            assertWaiting(row)
        }
        val invalid = payload()
        invalid.getJSONObject("runtime").getJSONObject("serviceValid").put("carState", false)
        assertWaiting(invalid)
        assertWaiting(payload().put("runtime", JSONObject()))
        val malformed = payload()
        malformed.getJSONObject("runtime").getJSONObject("serviceAgeMs").put("carState", "NaN")
        assertWaiting(malformed)
    }
    @Test fun staleOrMalformedSnapshotCannotClaimFreshData() {
        for (age in listOf(-1, 1001, "Infinity")) assertWaiting(payload().put("snapshotAgeMs", age))
        assertWaiting(payload().put("ok", false))
        val row = payload(); row.remove("snapshotAgeMs"); assertWaiting(row)
        assertFalse(Net.parseSnapshot("not json").dataFresh)
        assertTrue(Net.parseSnapshot("not json").online)
    }
    @Test fun freshProcessWithMissingOrInvalidCanCannotShowCachedSpeed() {
        val invalid = payload(); invalid.getJSONObject("services").getJSONObject("carState").put("canValid", false)
        assertWaiting(invalid)
        val timedOut = payload(); timedOut.getJSONObject("services").getJSONObject("carState").put("canTimeout", true)
        assertWaiting(timedOut)
    }
    @Test fun fallbackDesiredSpeedAlsoRequiresItsOwnFreshPublication() {
        val row = payload(); row.getJSONObject("services").getJSONObject("carState").put("vCruiseCluster", 255)
        assertEquals("72", parse(row).setSpeed)
        row.getJSONObject("runtime").getJSONObject("serviceAgeMs").put("carrotMan", 1001)
        assertEquals("--", parse(row).setSpeed)
        assertEquals("36", parse(row).speed)
    }
    @Test fun fullLegacySchemaRetainsCruiseMpsConversion() {
        val row = payload(); val car = row.getJSONObject("services").getJSONObject("carState")
        car.remove("vCruiseCluster"); car.put("cruiseState", JSONObject().put("speed", 20.0))
        assertEquals("72", parse(row).setSpeed)
    }
    @Test fun unavailableClusterFallsBackToFiniteVehicleSpeedAndZeroIsValid() {
        val row = payload(); val car = row.getJSONObject("services").getJSONObject("carState")
        car.put("vEgoCluster", JSONObject.NULL)
        assertEquals("32", parse(row).speed)
        car.put("vEgo", 0.0)
        assertTrue(parse(row).dataFresh); assertEquals("0", parse(row).speed)
        car.put("vEgo", -1.0); assertTrue(parse(row).dataFresh); assertEquals("4", parse(row).speed)
        car.put("vEgo", "NaN"); assertWaiting(row)
    }
    @Test fun discoveryRequiresAnActualNonEmptyDongleIdentity() {
        for (raw in listOf("<html>unrelated server</html>", "{}", "{\"values\":{}}", "{\"values\":{\"DongleId\":null}}", "{\"values\":{\"DongleId\":\" \"}}", "{\"values\":{\"DongleId\":123}}")) {
            assertNull(Net.parseDongle(raw))
        }
        assertEquals("UnregisteredDevice", Net.parseDongle("{\"values\":{\"DongleId\":\"UnregisteredDevice\"}}"))
        assertEquals("abc123", Net.parseDongle("{\"values\":{\"DongleId\":\" abc123 \"}}"))
    }
}
