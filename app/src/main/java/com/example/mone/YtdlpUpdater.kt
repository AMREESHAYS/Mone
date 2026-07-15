package com.example.mone

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import kotlin.concurrent.thread

/**
 * Keeps the bundled yt-dlp engine current. Sites change constantly and yt-dlp ships
 * fixes almost weekly, so a frozen copy rots — this updates it in the background.
 */
object YtdlpUpdater {
    private const val PREFS = "mone_updater"
    private const val KEY_LAST = "ytdlp_last_update"
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    /** Auto-update at most once per day, silently, in the background. */
    fun maybeUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST, 0L) < ONE_DAY_MS) return
        update(context) { _, _ -> }
    }

    /** Force an update now; [onResult] runs on the main thread. */
    fun update(context: Context, onResult: (success: Boolean, message: String) -> Unit) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        thread {
            try {
                YtDlp.init(app)
                YtDlp.updateYtDlp(app, object : YtDlp.UpdateCallback {
                    override fun onComplete(message: String) {
                        prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
                        main.post { onResult(true, message.ifBlank { "Engine up to date" }) }
                    }

                    override fun onError(message: String) {
                        main.post { onResult(false, message.ifBlank { "Update failed" }) }
                    }
                })
            } catch (e: Exception) {
                main.post { onResult(false, e.message ?: "Update failed") }
            }
        }
    }
}
