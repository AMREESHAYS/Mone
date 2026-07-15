package com.example.mone

import android.content.Context
import android.os.Build
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import dev.ffmpegkit_maintained.ytdlp.YtDlpResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Result of a download attempt. */
sealed class Outcome {
    data class Ok(val file: File) : Outcome()
    data class Err(val message: String) : Outcome()
    object Cancelled : Outcome()
}

/** What the user chose to download. */
enum class DownloadFormat(val label: String) {
    BEST("Best video"),
    P1080("1080p"),
    P720("720p"),
    AUDIO("Audio (M4A)");

    /** yt-dlp single-file selector (video modes). */
    val single: String
        get() = when (this) {
            BEST -> "b"
            P1080 -> "b[height<=1080]"
            P720 -> "b[height<=720]"
            AUDIO -> "ba/bestaudio/best"
        }

    /** yt-dlp video-only selector for the separate-streams path. */
    val video: String
        get() = when (this) {
            BEST -> "bv*"
            P1080 -> "bv*[height<=1080]"
            P720 -> "bv*[height<=720]"
            AUDIO -> "bv*"
        }
}

/**
 * Pure download logic (no UI, no notifications) — runs SYNCHRONOUSLY on the caller's
 * thread and returns an [Outcome]. Notifications, history and gallery-scan are the
 * caller's job (see DownloadService). Cancellation is cooperative: [cancelled] is
 * polled between steps and cancels the underlying Future. The library has no hard
 * process-kill, so an in-flight transfer may run to completion in the background,
 * but its files are deleted and never published.
 */
object Downloader {

    fun downloadDir(): File = File(Environment.getExternalStorageDirectory(), "Mone")

    /** Cookies stored in internal (app-private) storage — not readable by other apps. */
    fun cookiesFile(context: Context): File = File(context.filesDir, "cookies.txt")

    fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun run(
        context: Context,
        url: String,
        format: DownloadFormat,
        cancelled: AtomicBoolean,
        onProgress: (percent: Int, line: String) -> Unit,
        trimStartSec: Int = -1,
        trimEndSec: Int = -1,
    ): Outcome {
        val appContext = context.applicationContext
        if (!hasStorageAccess()) return Outcome.Err("Grant “All files access” to Mone first, then try again.")

        val outDir = downloadDir().apply { mkdirs() }

        // Direct image links: just fetch them over HTTP (yt-dlp is for video).
        if (looksLikeImage(url) && format != DownloadFormat.AUDIO) return downloadImage(url, outDir, onProgress)

        val cookies = cookiesFile(appContext)
        fun common(req: YtDlpRequest): YtDlpRequest {
            if (cookies.exists()) req.addOption("--cookies", cookies.absolutePath)
            return req.addOption("--no-playlist")
                .addOption("--retries", "3")
                .addOption("--extractor-retries", "3")
                .addOption("--socket-timeout", "30")
        }

        val jobDir = File(outDir, ".mone_job_${System.currentTimeMillis()}").apply { mkdirs() }

        // Cut the given file to [trimStartSec, trimEndSec] with a stream-copy (no re-encode).
        fun trimmed(file: File): File {
            if (trimStartSec < 0 && trimEndSec < 0) return file
            onProgress(-1, "Trimming clip…")
            val start = if (trimStartSec > 0) trimStartSec else 0
            val out = File(jobDir, "clip_${System.currentTimeMillis()}.${file.extension}")
            // Argument array (not a parsed string) so paths with spaces/special chars are safe.
            val args = mutableListOf("-y")
            if (start > 0) { args.add("-ss"); args.add(start.toString()) }
            args.add("-i"); args.add(file.absolutePath)
            if (trimEndSec > start) { args.add("-t"); args.add((trimEndSec - start).toString()) }
            args.add("-c"); args.add("copy"); args.add(out.absolutePath)
            val session = FFmpegKit.executeWithArguments(args.toTypedArray())
            return if (ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 0) out else file
        }

        fun publish(file: File): File {
            var dest = File(outDir, file.name)
            var i = 1
            while (dest.exists()) {
                dest = File(outDir, "${file.nameWithoutExtension} ($i).${file.extension}")
                i++
            }
            if (!file.renameTo(dest)) file.copyTo(dest, overwrite = false)
            return dest
        }

        try {
            YtDlp.init(appContext)
            if (cancelled.get()) return Outcome.Cancelled

            // Audio-only: prefer an m4a stream directly (no transcode). If the source only
            // offers Opus/webm, transcode to m4a (AAC) — the free ffmpeg has that encoder.
            if (format == DownloadFormat.AUDIO) {
                onProgress(0, "Fetching audio…")
                val ra = runYtdlp(
                    common(
                        YtDlpRequest(url).setOutputTemplate("${jobDir.absolutePath}/a.%(ext)s")
                            .addOption("-f", "ba[ext=m4a]/ba/bestaudio/best").addOption("--hls-prefer-native"),
                    ),
                    cancelled, onProgress,
                )
                if (cancelled.get()) return Outcome.Cancelled
                val aFile = jobDir.listFiles { f -> f.name.startsWith("a.") }?.firstOrNull()
                if (ra?.isSuccess != true || aFile == null) {
                    return Outcome.Err(humanize(ra?.errorOutput.orEmpty()))
                }
                // Already m4a/mp4 (AAC) → publish as-is, no re-encode.
                if (aFile.extension.lowercase() in setOf("m4a", "mp4", "aac")) {
                    return Outcome.Ok(publish(aFile))
                }
                onProgress(-1, "Converting audio…")
                val m4a = File(jobDir, "mone_${System.currentTimeMillis()}.m4a")
                val session = FFmpegKit.executeWithArguments(
                    arrayOf("-y", "-i", aFile.absolutePath, "-vn", "-c:a", "aac", "-b:a", "192k", m4a.absolutePath),
                )
                return if (ReturnCode.isSuccess(session.returnCode) && m4a.exists() && m4a.length() > 0) {
                    Outcome.Ok(publish(m4a))
                } else {
                    Outcome.Ok(publish(aFile)) // last resort: keep the raw audio
                }
            }

            // 1) Best single file (already has audio+video) — no merge needed.
            val r1 = runYtdlp(
                common(
                    YtDlpRequest(url)
                        .setOutputTemplate("${jobDir.absolutePath}/%(title).80s.%(ext)s")
                        .addOption("-f", format.single),
                ),
                cancelled, onProgress,
            )
            if (cancelled.get()) return Outcome.Cancelled
            if (r1?.isSuccess == true) {
                val file = jobDir.listFiles()?.maxByOrNull { it.lastModified() }
                    ?: return Outcome.Err("Downloaded, but file not found.")
                return Outcome.Ok(publish(trimmed(file)))
            }

            // 2) Separate streams (Pinterest/HLS) — download video + audio, then mux.
            onProgress(0, "Fetching video…")
            val rv = runYtdlp(
                common(
                    YtDlpRequest(url).setOutputTemplate("${jobDir.absolutePath}/v.%(ext)s")
                        .addOption("-f", format.video).addOption("--hls-prefer-native"),
                ),
                cancelled, onProgress,
            )
            if (cancelled.get()) return Outcome.Cancelled

            onProgress(0, "Fetching audio…")
            val ra = runYtdlp(
                common(
                    YtDlpRequest(url).setOutputTemplate("${jobDir.absolutePath}/a.%(ext)s")
                        .addOption("-f", "ba").addOption("--hls-prefer-native"),
                ),
                cancelled, onProgress,
            )
            if (cancelled.get()) return Outcome.Cancelled

            val vFile = jobDir.listFiles { f -> f.name.startsWith("v.") }?.firstOrNull()
            val aFile = jobDir.listFiles { f -> f.name.startsWith("a.") }?.firstOrNull()
            if (rv?.isSuccess != true || ra?.isSuccess != true || vFile == null || aFile == null) {
                val raw = (rv?.errorOutput.orEmpty()).ifBlank { ra?.errorOutput.orEmpty() }
                    .ifBlank { r1?.errorOutput.orEmpty() }
                return Outcome.Err(humanize(raw))
            }

            // 3) Mux with ffmpeg-kit (stream copy — no re-encode).
            onProgress(-1, "Merging video + audio…")
            if (cancelled.get()) return Outcome.Cancelled
            val out = File(jobDir, "mone_${System.currentTimeMillis()}.mp4")
            val session = FFmpegKit.executeWithArguments(
                arrayOf("-y", "-i", vFile.absolutePath, "-i", aFile.absolutePath, "-c", "copy", out.absolutePath),
            )
            return if (ReturnCode.isSuccess(session.returnCode) && out.exists()) {
                Outcome.Ok(publish(trimmed(out)))
            } else {
                Outcome.Err("Merge failed. Try again or a different link.")
            }
        } catch (e: Exception) {
            return if (cancelled.get()) Outcome.Cancelled else Outcome.Err("Error: ${e.message}")
        } finally {
            jobDir.deleteRecursively()
        }
    }

    private val imageExts = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")

    private fun looksLikeImage(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return imageExts.any { path.endsWith(it) }
    }

    private fun downloadImage(url: String, outDir: File, onProgress: (Int, String) -> Unit): Outcome {
        return try {
            onProgress(-1, "Downloading image…")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Mone")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return Outcome.Err("Image download failed (HTTP ${conn.responseCode}).")
            }
            val ext = when {
                conn.contentType?.contains("png", true) == true -> "png"
                conn.contentType?.contains("webp", true) == true -> "webp"
                conn.contentType?.contains("gif", true) == true -> "gif"
                else -> imageExts.map { it.drop(1) }.firstOrNull { url.substringBefore('?').lowercase().endsWith(it) } ?: "jpg"
            }
            var dest = File(outDir, "image_${System.currentTimeMillis()}.$ext")
            var i = 1
            while (dest.exists()) { dest = File(outDir, "image_${System.currentTimeMillis()}_$i.$ext"); i++ }
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (dest.length() > 0) Outcome.Ok(dest) else { dest.delete(); Outcome.Err("Image download failed.") }
        } catch (e: Exception) {
            Outcome.Err("Image download failed: ${e.message}")
        }
    }

    /** Runs one yt-dlp request, polling [cancelled] so a cancel cancels the Future. */
    private fun runYtdlp(
        request: YtDlpRequest,
        cancelled: AtomicBoolean,
        onProgress: (Int, String) -> Unit,
    ): YtDlpResponse? {
        return try {
            val future = YtDlp.executeAsync(request) { p, _, line ->
                onProgress(if (p >= 0f) p.toInt() else 0, line)
            }
            while (!future.isDone) {
                if (cancelled.get()) {
                    future.cancel(true)
                    return null
                }
                Thread.sleep(150)
            }
            future.get()
        } catch (e: Exception) {
            null
        }
    }

    /** Turn raw yt-dlp errors into something a person can act on. */
    private fun humanize(raw: String): String = when {
        raw.contains("No address associated", true) ||
            raw.contains("getaddrinfo", true) ||
            raw.contains("Errno 7", true) ||
            raw.contains("Temporary failure in name resolution", true) ||
            raw.contains("Unable to download webpage", true) ->
            "Network error — check your connection and try again."
        raw.contains("Requested format is not available", true) ->
            "Couldn't find a downloadable video at that link."
        raw.contains("not a bot", true) ||
            raw.contains("Too Many Requests", true) ||
            raw.contains("HTTP Error 429", true) ->
            "This site is rate-limiting or blocking automated downloads right now. Try again later."
        raw.contains("empty media response", true) ||
            (raw.contains("login", true) && raw.contains("instagram", true)) ->
            "This post needs you to be logged in. Tap “Log in to Instagram”."
        else -> "Failed:\n${raw.take(300)}"
    }
}
