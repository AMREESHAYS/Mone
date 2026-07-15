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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Home hub: navigation to each screen, plus Instagram login and launch-time setup. */
class HomeActivity : AppCompatActivity() {

    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.homeRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        Notifications.ensureChannel(this)
        YtdlpUpdater.maybeUpdate(this) // keep yt-dlp fresh (once/day)
        AppUpdater.maybeCheck(this) // offer a newer app release (once/day)
        ensureStorageAccess()
        ensureNotificationPermission()

        findViewById<Button>(R.id.downloadButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.statusButton).setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }
        findViewById<Button>(R.id.queueButton).setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        loginButton = findViewById(R.id.loginButton)
        loginButton.setOnClickListener { onLoginButtonClicked() }
    }

    override fun onResume() {
        super.onResume()
        refreshLoginButton()
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

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

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
