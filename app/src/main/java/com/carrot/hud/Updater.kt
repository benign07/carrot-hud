package com.carrot.hud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: checks the GitHub Releases of the project for a newer build
 * and downloads + installs the APK. Requires the repo to be PUBLIC so the
 * release + asset are reachable without auth.
 */
object Updater {
    // The release tag is "v<run_number>" and versionCode == run_number,
    // so the newest release's number is comparable to the installed versionCode.
    private const val REPO = "benign07/carrot-hud"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_URL = "https://github.com/$REPO/releases/latest/download/app-debug.apk"

    data class Latest(val versionCode: Long, val tag: String)

    fun currentVersionCode(ctx: Context): Long {
        return try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) {
            0L
        }
    }

    /** Query GitHub for the latest release. null on failure (e.g. private repo / offline). */
    fun fetchLatest(): Latest? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "CarrotHud")
            }
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val tag = JSONObject(txt).optString("tag_name", "")
            val num = Regex("(\\d+)").find(tag)?.value?.toLongOrNull() ?: return null
            Latest(num, tag)
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** Download the latest APK into cacheDir. Returns the file, or null on error. */
    fun download(ctx: Context): File? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(APK_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CarrotHud")
            }
            if (conn.responseCode !in 200..299) return null
            val out = File(ctx.cacheDir, "update.apk")
            conn.inputStream.use { input ->
                out.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** Whether the app may install APKs (Android 8+ requires per-source consent). */
    fun canInstall(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.packageManager.canRequestPackageInstalls()
        else true
    }
}
