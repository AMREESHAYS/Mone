package com.example.mone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Checks GitHub Releases for a newer APK and offers to download + install it.
 * Sideloaded apps don't auto-update, so this is how users get fixes.
 */
object AppUpdater {
    private const val PREFS = "mone_updater"
    private const val KEY_LAST = "app_last_check"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val API = "https://api.github.com/repos/AMREESHAYS/Mone/releases/latest"
    private val main = Handler(Looper.getMainLooper())

    /** Check at most once per day; prompt if a newer release exists. */
    fun maybeCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0L) < ONE_DAY_MS) return
        thread {
            try {
                val (tag, apkUrl) = fetchLatest() ?: return@thread
                prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                if (apkUrl != null && isNewer(tag, currentVersion(activity))) {
                    main.post { if (!activity.isFinishing) prompt(activity, tag, apkUrl) }
                }
            } catch (e: Exception) {
                // offline / rate-limited — try again next launch
            }
        }
    }

    private fun fetchLatest(): Pair<String, String?>? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15000
            readTimeout = 15000
        }
        if (conn.responseCode !in 200..299) return null
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.getString("tag_name")
        var apk: String? = null
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", true)) {
                    apk = a.getString("browser_download_url"); break
                }
            }
        }
        return tag to apk
    }

    private fun currentVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "0"

    /** Compare dotted versions; ignores a leading "v". */
    private fun isNewer(tag: String, current: String): Boolean {
        fun parts(s: String) = s.trimStart('v', 'V').split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(tag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun prompt(activity: Activity, tag: String, apkUrl: String) {
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage("Mone $tag is available. Download and install it now?")
            .setNegativeButton("Later", null)
            .setPositiveButton("Download") { _, _ -> downloadAndInstall(activity, tag, apkUrl) }
            .show()
    }

    private fun downloadAndInstall(activity: Activity, tag: String, apkUrl: String) {
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val apk = File(activity.cacheDir, "Mone-$tag.apk")
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30000
                    readTimeout = 30000
                }
                conn.inputStream.use { input -> apk.outputStream().use { input.copyTo(it) } }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                main.post { activity.startActivity(intent) }
            } catch (e: Exception) {
                main.post { Toast.makeText(activity, "Update download failed.", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
