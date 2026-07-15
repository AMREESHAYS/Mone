package com.example.mone

import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * In-app Instagram login. The user signs into their OWN account here; we capture
 * their cookies into cookies.txt (Netscape format) so the downloader can fetch
 * login-gated reels as them. No sessions are shared between users.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val progress = findViewById<ProgressBar>(R.id.loginProgress)
        webView = findViewById(R.id.webView)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Report a Chrome UA with the user's REAL device (so Instagram's login alert
            // shows their actual phone, not a hardcoded "Pixel 7"). A Chrome-style UA is
            // used because Instagram blocks the raw WebView user-agent.
            val model = android.os.Build.MODEL.ifBlank { "Android" }
            val release = android.os.Build.VERSION.RELEASE.ifBlank { "13" }
            userAgentString =
                "Mozilla/5.0 (Linux; Android $release; $model) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com").orEmpty()
                // sessionid only appears after a successful login.
                if (cookies.contains("sessionid=") && saveCookies(cookies)) {
                    CookieManager.getInstance().flush()
                    Toast.makeText(this@LoginActivity, "Logged in ✓", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }

        webView.loadUrl("https://www.instagram.com/accounts/login/")
    }

    /** Writes the WebView cookies to cookies.txt in Netscape format for yt-dlp. */
    private fun saveCookies(cookieString: String): Boolean {
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        cookieString.split(";").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                // domain, includeSubdomains, path, secure, expiry, name, value
                sb.append(".instagram.com\tTRUE\t/\tTRUE\t4102444800\t$name\t$value\n")
            }
        }
        return try {
            Downloader.cookiesFile(this).writeText(sb.toString())
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
