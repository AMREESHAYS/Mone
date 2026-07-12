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
import java.util.concurrent.atomic.AtomicBoolean

/** Result of a download attempt. */
sealed class Outcome {
    data class Ok(val file: File) : Outcome()
    data class Err(val message: String) : Outcome()
    object Cancelled : Outcome()
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

    fun cookiesFile(context: Context): File = File(context.filesDir, "cookies.txt")

    fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun run(
        context: Context,
        url: String,
        cancelled: AtomicBoolean,
        onProgress: (percent: Int, line: String) -> Unit,
    ): Outcome {
        val appContext = context.applicationContext
        if (!hasStorageAccess()) return Outcome.Err("Grant “All files access” to Mone first, then try again.")

        val outDir = downloadDir().apply { mkdirs() }
        val cookies = cookiesFile(appContext)
        fun common(req: YtDlpRequest): YtDlpRequest {
            if (cookies.exists()) req.addOption("--cookies", cookies.absolutePath)
            return req.addOption("--no-playlist")
                .addOption("--retries", "3")
                .addOption("--extractor-retries", "3")
                .addOption("--socket-timeout", "30")
        }

        val jobDir = File(outDir, ".mone_job_${System.currentTimeMillis()}").apply { mkdirs() }

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

            // 1) Best single file (already has audio+video) — no merge needed.
            val r1 = runYtdlp(
                common(
                    YtDlpRequest(url)
                        .setOutputTemplate("${jobDir.absolutePath}/%(title).80s.%(ext)s")
                        .addOption("-f", "b"),
                ),
                cancelled, onProgress,
            )
            if (cancelled.get()) return Outcome.Cancelled
            if (r1?.isSuccess == true) {
                val file = jobDir.listFiles()?.maxByOrNull { it.lastModified() }
                    ?: return Outcome.Err("Downloaded, but file not found.")
                return Outcome.Ok(publish(file))
            }

            // 2) Separate streams (Pinterest/HLS) — download video + audio, then mux.
            onProgress(0, "Fetching video…")
            val rv = runYtdlp(
                common(
                    YtDlpRequest(url).setOutputTemplate("${jobDir.absolutePath}/v.%(ext)s")
                        .addOption("-f", "bv*").addOption("--hls-prefer-native"),
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
            val session = FFmpegKit.execute(
                "-y -i ${vFile.absolutePath} -i ${aFile.absolutePath} -c copy ${out.absolutePath}",
            )
            return if (ReturnCode.isSuccess(session.returnCode) && out.exists()) {
                Outcome.Ok(publish(out))
            } else {
                Outcome.Err("Merge failed. Try again or a different link.")
            }
        } catch (e: Exception) {
            return if (cancelled.get()) Outcome.Cancelled else Outcome.Err("Error: ${e.message}")
        } finally {
            jobDir.deleteRecursively()
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
