package com.example.mone

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Runs downloads as a foreground service so they survive the app being backgrounded. */
class DownloadService : Service() {

    private val jobs = ConcurrentHashMap<Int, AtomicBoolean>()
    @Volatile private var foregroundId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val jobId = intent.getIntExtra(EXTRA_JOB, -1)
                jobs[jobId]?.set(true)
                if (jobs.isEmpty()) stopSelf()
            }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                val jobId = intent?.getIntExtra(EXTRA_JOB, -1) ?: -1
                if (url.isNullOrBlank() || jobId < 0) {
                    if (jobs.isEmpty()) stopSelf()
                    return START_NOT_STICKY
                }
                val cancelled = AtomicBoolean(false)
                jobs[jobId] = cancelled
                startForegroundFor(jobId)
                foregroundId = jobId
                thread { runJob(jobId, url, cancelled) }
            }
        }
        return START_NOT_STICKY
    }

    private fun runJob(jobId: Int, url: String, cancelled: AtomicBoolean) {
        val outcome = Downloader.run(applicationContext, url, cancelled) { pct, line ->
            if (!cancelled.get()) {
                Notifications.progress(this, jobId, pct, indeterminate = pct <= 0)
                DownloadBus.emit(DownloadBus.Update(jobId, pct, line, DownloadBus.Phase.PROGRESS, ""))
            }
        }
        when (outcome) {
            is Outcome.Ok -> {
                MediaScannerConnection.scanFile(
                    applicationContext, arrayOf(outcome.file.absolutePath), null,
                ) { _, uri ->
                    HistoryStore.add(
                        applicationContext,
                        HistoryStore.Entry(outcome.file.name, System.currentTimeMillis(), uri?.toString()),
                    )
                    Notifications.complete(this, jobId, outcome.file.name, uri)
                    DownloadBus.emit(
                        DownloadBus.Update(jobId, 100, "", DownloadBus.Phase.DONE_OK, "Done ✓\nSaved to Mone folder & gallery"),
                    )
                    finishJob(jobId)
                }
            }
            is Outcome.Err -> {
                Notifications.failed(this, jobId, outcome.message)
                DownloadBus.emit(DownloadBus.Update(jobId, 0, "", DownloadBus.Phase.DONE_FAIL, outcome.message))
                finishJob(jobId)
            }
            Outcome.Cancelled -> {
                Notifications.remove(this, jobId)
                DownloadBus.emit(DownloadBus.Update(jobId, 0, "", DownloadBus.Phase.CANCELLED, "Cancelled"))
                finishJob(jobId)
            }
        }
    }

    private fun finishJob(jobId: Int) {
        jobs.remove(jobId)
        if (jobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            stopSelf()
        } else if (jobId == foregroundId) {
            // Re-anchor the foreground to another still-running job.
            val next = jobs.keys.firstOrNull() ?: return
            foregroundId = next
            startForegroundFor(next)
        }
    }

    private fun startForegroundFor(jobId: Int) {
        val notif = Notifications.buildProgress(this, jobId, 0, true)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(jobId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(jobId, notif)
            }
        } catch (e: Throwable) {
            // Rare OEM restriction; download still proceeds without the foreground guarantee.
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.example.mone.CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_JOB = "job"
        private val counter = AtomicInteger(1)

        /** Starts a download; returns its job id so the UI can track/cancel it. */
        fun enqueue(context: Context, url: String): Int {
            val jobId = counter.getAndIncrement()
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_JOB, jobId)
            }
            ContextCompat.startForegroundService(context, intent)
            return jobId
        }

        fun cancel(context: Context, jobId: Int) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB, jobId)
            }
            context.startService(intent)
        }
    }
}
