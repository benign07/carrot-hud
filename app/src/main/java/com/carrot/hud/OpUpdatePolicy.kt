package com.carrot.hud

import org.json.JSONObject

/** Metadata checks cannot queue an installation. All deadlines use elapsed time. */
class OpUpdateSchedule {
    private var connected = false
    private var nextStatus = 0L
    private var nextCheck = 0L
    fun disconnected() { if (connected) { connected = false; nextCheck = 0L } }
    fun due(now: Long): Boolean = now >= nextStatus
    fun checkDue(now: Long): Boolean = now >= nextCheck
    fun success(now: Long, checked: Boolean) {
        connected = true
        nextStatus = now + 60_000
        if (checked) nextCheck = now + 15 * 60_000
    }
    fun failed(now: Long) { disconnected(); nextStatus = now + 60_000 }
}

object OpUpdatePolicy {
    val activePhases = setOf("waiting_parked", "downloading", "countdown", "armed", "applying", "verifying", "rolling_back")
    fun available(state: JSONObject): JSONObject? {
        if (!state.optBoolean("ok") || !state.optBoolean("configured") || state.optString("phase") in activePhases) return null
        // Do not infer an installed version when the device has not reported one.
        val installed = state.optJSONObject("installed") ?: return null
        val latest = state.optJSONObject("latest") ?: return null
        if (!installed.has("sequence") || latest.optLong("sequence", -1) <= installed.optLong("sequence", -1)) return null
        if (!latest.optString("release_id").matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) ||
            !latest.optString("bundle_sha256").matches(Regex("[a-f0-9]{64}"))) return null
        return latest
    }
    fun identity(url: String, latest: JSONObject) = "$url|${latest.getLong("sequence")}|${latest.getString("release_id")}|${latest.getString("bundle_sha256")}"
}
