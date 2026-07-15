package com.example.mone

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that processes a download queue: up to [MAX_CONCURRENT] run at
 * once, the rest wait. One aggregate notification tracks the whole queue; each finished
 * download gets its own "Saved ✓" notification. Cancel per-item or cancel-all.
 */
class DownloadService : Service() {

    private val flags = ConcurrentHashMap<Int, AtomicBoolean>() // id -> cancel flag (non-terminal jobs)
    private val pool = Executors.newFixedThreadPool(MAX_CONCURRENT)
    @Volatile private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flagsArg: Int, startId: Int): Int {
        enterForeground() // satisfy the FGS contract immediately on every start
        when (intent?.action) {
            ACTION_CANCEL -> flags[intent.getIntExtra(EXTRA_JOB, -1)]?.set(true)
            ACTION_CANCEL_ALL -> flags.values.forEach { it.set(true) }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                val id = intent?.getIntExtra(EXTRA_JOB, -1) ?: -1
                val format = runCatching {
                    DownloadFormat.valueOf(intent?.getStringExtra(EXTRA_FORMAT) ?: "BEST")
                }.getOrDefault(DownloadFormat.BEST)
                val trimStart = intent?.getIntExtra(EXTRA_TRIM_START, -1) ?: -1
                val trimEnd = intent?.getIntExtra(EXTRA_TRIM_END, -1) ?: -1
                if (!url.isNullOrBlank() && id >= 0) {
                    val cancelled = AtomicBoolean(false)
                    flags[id] = cancelled
                    DownloadStore.add(DownloadStore.Task(id, url, shortTitle(url), DownloadStore.State.QUEUED, 0, format.label))
                    pool.execute { runJob(id, url, format, trimStart, trimEnd, cancelled) }
                }
            }
        }
        finishIfIdle()
        updateNotification()
        return START_NOT_STICKY
    }

    private fun runJob(id: Int, url: String, format: DownloadFormat, trimStart: Int, trimEnd: Int, cancelled: AtomicBoolean) {
        if (cancelled.get()) {
            DownloadStore.setState(id, DownloadStore.State.CANCELLED)
            DownloadBus.emit(DownloadBus.Update(id, 0, "", DownloadBus.Phase.CANCELLED, "Cancelled"))
            flags.remove(id)
            updateNotification()
            finishIfIdle()
            return
        }
        DownloadStore.setState(id, DownloadStore.State.DOWNLOADING)
        updateNotification()

        val outcome = Downloader.run(
            applicationContext, url, format, cancelled,
            onProgress = { pct, line ->
                DownloadStore.setProgress(id, pct)
                DownloadBus.emit(DownloadBus.Update(id, pct, line, DownloadBus.Phase.PROGRESS, ""))
                updateNotification()
            },
            trimStartSec = trimStart, trimEndSec = trimEnd,
        )

        when (outcome) {
            is Outcome.Ok -> {
                DownloadStore.setTitle(id, outcome.file.name)
                DownloadStore.setState(id, DownloadStore.State.DONE)
                MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(outcome.file.absolutePath), null,
                ) { _, uri: Uri? ->
                    HistoryStore.add(applicationContext, HistoryStore.Entry(outcome.file.name, System.currentTimeMillis(), uri?.toString()))
                    Notifications.complete(this, id, outcome.file.name, uri)
                }
                DownloadBus.emit(DownloadBus.Update(id, 100, "", DownloadBus.Phase.DONE_OK, "Done ✓"))
            }
            is Outcome.Err -> {
                DownloadStore.setState(id, DownloadStore.State.FAILED)
                Notifications.failed(this, id, outcome.message)
                DownloadBus.emit(DownloadBus.Update(id, 0, "", DownloadBus.Phase.DONE_FAIL, outcome.message))
            }
            Outcome.Cancelled -> {
                DownloadStore.setState(id, DownloadStore.State.CANCELLED)
                DownloadBus.emit(DownloadBus.Update(id, 0, "", DownloadBus.Phase.CANCELLED, "Cancelled"))
            }
        }
        flags.remove(id)
        updateNotification()
        finishIfIdle()
    }

    private fun enterForeground() {
        try {
            val notif = Notifications.buildQueue(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(SERVICE_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(SERVICE_NOTIF_ID, notif)
            }
            isForeground = true
        } catch (e: Throwable) {
            // Rare OEM restriction; downloads still proceed.
        }
    }

    private fun updateNotification() {
        if (isForeground) {
            try {
                NotificationManagerCompat.from(this).notify(SERVICE_NOTIF_ID, Notifications.buildQueue(this))
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS not granted — ignore.
            }
        }
    }

    private fun finishIfIdle() {
        if (flags.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            isForeground = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun shortTitle(url: String): String {
        val host = runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull()
        return host ?: url.take(40)
    }

    companion object {
        const val ACTION_CANCEL = "com.example.mone.CANCEL"
        const val ACTION_CANCEL_ALL = "com.example.mone.CANCEL_ALL"
        const val EXTRA_URL = "url"
        const val EXTRA_JOB = "job"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_TRIM_START = "trim_start"
        const val EXTRA_TRIM_END = "trim_end"
        private const val SERVICE_NOTIF_ID = 990001
        private const val MAX_CONCURRENT = 2
        private val counter = AtomicInteger(1)

        /** Adds a download to the queue; returns its id. Trim seconds default to -1 (no trim). */
        fun enqueue(
            context: Context,
            url: String,
            format: DownloadFormat = DownloadFormat.BEST,
            trimStart: Int = -1,
            trimEnd: Int = -1,
        ): Int {
            val id = counter.getAndIncrement()
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_JOB, id)
                putExtra(EXTRA_FORMAT, format.name)
                putExtra(EXTRA_TRIM_START, trimStart)
                putExtra(EXTRA_TRIM_END, trimEnd)
            }
            ContextCompat.startForegroundService(context, intent)
            return id
        }

        fun cancel(context: Context, id: Int) {
            context.startService(
                Intent(context, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_JOB, id)
                },
            )
        }

        fun cancelAll(context: Context) {
            context.startService(
                Intent(context, DownloadService::class.java).apply { action = ACTION_CANCEL_ALL },
            )
        }
    }
}
