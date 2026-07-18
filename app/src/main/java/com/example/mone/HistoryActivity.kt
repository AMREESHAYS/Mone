package com.example.mone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shows past downloads; tap an item to play it. */
class HistoryActivity : AppCompatActivity() {

    private val dateFmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        findViewById<View>(R.id.historyRoot).applyBarInsets()

        val list = findViewById<ListView>(R.id.historyList)
        val empty = findViewById<TextView>(R.id.emptyView)
        val clear = findViewById<Button>(R.id.clearButton)

        val entries = HistoryStore.all(this)
        list.emptyView = empty
        if (entries.isEmpty()) empty.visibility = View.VISIBLE

        list.adapter = object : ArrayAdapter<HistoryStore.Entry>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, entries,
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = entries[position]
                v.findViewById<TextView>(android.R.id.text1).text = e.name
                v.findViewById<TextView>(android.R.id.text2).text = dateFmt.format(Date(e.time))
                return v
            }
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val uri = entries[position].uri
            if (uri == null) {
                Toast.makeText(this, "File not available.", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(Uri.parse(uri), "video/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Can't open this file.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        clear.setOnClickListener {
            HistoryStore.clear(this)
            recreate()
        }
    }
}
