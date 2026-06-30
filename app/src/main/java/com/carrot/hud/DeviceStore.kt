package com.carrot.hud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A registered comma device (identified by IP). */
data class Device(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int = 7000
) {
    fun hudUrl(): String = "http://$ip:$port/hud.html"
    fun settingsUrl(): String = "http://$ip:$port/"
    fun label(): String = "$ip:$port"
}

/** Persists the list of devices + which one is active (SharedPreferences/JSON). */
object DeviceStore {
    private const val PREFS = "carrot_hud"
    private const val KEY_DEVICES = "devices"
    private const val KEY_ACTIVE = "active_id"
    private const val KEY_WIDGET_INTERVAL = "widget_interval_ms"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getDevices(ctx: Context): MutableList<Device> {
        val raw = prefs(ctx).getString(KEY_DEVICES, "[]") ?: "[]"
        val list = mutableListOf<Device>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Device(
                        o.getString("id"),
                        o.optString("name", o.getString("ip")),
                        o.getString("ip"),
                        o.optInt("port", 7000)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun save(ctx: Context, list: List<Device>) {
        val arr = JSONArray()
        for (d in list) {
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("name", d.name)
                    .put("ip", d.ip)
                    .put("port", d.port)
            )
        }
        prefs(ctx).edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    fun addDevice(ctx: Context, name: String, ip: String, port: Int = 7000): Device {
        val cleanIp = cleanIp(ip)
        val d = Device(
            id = System.currentTimeMillis().toString(),
            name = name.trim().ifBlank { cleanIp },
            ip = cleanIp,
            port = port
        )
        val list = getDevices(ctx)
        list.add(d)
        save(ctx, list)
        setActive(ctx, d.id)
        return d
    }

    fun updateDevice(ctx: Context, id: String, name: String, ip: String, port: Int = 7000) {
        val cleanIp = cleanIp(ip)
        val list = getDevices(ctx).map {
            if (it.id == id) it.copy(name = name.trim().ifBlank { cleanIp }, ip = cleanIp, port = port) else it
        }
        save(ctx, list)
    }

    fun removeDevice(ctx: Context, id: String) {
        val list = getDevices(ctx).filterNot { it.id == id }
        save(ctx, list)
        if (getActiveId(ctx) == id) setActive(ctx, list.firstOrNull()?.id)
    }

    fun getActiveId(ctx: Context): String? = prefs(ctx).getString(KEY_ACTIVE, null)

    fun setActive(ctx: Context, id: String?) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Home-screen widget auto-refresh interval (default 60s). */
    fun getWidgetIntervalMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_WIDGET_INTERVAL, 60_000L)

    fun setWidgetIntervalMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_WIDGET_INTERVAL, ms).apply()
    }

    fun getActive(ctx: Context): Device? {
        val id = getActiveId(ctx)
        val list = getDevices(ctx)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun getById(ctx: Context, id: String?): Device? {
        if (id == null) return null
        return getDevices(ctx).firstOrNull { it.id == id }
    }

    private fun cleanIp(s: String): String =
        s.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
}
