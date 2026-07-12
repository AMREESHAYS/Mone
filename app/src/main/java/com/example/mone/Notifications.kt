package com.example.mone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Download progress + completion notifications. */
object Notifications {
    private const val CHANNEL = "downloads"

    /** The app's launcher icon as a bitmap, for the notification's large icon. */
    private fun appIcon(context: Context): Bitmap? = try {
        val d = context.packageManager.getApplicationIcon(context.packageName)
        val bmp = Bitmap.createBitmap(
            d.intrinsicWidth.coerceAtLeast(1),
            d.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, canvas.width, canvas.height)
        d.draw(canvas)
        bmp
    } catch (e: Exception) {
        null
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /** Aggregate ongoing notification for the whole download queue (with Cancel-all). */
    fun buildQueue(context: Context): android.app.Notification {
        val (active, queued, done) = DownloadStore.counts()
        val current = DownloadStore.current()
        val pct = current?.percent ?: 0
        val text = when {
            current != null -> "${current.title}  ·  $pct%"
            queued > 0 -> "$queued queued"
            else -> "Working…"
        }
        val cancelAll = PendingIntent.getService(
            context, 0,
            Intent(context, DownloadService::class.java).apply { action = DownloadService.ACTION_CANCEL_ALL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mone)
            .setLargeIcon(appIcon(context))
            .setContentTitle(if (queued + active > 1) "Downloading ${active + queued} items" else "Downloading…")
            .setContentText(text)
            .setSubText(if (done > 0) "$done done" else null)
            .setProgress(100, pct.coerceIn(0, 100), current == null || pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel all", cancelAll)
            .build()
    }

    fun complete(context: Context, id: Int, title: String, uri: Uri?) {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mone)
            .setLargeIcon(appIcon(context))
            .setContentTitle("Saved to Mone ✓")
            .setContentText(title)
            .setAutoCancel(true)
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, id, view,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        notify(context, id, builder.build())
    }

    fun failed(context: Context, id: Int, message: String) {
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mone)
            .setLargeIcon(appIcon(context))
            .setContentTitle("Download failed")
            .setContentText(message.take(120))
            .setAutoCancel(true)
            .build()
        notify(context, id, n)
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — ignore.
        }
    }
}
