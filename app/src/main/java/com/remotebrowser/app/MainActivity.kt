package com.remotebrowser.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom

class MainActivity : Activity() {

    companion object {
        const val PORT = 8765
    }

    lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private var server: McpServer? = null
    private var tunnel: Tunnel? = null
    @Volatile private var tunnelStatus = "Tunnel: off"

    @Volatile var loading = false
    @Volatile var pageProgress = 100

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screen stays on while the app is open, so Claude can keep using it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = (8 * resources.displayMetrics.density).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        urlBar = EditText(this)
        urlBar.setSingleLine(true)
        urlBar.hint = "Search or address"
        urlBar.textSize = 14f
        urlBar.imeOptions = EditorInfo.IME_ACTION_GO
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openFromBar()
                true
            } else {
                false
            }
        }
        val goBtn = Button(this)
        goBtn.text = "Go"
        goBtn.setOnClickListener { openFromBar() }
        val setupBtn = Button(this)
        setupBtn.text = "Setup"
        setupBtn.setOnClickListener { showSettings() }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.addView(goBtn, LinearLayout.LayoutParams(wrap, wrap))
        bar.addView(setupBtn, LinearLayout.LayoutParams(wrap, wrap))

        statusView = TextView(this)
        statusView.textSize = 12f
        statusView.setPadding(pad, 0, pad, 0)
        logView = TextView(this)
        logView.textSize = 12f
        logView.setPadding(pad, 0, pad, pad / 2)
        logView.maxLines = 2
        webView = WebView(this)

        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        root.addView(statusView, LinearLayout.LayoutParams(match, wrap))
        root.addView(logView, LinearLayout.LayoutParams(match, wrap))
        root.addView(webView, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)

        setupWebView()
        startServer()
        startKeepAlive()
        startTunnel()
        webView.loadUrl("https://www.google.com")
    }

    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                if (!urlBar.hasFocus()) urlBar.setText(url ?: "")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme?.lowercase() ?: return false
                // Block app links (intent://, market:// ...) so pages stay in this browser.
                return scheme !in setOf("http", "https", "about", "data", "blob", "javascript")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                pageProgress = newProgress
            }
        }
    }

    private fun openFromBar() {
        val t = urlBar.text.toString().trim()
        if (t.isEmpty()) return
        webView.loadUrl(normalizeUrl(t))
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        webView.requestFocus()
    }

    fun secret(): String {
        val existing = prefs.getString("secret", null)
        if (existing != null) return existing
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val s = bytes.joinToString("") { String.format("%02x", it.toInt() and 0xff) }
        prefs.edit().putString("secret", s).apply()
        return s
    }

    private fun domain(): String = prefs.getString("domain", "") ?: ""

    private fun connectorUrl(): String {
        val d = domain().ifEmpty { "YOUR-NGROK-DOMAIN" }
        return "https://$d/${secret()}/mcp"
    }

    private fun appVersion(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }

    private fun token(): String = prefs.getString("token", "") ?: ""

    private fun updateStatus() {
        statusView.text = if (token().isEmpty() || domain().isEmpty()) {
            "Tap Setup and enter your ngrok token and domain."
        } else {
            tunnelStatus
        }
    }

    private fun startTunnel() {
        tunnel?.stop()
        tunnel = null
        if (token().isEmpty() || domain().isEmpty()) {
            tunnelStatus = "Tunnel: off"
            updateStatus()
            return
        }
        val t = Tunnel { msg ->
            tunnelStatus = msg
            runOnUiThread { updateStatus() }
        }
        tunnel = t
        t.start(token(), domain(), PORT)
    }

    private fun startKeepAlive() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't start background mode: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("Setup (v" + appVersion() + ")")
            .setMessage(
                "Connector URL for Claude:\n\n${connectorUrl()}\n\n" +
                    "ngrok token: " + (if (token().isEmpty()) "not set" else "set") + "\n" +
                    "ngrok domain: " + domain().ifEmpty { "not set" } + "\n\n" + tunnelStatus
            )
            .setPositiveButton("Copy URL") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Connector URL", connectorUrl()))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("ngrok settings") { _, _ -> askToken() }
            .setNegativeButton("More") { _, _ -> showMore() }
            .show()
    }

    private fun showMore() {
        AlertDialog.Builder(this)
            .setTitle("More")
            .setItems(arrayOf("Make a new secret key", "Restart tunnel", "Stop and close the app")) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().remove("secret").apply()
                        secret()
                        Toast.makeText(this, "New key made. Update the connector URL in Claude.", Toast.LENGTH_LONG).show()
                    }
                    1 -> startTunnel()
                    2 -> finishAndRemoveTask()
                }
            }
            .show()
    }

    private fun askToken() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.hint = "Paste your ngrok authtoken"
        input.setText(token())
        AlertDialog.Builder(this)
            .setTitle("ngrok authtoken")
            .setMessage("Find it on dashboard.ngrok.com under Your Authtoken.")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                prefs.edit().putString("token", input.text.toString().trim()).apply()
                askDomain()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askDomain() {
        val input = EditText(this)
        input.setSingleLine(true)
        input.hint = "your-name.ngrok-free.app"
        input.setText(domain())
        AlertDialog.Builder(this)
            .setTitle("Your ngrok domain")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val d = input.text.toString().trim()
                    .removePrefix("https://").removePrefix("http://").trimEnd('/')
                prefs.edit().putString("domain", d).apply()
                startTunnel()
                showSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startServer() {
        val controller = BrowserController(this)
        val handler = McpHandler(controller) { msg -> runOnUiThread { logView.text = "Claude: $msg" } }
        val s = McpServer(PORT, { secret() }, handler) { err -> runOnUiThread { statusView.text = err } }
        s.start()
        server = s
        updateStatus()
    }

    override fun onDestroy() {
        tunnel?.stop()
        stopService(Intent(this, KeepAliveService::class.java))
        server?.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back goes back a page; at the first page the app goes to the background instead of closing.
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }
}
