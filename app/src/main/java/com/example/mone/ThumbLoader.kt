package com.example.mone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/** Loads downsampled thumbnails for images and video files off the main thread. */
object ThumbLoader {

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private const val SIZE = 256

    /** Loads [file]'s thumbnail into [target], guarding against view recycling. */
    fun load(file: File, target: ImageView) {
        val key = file.absolutePath + ":" + file.lastModified()
        target.tag = key
        cache.get(key)?.let { target.setImageBitmap(it); return }
        target.setImageDrawable(null)
        executor.execute {
            val bmp = runCatching { decode(file) }.getOrNull() ?: return@execute
            cache.put(key, bmp)
            main.post { if (target.tag == key) target.setImageBitmap(bmp) }
        }
    }

    private fun decode(file: File): Bitmap? {
        return if (StatusRepo.isVideo(file)) {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                val frame = r.getFrameAtTime(0) ?: return null
                ThumbnailUtils.extractThumbnail(frame, SIZE, SIZE)
            } finally {
                r.release()
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, SIZE)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }
    }

    private fun sampleSize(w: Int, h: Int, target: Int): Int {
        var sample = 1
        var halfW = w / 2
        var halfH = h / 2
        while (halfW >= target && halfH >= target) {
            sample *= 2
            halfW /= 2
            halfH /= 2
        }
        return sample.coerceAtLeast(1)
    }
}
