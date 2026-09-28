package com.carrot.hud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ModeApiTest {
    private fun saved(mode: Int) = JSONObject().put("ok", true).put("values", JSONObject().put("MyDrivingMode", mode))
    private fun runtime(mode: Int, age: Int = 10) = JSONObject().put("ok", true).put("snapshotAgeMs", 10)
        .put("runtime", JSONObject().put("serviceAlive", JSONObject().put("longitudinalPlan", true))
            .put("serviceValid", JSONObject().put("longitudinalPlan", true)).put("serviceAgeMs", JSONObject().put("longitudinalPlan", age)))
        .put("services", JSONObject().put("longitudinalPlan", JSONObject().put("myDrivingMode", mode)))

    @Test fun rejectedParkedCheckIsShownAndNoOptimisticMode() {
        var posts = 0
        val api = ModeApi { method, _, _ ->
            if (method == "POST") { posts++; throw ModeApiError("정차 후 P단에서 변경할 수 있습니다.") }
            saved(3)
        }
        val result = api.cycle()
        assertFalse(result.changed); assertEquals(3, result.saved); assertEquals(1, posts)
        assertTrue(result.message.contains("P단")); assertNull(result.effective)
    }
    @Test fun successfulWriteIsReadBackAndActualModeIsSeparate() {
        var current = 3; var posts = 0
        val api = ModeApi { method, path, body ->
            when {
                method == "POST" -> { posts++; current = JSONObject(body!!).getInt("value"); JSONObject().put("ok", true).put("has_params", true).put("value", current) }
                path == "/api/live_runtime" -> runtime(2)
                else -> saved(current)
            }
        }
        val result = api.cycle()
        assertTrue(result.changed); assertEquals(4, result.saved); assertEquals(2, result.effective); assertEquals(1, posts)
    }
    @Test fun missingParamsBackendOrReadbackMismatchIsNotSuccess() {
        for (backend in listOf(false, true)) {
            val api = ModeApi { method, path, _ -> when {
                method == "POST" -> JSONObject().put("ok", true).put("has_params", backend).put("value", 4)
                path == "/api/live_runtime" -> runtime(3)
                else -> saved(3)
            } }
            assertFalse(api.cycle().changed)
        }
    }
    @Test fun unknownModeOrOfflineNeverWrites() {
        for (offline in listOf(false, true)) {
            var posts = 0
            val api = ModeApi { method, _, _ ->
                if (method == "POST") posts++
                if (offline) throw IOException("offline") else saved(0)
            }
            assertFalse(api.cycle().changed); assertEquals(0, posts)
        }
    }
    @Test fun staleActualModeIsNotDisplayedAsCurrent() {
        assertEquals(2, ModeApi.parseEffective(runtime(2)))
        assertNull(ModeApi.parseEffective(runtime(2, 1000)))
        assertNull(ModeApi.parseEffective(runtime(2).put("snapshotAgeMs", 1500)))
        assertNull(ModeApi.parseEffective(runtime(2).put("runtime", JSONObject())))
    }
}
