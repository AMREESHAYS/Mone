package com.example.mone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.CookieManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var loginButton: Button
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
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        Notifications.ensureChannel(this)
        ensureStorageAccess()
        ensureNotificationPermission()

        val urlInput = findViewById<EditText>(R.id.urlInput)
        downloadButton = findViewById(R.id.downloadButton)
        cancelButton = findViewById(R.id.cancelButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        loginButton = findViewById(R.id.loginButton)

        loginButton.setOnClickListener { onLoginButtonClicked() }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.statusButton).setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }
        findViewById<Button>(R.id.queueButton).setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }

        downloadButton.setOnClickListener {
            val text = urlInput.text.toString().trim()
            if (text.isEmpty()) {
                statusText.text = "Paste a link first."
                return@setOnClickListener
            }
            val urls = Regex("""https?://\S+""").findAll(text).map { it.value }.toList()
                .ifEmpty { listOf(text) }

            if (urls.size == 1) {
                activeJobId = DownloadService.enqueue(this, urls[0])
                statusText.text = "Starting…"
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                downloadButton.isEnabled = false
                cancelButton.visibility = View.VISIBLE
            } else {
                urls.forEach { DownloadService.enqueue(this, it) }
                Toast.makeText(this, "Added ${urls.size} to the queue", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, QueueActivity::class.java))
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

    override fun onResume() {
        super.onResume()
        refreshLoginButton()
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

    private fun refreshLoginButton() {
        loginButton.text =
            if (Downloader.cookiesFile(this).exists()) "Instagram: logged in ✓  (tap to log out)"
            else "Log in to Instagram"
    }

    private fun onLoginButtonClicked() {
        if (Downloader.cookiesFile(this).exists()) {
            Downloader.cookiesFile(this).delete()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
            refreshLoginButton()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Why log in?")
                .setMessage(
                    "Instagram only lets logged-in users download reels.\n\n" +
                        "Sign in with your account so Mone can save them for you. " +
                        "Your login stays on your phone — it's never shared.",
                )
                .setNegativeButton("Not now", null)
                .setPositiveButton("Continue") { _, _ ->
                    startActivity(Intent(this, LoginActivity::class.java))
                }
                .show()
        }
    }

    /** Ask for notification permission (Android 13+) so download alerts can show. */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** On first launch, ask for the file access Mone needs to save into its own folder. */
    private fun ensureStorageAccess() {
        if (Downloader.hasStorageAccess()) return
        AlertDialog.Builder(this)
            .setTitle("Allow file access")
            .setMessage("Mone saves videos into a \"Mone\" folder on your storage. Please turn on \"All files access\" on the next screen.")
            .setCancelable(false)
            .setNegativeButton("Later", null)
            .setPositiveButton("Open settings") { _, _ -> openAllFilesAccess() }
            .show()
    }

    private fun openAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
