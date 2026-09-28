package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpUpdatePolicyTest {
    private fun state(installed: Int = 1, latest: Int = 2, phase: String = "complete") = JSONObject()
        .put("ok", true).put("configured", true).put("phase", phase)
        .put("installed", JSONObject().put("sequence", installed))
        .put("latest", JSONObject().put("sequence", latest).put("release_id", "palisade-20260929-01").put("bundle_sha256", "a".repeat(64)))
    @Test fun onlyNewDeviceConfirmedReleasesAreAdvertised() {
        assertNotNull(OpUpdatePolicy.available(state()))
        assertNull(OpUpdatePolicy.available(state(2, 2)))
        assertNull(OpUpdatePolicy.available(state(3, 2)))
        for (phase in OpUpdatePolicy.activePhases) assertNull(OpUpdatePolicy.available(state(phase = phase)))
        assertNull(OpUpdatePolicy.available(state().put("configured", false)))
        assertNull(OpUpdatePolicy.available(state().put("ok", false)))
        assertNull(OpUpdatePolicy.available(state().apply { remove("installed") }))
        assertNull(OpUpdatePolicy.available(state().apply { getJSONObject("latest").put("bundle_sha256", "invalid") }))
    }
    @Test fun deduplicationIncludesDeviceAndExactBundle() {
        val latest = state().getJSONObject("latest")
        val key = OpUpdatePolicy.identity("device-a", latest)
        assertEquals(key, OpUpdatePolicy.identity("device-a", latest))
        assertNotEquals(key, OpUpdatePolicy.identity("device-b", latest))
        latest.put("bundle_sha256", "b".repeat(64))
        assertNotEquals(key, OpUpdatePolicy.identity("device-a", latest))
    }
    @Test fun bootRetryReconnectAndPeriodicChecksAreBounded() {
        val s = OpUpdateSchedule()
        assertTrue(s.due(0)); assertTrue(s.checkDue(0))
        s.failed(0)
        assertFalse(s.due(59_999)); assertTrue(s.due(60_000))
        s.success(60_000, true)
        assertFalse(s.due(60_001)); assertTrue(s.due(120_000))
        assertFalse(s.checkDue(959_999)); assertTrue(s.checkDue(960_000))
        s.disconnected()
        assertTrue(s.checkDue(120_000))
        s.success(120_000, false) // Active installs defer GitHub checks without losing the pending check.
        assertTrue(s.checkDue(180_000))
    }
}
