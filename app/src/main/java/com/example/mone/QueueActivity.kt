package com.example.mone

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Live list of all downloads: queued, running, done, failed. Cancel per item or all. */
class QueueActivity : AppCompatActivity() {

    private var items: List<DownloadStore.Task> = emptyList()
    private lateinit var adapter: QueueAdapter
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private val listener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_queue)

        list = findViewById(R.id.queueList)
        empty = findViewById(R.id.emptyView)
        adapter = QueueAdapter()
        list.adapter = adapter

        findViewById<Button>(R.id.cancelAllButton).setOnClickListener {
            DownloadService.cancelAll(this)
        }
        findViewById<Button>(R.id.clearFinishedButton).setOnClickListener {
            DownloadStore.clearFinished()
        }
        refresh()
    }

    override fun onStart() {
        super.onStart()
        DownloadStore.addListener(listener)
        refresh()
    }

    override fun onStop() {
        super.onStop()
        DownloadStore.removeListener(listener)
    }

    private fun refresh() {
        items = DownloadStore.snapshot()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun subtitle(t: DownloadStore.Task): String = when (t.state) {
        DownloadStore.State.QUEUED -> "Queued"
        DownloadStore.State.DOWNLOADING -> if (t.percent > 0) "Downloading · ${t.percent}%" else "Downloading…"
        DownloadStore.State.DONE -> "Saved ✓"
        DownloadStore.State.FAILED -> "Failed"
        DownloadStore.State.CANCELLED -> "Cancelled"
    }

    private inner class QueueAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
            val t = items[position]
            view.findViewById<TextView>(R.id.queueTitle).text = t.title
            view.findViewById<TextView>(R.id.queueSubtitle).text = subtitle(t)

            val bar = view.findViewById<ProgressBar>(R.id.queueProgress)
            if (t.state == DownloadStore.State.DOWNLOADING) {
                bar.visibility = View.VISIBLE
                bar.isIndeterminate = t.percent <= 0
                if (t.percent > 0) bar.progress = t.percent
            } else {
                bar.visibility = View.GONE
            }

            val cancel = view.findViewById<ImageButton>(R.id.queueCancel)
            val active = t.state == DownloadStore.State.QUEUED || t.state == DownloadStore.State.DOWNLOADING
            cancel.visibility = if (active) View.VISIBLE else View.GONE
            cancel.setOnClickListener { DownloadService.cancel(this@QueueActivity, t.id) }
            return view
        }
    }
}
