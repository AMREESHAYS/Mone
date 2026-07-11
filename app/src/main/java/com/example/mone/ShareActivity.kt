package com.example.mone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Transparent dialog activity registered as a share target. Sharing a link from
 * Instagram (or anywhere) into Mone pops a confirm dialog with a Download button.
 */
class ShareActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = extractUrl(intent)
        if (url == null) {
            Toast.makeText(this, "No link found to download.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        showConfirmDialog(url)
    }

    /** Instagram shares text like "Check this out: https://…" — pull the first URL out. */
    private fun extractUrl(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        return Regex("""https?://\S+""").find(text)?.value
    }

    private fun showConfirmDialog(url: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Download this?")
            .setMessage(url)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download", null) // wired below so it doesn't auto-dismiss
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Dismiss immediately; download continues in the background and toasts when done.
                val app = applicationContext
                Toast.makeText(app, "Downloading…", Toast.LENGTH_SHORT).show()
                Downloader.download(
                    app,
                    url,
                    onProgress = { _, _ -> },
                    onResult = { success, message ->
                        Toast.makeText(
                            app,
                            if (success) "Saved to Mone ✓" else message,
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
                dialog.dismiss()
                finish()
            }
        }
        dialog.show()
    }
}
