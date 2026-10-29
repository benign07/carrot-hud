package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OpUpdateApiTest {
    private class Reply(val code: Int, val text: String, val lost: Boolean = false) : HttpURLConnection(URL("http://100.98.217.122:7000")) {
        var posts = 0
        var disconnected = false
        override fun getResponseCode(): Int { if (lost) throw IOException("response lost"); return code }
        override fun getInputStream() = ByteArrayInputStream(text.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(text.toByteArray())
        override fun getOutputStream(): ByteArrayOutputStream { posts++; return ByteArrayOutputStream() }
        override fun disconnect() { disconnected = true }
        override fun connect() { }
        override fun usingProxy() = false
    }
    @Test fun pairingRejectsExternalUrlsAndEmbeddedCredentials() {
        for (url in listOf("http://example.com:7000", "http://100.98.217.122:7000/path", "http://a@100.98.217.122:7000", "http://100.98.217.122:7000?q=1")) {
            try { OpEndpoint(url, "x".repeat(43)); fail(url) } catch (_: IllegalArgumentException) { }
        }
        assertEquals("http://100.98.217.122:7000", OpEndpoint("http://100.98.217.122:7000", "x".repeat(43)).url)
    }
    @Test fun oldDeviceAndPairingFailuresDoNotReportSuccess() {
        for (code in listOf(404, 401, 409, 500, 302)) {
            val reply = Reply(code, "rejected")
            try { OpUpdateApi.read(reply); fail() } catch (_: OpUpdateException) { }
            assertTrue(reply.disconnected)
        }
    }
    @Test fun lostQueueResponseIsNotRetried() {
        val reply = Reply(200, "", true)
        try { OpUpdateApi.read(reply, JSONObject().put("action", "queue")); fail() } catch (_: IOException) { }
        assertEquals(1, reply.posts)
        assertTrue(reply.disconnected)
    }
    @Test fun queuedAndAppliedAreNotLabelledComplete() {
        for (phase in listOf("waiting_parked", "armed", "applying", "verifying", "failed", "unknown"))
            assertNotEquals("업데이트 완료", OpUpdateApi.label(phase))
        assertEquals("업데이트 완료", OpUpdateApi.label("complete"))
    }
    @Test fun oversizedStatusIsRejected() {
        val reply = Reply(200, "x".repeat(131073))
        try { OpUpdateApi.read(reply); fail() } catch (_: Exception) { }
        assertTrue(reply.disconnected)
    }
    @Test fun automaticChoiceIsExplicitBooleanAndCannotQueueAnInstallByItself() {
        for (enabled in listOf(false,true)) {
            val body=OpUpdateApi.automaticBody(enabled)
            assertEquals("automatic",body.getString("action"))
            assertEquals(enabled,body.getBoolean("enabled"))
            assertFalse(body.has("release_id"));assertFalse(body.has("bundle_sha256"))
        }
    }
    @Test fun manualTakeoverOnlyOffersTheStillPinnedAutomaticReservation() {
        val state=JSONObject("""{"ok":true,"configured":true,"phase":"waiting_parked","automatic_request":true,"installed":{"sequence":20},"latest":{"sequence":21,"release_id":"test-21","bundle_sha256":"${"a".repeat(64)}"},"requested_release":"test-21","requested_bundle_sha256":"${"a".repeat(64)}"}""")
        assertNull(OpUpdatePolicy.available(state));assertNotNull(OpUpdatePolicy.manualRequest(state))
        state.getJSONObject("latest").put("bundle_sha256","b".repeat(64))
        assertNull(OpUpdatePolicy.manualRequest(state))
        state.put("automatic_request",false);assertNull(OpUpdatePolicy.manualRequest(state))
    }
    @Test fun automaticPreparationPollsFrequentlyWithoutFrequentGithubChecks() {
        val schedule=OpUpdateSchedule();schedule.success(1000,true,true)
        assertFalse(schedule.due(5999));assertTrue(schedule.due(6000))
        assertFalse(schedule.checkDue(6000));assertTrue(schedule.checkDue(901000))
    }
}
