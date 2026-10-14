package com.carrot.hud

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** A token stays on its original Tailscale endpoint, even when HUD uses Wi-Fi. */
object OpPairingBinding {
    fun known(value: String) = value.isNotBlank() && value != "UnregisteredDevice"
    fun allows(device: Device, boundId: String, dongle: String, endpoint: OpEndpoint): Boolean =
        device.id == boundId && (if (known(dongle)) dongle == device.dongle
            else endpoint.url == "http://${device.ip}:${device.port}")
    fun canBind(device: Device, endpoint: OpEndpoint, remoteDongle: String?): Boolean =
        if (known(device.dongle)) remoteDongle == device.dongle
        else endpoint.url == "http://${device.ip}:${device.port}" && known(remoteDongle ?: "")
}

object OpPairingStore {
    private fun file(context: Context, id: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(context.noBackupFilesDir, "op-update-$key.json")
    }
    fun endpoint(context: Context): OpEndpoint? = try {
        val device = DeviceStore.getActive(context) ?: error("No active device")
        val saved = file(context, device.id)
        if (saved.isFile) {
            val row = JSONObject(saved.readText())
            val ep = OpEndpoint.parse(row.toString())
            require(OpPairingBinding.allows(device, row.getString("device_id"), row.optString("dongle"), ep))
            ep
        } else {
            val ep = OpEndpoint.parse(File(context.noBackupFilesDir, "op-update-pairing.json").readText())
            require(ep.url == "http://${device.ip}:${device.port}")
            ep
        }
    } catch (_: Exception) { null }

    /** Read-only identity lookup on the fixed endpoint, before sending any token. */
    fun resolve(context: Context): OpEndpoint? {
        endpoint(context)?.let { return it }
        val device = DeviceStore.getActive(context) ?: return null
        return try {
            val raw = File(context.noBackupFilesDir, "op-update-pairing.json").readText()
            bind(context, device, raw)
        } catch (_: Exception) { null }
    }
    fun bind(context: Context, device: Device, raw: String): OpEndpoint {
        val ep = OpEndpoint.parse(raw)
        val uri = URI(ep.url)
        val remote = Net.fetchDongle(uri.host, uri.port, 2500)
        require(OpPairingBinding.canBind(device, ep, remote)) { "등록 기기와 Tailscale 기기의 식별자가 다릅니다." }
        require(DeviceStore.getActive(context) == device) { "기기가 변경됐습니다. 다시 확인하세요." }
        val dongle = remote!!
        DeviceStore.setDongle(context, device.id, dongle)
        val row = JSONObject(raw).put("device_id", device.id).put("dongle", dongle)
        ArchiveConfig.writeAtomic(file(context, device.id), row.toString())
        return ep
    }
}
