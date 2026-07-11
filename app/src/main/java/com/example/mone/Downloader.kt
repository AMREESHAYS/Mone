package com.example.mone

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest
import java.io.File
import kotlin.concurrent.thread

/** Shared download logic used by both the main screen and the share popup. */
object Downloader {
    private val main = Handler(Looper.getMainLooper())

    /** Public, file-manager-visible folder all downloads go into: /sdcard/Mone. */
    fun downloadDir(): File = File(Environment.getExternalStorageDirectory(), "Mone")

    /** Cookies live in INTERNAL storage — they are a full login session, never on shared storage. */
    fun cookiesFile(context: Context): File = File(context.filesDir, "cookies.txt")

    /** True when we can write to the public Mone folder. */
    fun hasStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * Downloads [url] into the public Mone folder in best quality, then makes it
     * visible in the gallery. Both callbacks are invoked on the main thread.
     *
     * Strategy: try a best single-file stream first (fast, covers reels & direct mp4).
     * If a site only offers separate video+audio (e.g. Pinterest/HLS), download each
     * and mux them with ffmpeg-kit — the library has no ffmpeg CLI for yt-dlp to use.
     *
     * Each call uses its own private temp dir, so concurrent downloads never collide.
     */
    fun download(
        context: Context,
        url: String,
        onProgress: (percent: Int, line: String) -> Unit,
        onResult: (success: Boolean, message: String) -> Unit,
    ) {
        val appContext = context.applicationContext
        if (!hasStorageAccess()) {
            onResult(false, "Grant “All files access” to Mone first, then try again.")
            return
        }
        val outDir = downloadDir().apply { mkdirs() }
        val cookies = cookiesFile(appContext)
        fun withCookies(req: YtDlpRequest) =
            req.also { if (cookies.exists()) it.addOption("--cookies", cookies.absolutePath) }

        val notifId = (System.currentTimeMillis() and 0xFFFFFF).toInt()
        // Per-job temp dir on the SAME volume as outDir, so publishing is an instant rename.
        val jobDir = File(outDir, ".mone_job_$notifId").apply { mkdirs() }
        Notifications.progress(appContext, notifId, 0, indeterminate = true)

        val prog: (Int, String) -> Unit = { pct, line ->
            Notifications.progress(appContext, notifId, pct, indeterminate = pct <= 0)
            main.post { onProgress(pct, line) }
        }

        // Move a finished file out of the private job dir into the public Mone folder.
        fun publish(file: File): File {
            val dest = File(outDir, file.name)
            if (dest.exists()) dest.delete()
            if (!file.renameTo(dest)) file.copyTo(dest, overwrite = true)
            return dest
        }

        fun succeed(file: File) {
            val dest = publish(file)
            MediaScannerConnection.scanFile(appContext, arrayOf(dest.absolutePath), null) { _, uri ->
                HistoryStore.add(appContext, HistoryStore.Entry(dest.name, System.currentTimeMillis(), uri?.toString()))
                Notifications.complete(appContext, notifId, dest.name, uri)
            }
            main.post { onResult(true, "Done ✓\nSaved to Mone folder & gallery") }
        }

        fun fail(message: String) {
            Notifications.failed(appContext, notifId, message)
            main.post { onResult(false, message) }
        }

        thread {
            try {
                YtDlp.init(appContext)

                // 1) Best single file that already has audio+video — no merge needed.
                val single = withCookies(
                    YtDlpRequest(url)
                        .setOutputTemplate("${jobDir.absolutePath}/%(title).80s.%(ext)s")
                        .addOption("-f", "b"),
                )
                val r1 = try {
                    YtDlp.execute(single) { p, _, line -> prog(if (p >= 0f) p.toInt() else 0, line) }
                } catch (e: Exception) {
                    null
                }
                if (r1?.isSuccess == true) {
                    val file = jobDir.listFiles()?.maxByOrNull { it.lastModified() }
                    if (file != null) succeed(file) else fail("Downloaded, but file not found.")
                    return@thread
                }

                // 2) No single file (separate streams). Download video + audio, then mux.
                main.post { onProgress(0, "Fetching video…") }
                val rv = YtDlp.execute(
                    withCookies(
                        YtDlpRequest(url)
                            .setOutputTemplate("${jobDir.absolutePath}/v.%(ext)s")
                            .addOption("-f", "bv*").addOption("--hls-prefer-native"),
                    ),
                ) { p, _, line -> prog(if (p >= 0f) p.toInt() else 0, line) }

                main.post { onProgress(0, "Fetching audio…") }
                val ra = YtDlp.execute(
                    withCookies(
                        YtDlpRequest(url)
                            .setOutputTemplate("${jobDir.absolutePath}/a.%(ext)s")
                            .addOption("-f", "ba").addOption("--hls-prefer-native"),
                    ),
                ) { p, _, line -> prog(if (p >= 0f) p.toInt() else 0, line) }

                val vFile = jobDir.listFiles { f -> f.name.startsWith("v.") }?.firstOrNull()
                val aFile = jobDir.listFiles { f -> f.name.startsWith("a.") }?.firstOrNull()
                if (!rv.isSuccess || !ra.isSuccess || vFile == null || aFile == null) {
                    val why = rv.errorOutput.ifBlank { ra.errorOutput }.ifBlank { r1?.errorOutput.orEmpty() }
                    fail("Failed:\n$why")
                    return@thread
                }

                // 3) Mux with ffmpeg-kit (stream copy — no re-encode, fast).
                main.post { onProgress(-1, "Merging video + audio…") }
                val out = File(jobDir, "mone_${System.currentTimeMillis()}.mp4")
                val session = FFmpegKit.execute(
                    "-y -i ${vFile.absolutePath} -i ${aFile.absolutePath} -c copy ${out.absolutePath}",
                )
                if (ReturnCode.isSuccess(session.returnCode) && out.exists()) {
                    succeed(out)
                } else {
                    fail("Merge failed. Try again or a different link.")
                }
            } catch (e: Exception) {
                fail("Error: ${e.message}")
            } finally {
                jobDir.deleteRecursively()
            }
        }
    }
}
