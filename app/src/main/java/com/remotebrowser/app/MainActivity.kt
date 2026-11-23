package com.remotebrowser.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
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
    private lateinit var root: LinearLayout
    @Volatile var inOverlay = false
    @Volatile var desktopNow = false
    private lateinit var urlBar: EditText
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private var server: McpServer? = null
    private var controller: BrowserController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var askingSomething = false
    private val stateListener: () -> Unit = { runOnUiThread { updateStatus() } }
    private val idleCheck = object : Runnable {
        override fun run() {
            controller?.idleTick()
            mainHandler.postDelayed(this, 20_000)
        }
    }
    private var tunnel: Tunnel? = null
    @Volatile private var tunnelStatus = "Tunnel: off"

    @Volatile var loading = false
    @Volatile var pageProgress = 100

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)

        val pad = (8 * resources.displayMetrics.density).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        root = LinearLayout(this)
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
        AppState.addListener(stateListener)
        mainHandler.postDelayed(idleCheck, 20_000)
        checkForUpdate()
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
        if (AppState.desktopDefault) {
            s.userAgentString = DESKTOP_UA
            desktopNow = true
        }
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

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (AdBlock.isBlocked(request?.url?.host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
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
        statusView.text = if (AppState.paused) {
            "Paused: Claude can't use the browser. Setup > More > Resume, or use the notification."
        } else if (token().isEmpty() || domain().isEmpty()) {
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
            .setItems(arrayOf(
                    if (AppState.paused) "Resume Claude" else "Pause Claude",
                    "Make a new secret key", "Restart tunnel", "Stop and close the app",
                    "Allow background mode (display over other apps)", "Battery settings for this app",
                    "Check for updates",
                    "Activity log",
                    "Blocked sites",
                    if (AppState.allowRunJs) "Turn off run_js (advanced)" else "Allow run_js (advanced)",
                    "Default site version: " + (if (AppState.desktopDefault) "desktop (tap for phone)" else "phone (tap for desktop)")
                )) { _, which ->
                when (which) {
                    0 -> AppState.setPaused(!AppState.paused)
                    1 -> {
                        prefs.edit().remove("secret").apply()
                        secret()
                        Toast.makeText(this, "New key made. Update the connector URL in Claude.", Toast.LENGTH_LONG).show()
                    }
                    2 -> startTunnel()
                    3 -> finishAndRemoveTask()
                    4 -> askOverlayPermission()
                    5 -> askBatteryExemption()
                    6 -> checkForUpdate(true)
                    7 -> showLog()
                    8 -> editBlocked()
                    9 -> {
                        prefs.edit().putBoolean("allowRunJs", !AppState.allowRunJs).apply()
                        Toast.makeText(this, if (AppState.allowRunJs) "run_js is on" else "run_js is off", Toast.LENGTH_SHORT).show()
                    }
                    10 -> {
                        val d = !AppState.desktopDefault
                        prefs.edit().putBoolean("desktopDefault", d).apply()
                        webView.settings.userAgentString = if (d) DESKTOP_UA else null
                        desktopNow = d
                        webView.reload()
                        Toast.makeText(this, if (d) "Desktop version by default" else "Phone version by default", Toast.LENGTH_SHORT).show()
                    }
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
        val c = BrowserController(this)
        controller = c
        val handler = McpHandler(c) { msg -> runOnUiThread { logView.text = "Claude: $msg" } }
        val s = McpServer(PORT, { secret() }, handler) { err -> runOnUiThread { statusView.text = err } }
        s.start()
        server = s
        updateStatus()
    }

    private fun askOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle("Background mode")
            .setMessage(
                "To let Claude see and tap pages while you're in another app, allow Remote Browser to " +
                    "\"display over other apps\". The browser then keeps running in an invisible window " +
                    "that doesn't block your screen or taps."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { askingSomething = false }
            .show()
    }

    private fun showLog() {
        val tv = TextView(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad / 2, pad, pad / 2)
        tv.textSize = 12f
        tv.setTextIsSelectable(true)
        tv.text = AppState.logText()
        val sv = android.widget.ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Activity log (newest first)")
            .setView(sv)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun editBlocked() {
        val input = EditText(this)
        input.setText(prefs.getString("blockedSites", null) ?: AppState.DEFAULT_BLOCKED)
        input.textSize = 13f
        input.minLines = 6
        AlertDialog.Builder(this)
            .setTitle("Blocked sites")
            .setMessage("Claude can't open or stay on these sites (one per line, subdomains included).")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> prefs.edit().putString("blockedSites", input.text.toString()).apply() }
            .setNeutralButton("Reset to default") { _, _ -> prefs.edit().remove("blockedSites").apply() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun batteryExempt(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun askBatteryExemption() {
        askingSomething = true
        AlertDialog.Builder(this)
            .setTitle("Battery")
            .setMessage(
                "The app lets your phone sleep and only wakes up when Claude needs it. " +
                    "To make sure Android doesn't cut its connection while the screen is off, " +
                    "allow it to run without battery restrictions."
            )
            .setPositiveButton("Allow") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { askingSomething = false }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // One-time setup prompts, one at a time.
        if (askingSomething) return
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("askedOverlay", false)) {
            prefs.edit().putBoolean("askedOverlay", true).apply()
            askingSomething = true
            askOverlayPermission()
        } else if (!batteryExempt() && !prefs.getBoolean("askedBattery", false)) {
            prefs.edit().putBoolean("askedBattery", true).apply()
            askBatteryExemption()
        }
    }

    /** Looks at the latest GitHub release and offers to download it if it's newer. */
    private fun checkForUpdate(manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("lastUpdateCheck", 0L) < 60 * 60_000L) return
        prefs.edit().putLong("lastUpdateCheck", now).apply()
        Thread {
            try {
                val c = URL("https://api.github.com/repos/Dragonngame/Claude-Android-browser/releases/latest")
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val o = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = o.optString("tag_name")
                val latest = tag.substringAfterLast('.').toIntOrNull() ?: 0
                val mine = appVersion().substringAfterLast('.').toIntOrNull() ?: 0
                val assets = o.optJSONArray("assets")
                val apk = if (assets != null && assets.length() > 0) assets.getJSONObject(0).optString("browser_download_url") else ""
                runOnUiThread {
                    if (latest > mine && apk.isNotEmpty()) {
                        AlertDialog.Builder(this)
                            .setTitle("Update available: $tag")
                            .setMessage(o.optString("body").take(1500))
                            .setPositiveButton("Download") { _, _ ->
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk)))
                            }
                            .setNegativeButton("Later", null)
                            .show()
                    } else if (manual) {
                        Toast.makeText(this, "You have the latest version (v${appVersion()})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (manual) runOnUiThread { Toast.makeText(this, "Couldn't check: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun webViewParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)

    // When the app goes to the background, move the browser into an invisible overlay window
    // so Android keeps drawing it: screenshots and taps keep working.
    private fun moveWebViewToOverlay() {
        if (inOverlay || !Settings.canDrawOverlays(this)) return
        val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val w = if (webView.width > 0) webView.width else dm.widthPixels
        val h = if (webView.height > 0) webView.height else dm.heightPixels
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.alpha = 0f
        try {
            root.removeView(webView)
            wm.addView(webView, lp)
            inOverlay = true
        } catch (e: Exception) {
            if (webView.parent == null) root.addView(webView, webViewParams())
        }
    }

    private fun moveWebViewBack() {
        if (!inOverlay) return
        val wm = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeViewImmediate(webView)
        } catch (e: Exception) {
        }
        inOverlay = false
        if (webView.parent == null) root.addView(webView, webViewParams())
    }

    override fun onStart() {
        super.onStart()
        if (::webView.isInitialized) moveWebViewBack()
        val c = controller
        if (c != null) Thread { c.wake(true) }.start()
        if (c != null) checkForUpdate()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && ::webView.isInitialized) moveWebViewToOverlay()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleCheck)
        AppState.removeListener(stateListener)
        moveWebViewBack()
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
