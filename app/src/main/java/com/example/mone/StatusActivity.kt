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

        val grid = findViewById<GridView>(R.id.statusGrid)
        val empty = findViewById<TextView>(R.id.emptyView)

        items.addAll(StatusRepo.statuses())
        if (items.isEmpty()) {
            grid.visibility = View.GONE
            empty.visibility = View.VISIBLE
        }
        grid.adapter = StatusAdapter()
    }

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
            val dir = File(Downloader.downloadDir(), "Statuses").apply { mkdirs() }
            var dest = File(dir, file.name)
            var i = 1
            while (dest.exists()) {
                dest = File(dir, "${file.nameWithoutExtension} ($i).${file.extension}")
                i++
            }
            val ok = runCatching { file.copyTo(dest, overwrite = false) }.isSuccess
            if (ok) MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), null, null)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Saved ✓  (Mone/Statuses)" else "Couldn't save this status.",
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
