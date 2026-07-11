package com.example.mone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.widget.Button
import androidx.core.content.ContextCompat
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var loginButton: Button

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
        val downloadButton = findViewById<Button>(R.id.downloadButton)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val statusText = findViewById<TextView>(R.id.statusText)
        loginButton = findViewById(R.id.loginButton)

        loginButton.setOnClickListener { onLoginButtonClicked() }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) {
                statusText.text = "Paste a link first."
                return@setOnClickListener
            }
            downloadButton.isEnabled = false
            progressBar.visibility = ProgressBar.VISIBLE
            progressBar.progress = 0
            statusText.text = "Starting…"

            Downloader.download(
                this,
                url,
                onProgress = { pct, line ->
                    progressBar.progress = pct
                    statusText.text = line
                },
                onResult = { _, message ->
                    statusText.text = message
                    downloadButton.isEnabled = true
                    progressBar.visibility = ProgressBar.GONE
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLoginButton()
    }

    private fun cookiesFile() = Downloader.cookiesFile(this)

    private fun refreshLoginButton() {
        loginButton.text =
            if (cookiesFile().exists()) "Instagram: logged in ✓  (tap to log out)"
            else "Log in to Instagram"
    }

    private fun onLoginButtonClicked() {
        if (cookiesFile().exists()) {
            cookiesFile().delete()
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
