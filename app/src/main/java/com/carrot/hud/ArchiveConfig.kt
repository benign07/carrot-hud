package com.carrot.hud

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Credentials are imported locally; never included in an APK or cloud backup. */
data class PcEndpoint(val url: String, val token: String, val receiverId: String) {
    init {
        val uri = URI(url)
        val parts = (uri.host ?: "").split('.').map { it.toIntOrNull() ?: -1 }
        require(uri.scheme == "http" && parts.size == 4 && parts[0] == 100 && parts[1] in 64..127 &&
            parts.all { it in 0..255 } && uri.port in 1..65535 && uri.userInfo == null &&
            uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null) { "Tailscale IPv4 주소와 포트가 필요합니다" }
        require(token.matches(Regex("[A-Za-z0-9_-]{43,128}"))) { "잘못된 연결 키" }
        require(receiverId.matches(Regex("[a-f0-9]{32}"))) { "잘못된 PC 식별자" }
    }

    companion object {
        fun parse(raw: String): PcEndpoint {
            require(raw.length <= 4096)
            val value = JSONObject(raw)
            require(value.getInt("schema") == 1)
            return PcEndpoint(value.getString("url"), value.getString("token"), value.getString("receiver_id"))
        }
    }
}

object ArchiveConfig {
    private fun prefs(context: Context) = context.getSharedPreferences("archive_control", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("auto_start", true)
    fun setEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean("auto_start", value).apply() }
    fun pairingFile(context: Context) = File(context.noBackupFilesDir, "pc-pairing.json")
    fun endpoint(context: Context): PcEndpoint? = try { PcEndpoint.parse(pairingFile(context).readText()) } catch (_: Exception) { null }
    fun import(context: Context, raw: String) {
        PcEndpoint.parse(raw)
        writeAtomic(pairingFile(context), raw)
    }
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
