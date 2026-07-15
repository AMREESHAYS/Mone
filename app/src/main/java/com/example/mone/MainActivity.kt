package com.example.mone

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Download screen: paste a link, pick quality, optionally trim, download. */
class MainActivity : AppCompatActivity() {

    private lateinit var downloadButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private var activeJobId = -1
    private val busListener: (DownloadBus.Update) -> Unit = { onDownloadUpdate(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val urlInput = findViewById<EditText>(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        cancelButton = findViewById(R.id.cancelButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)

        val formatSpinner = findViewById<Spinner>(R.id.formatSpinner)
        formatSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            DownloadFormat.values().map { it.label },
        )

        val trimCheck = findViewById<CheckBox>(R.id.trimCheck)
        val trimRow = findViewById<View>(R.id.trimRow)
        val trimStart = findViewById<EditText>(R.id.trimStart)
        val trimEnd = findViewById<EditText>(R.id.trimEnd)
        trimCheck.setOnCheckedChangeListener { _, checked ->
            trimRow.visibility = if (checked) View.VISIBLE else View.GONE
        }

        downloadButton.setOnClickListener {
            val text = urlInput.text.toString().trim()
            if (text.isEmpty()) {
                statusText.text = "Paste a link first."
                return@setOnClickListener
            }
            val urls = Regex("""https?://\S+""").findAll(text).map { it.value }.toList()
                .ifEmpty { listOf(text) }
            val format = DownloadFormat.values()[formatSpinner.selectedItemPosition.coerceIn(0, DownloadFormat.values().lastIndex)]
            val tStart = if (trimCheck.isChecked) parseTime(trimStart.text.toString()) else -1
            val tEnd = if (trimCheck.isChecked) parseTime(trimEnd.text.toString()) else -1

            if (urls.size == 1) {
                activeJobId = DownloadService.enqueue(this, urls[0], format, tStart, tEnd)
                statusText.text = "Starting…"
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                downloadButton.isEnabled = false
                cancelButton.visibility = View.VISIBLE
            } else {
                urls.forEach { DownloadService.enqueue(this, it, format, tStart, tEnd) }
                Toast.makeText(this, "Added ${urls.size} to the queue", Toast.LENGTH_SHORT).show()
                startActivity(android.content.Intent(this, QueueActivity::class.java))
            }
        }

        cancelButton.setOnClickListener {
            if (activeJobId >= 0) DownloadService.cancel(this, activeJobId)
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadBus.addListener(busListener)
    }

    override fun onStop() {
        super.onStop()
        DownloadBus.removeListener(busListener)
    }

    private fun onDownloadUpdate(u: DownloadBus.Update) {
        if (u.jobId != activeJobId) return
        when (u.phase) {
            DownloadBus.Phase.PROGRESS -> {
                if (u.percent > 0) {
                    progressBar.isIndeterminate = false
                    progressBar.progress = u.percent
                } else {
                    progressBar.isIndeterminate = true
                }
                statusText.text = u.line
            }
            else -> {
                statusText.text = u.message
                activeJobId = -1
                resetIdle()
            }
        }
    }

    private fun resetIdle() {
        downloadButton.isEnabled = true
        cancelButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        progressBar.isIndeterminate = false
    }

    /** "m:ss" / "h:mm:ss" / plain seconds → seconds; -1 if blank or invalid. */
    private fun parseTime(s: String): Int {
        val t = s.trim()
        if (t.isEmpty()) return -1
        return try {
            val parts = t.split(":").map { it.trim().toInt() }
            when (parts.size) {
                1 -> parts[0]
                2 -> parts[0] * 60 + parts[1]
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                else -> -1
            }
        } catch (e: Exception) {
            -1
        }
    }
}
