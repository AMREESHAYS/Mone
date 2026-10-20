package com.example.mone

import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/** Grid of viewed WhatsApp statuses; tap to preview, tap ⬇ to save to the gallery. */
class StatusActivity : AppCompatActivity() {

    private val items = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)
        findViewById<View>(R.id.statusRoot).applyBarInsets()

        val grid = findViewById<GridView>(R.id.statusGrid)
        val empty = findViewById<TextView>(R.id.emptyView)

        items.addAll(StatusRepo.statuses())
        if (items.isEmpty()) {
            grid.visibility = View.GONE
            empty.visibility = View.VISIBLE
        }
        grid.adapter = StatusAdapter()

        findViewById<android.widget.Button>(R.id.saveAllButton).setOnClickListener { saveAll() }
    }

    /** Save every viewed status at once, organized into date sub-folders. */
    private fun saveAll() {
        if (items.isEmpty()) {
            Toast.makeText(this, "No statuses to save.", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Saving ${items.size} statuses…", Toast.LENGTH_SHORT).show()
        val toSave = items.toList()
        thread {
            val results = toSave.map { copyStatus(it) }
            val saved = results.count { it == true }
            val already = results.count { it == false }
            runOnUiThread {
                val skipped = if (already > 0) ", $already already saved" else ""
                Toast.makeText(this, "Saved $saved statuses ✓$skipped (Mone/Statuses)", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Copies [file] into Mone/Statuses/<date>/. Returns true if copied, false if an identical
     * copy (same name and size) is already there, null on failure. WhatsApp status file names
     * are unique, so name + size identifies a status; a size mismatch is a broken copy and is replaced.
     */
    private fun copyStatus(file: File): Boolean? = runCatching {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(file.lastModified()))
        val dest = File(Downloader.downloadDir(), "Statuses/$day/${file.name}")
        if (dest.exists() && dest.length() == file.length()) return@runCatching false
        dest.parentFile?.mkdirs()
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
        true
    }.getOrNull()

    private fun preview(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val type = if (StatusRepo.isVideo(file)) "video/*" else "image/*"
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    "Open with",
                ),
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this status.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save(file: File) {
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        thread {
            val result = copyStatus(file)
            runOnUiThread {
                Toast.makeText(
                    this,
                    when (result) {
                        true -> "Saved ✓  (Mone/Statuses)"
                        false -> "Already saved ✓"
                        null -> "Couldn't save this status."
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private inner class StatusAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_status, parent, false)
            val file = items[position]
            val thumb = view.findViewById<ImageView>(R.id.thumb)
            view.findViewById<ImageView>(R.id.playBadge).visibility =
                if (StatusRepo.isVideo(file)) View.VISIBLE else View.GONE

            ThumbLoader.load(file, thumb)
            thumb.setOnClickListener { preview(file) }
            view.findViewById<ImageView>(R.id.saveBadge).setOnClickListener { save(file) }
            return view
        }
    }
}
