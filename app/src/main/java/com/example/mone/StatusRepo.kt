package com.example.mone

import android.os.Environment
import java.io.File

/** Finds WhatsApp statuses the user has already viewed (WhatsApp caches them locally). */
object StatusRepo {

    private val candidateDirs: List<String> = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
        "Android/media/com.whatsapp.w4b/WhatsApp/Media/.Statuses",
        "WhatsApp/Media/.Statuses", // legacy (Android 10 and below)
    )

    private val mediaExts = setOf("jpg", "jpeg", "png", "webp", "mp4")

    fun isVideo(file: File) = file.extension.equals("mp4", ignoreCase = true)

    /** All viewed statuses across WhatsApp + Business, newest first. */
    fun statuses(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return candidateDirs
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.asList() ?: emptyList() }
            .filter { it.isFile && it.extension.lowercase() in mediaExts && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
    }
}
