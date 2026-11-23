package com.remotebrowser.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared app state: the pause switch and the battery locks.
 * The phone is allowed to sleep; the CPU/Wi-Fi locks are only held while Claude is using the browser.
 */
object AppState {

    @Volatile var paused = false
        private set
    @Volatile var lastRequest = 0L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val logLines = java.util.ArrayDeque<String>()
    private var prefs: android.content.SharedPreferences? = null

    /** Settings the user controls (Setup > More). */
    val allowRunJs: Boolean get() = prefs?.getBoolean("allowRunJs", false) ?: false
    val desktopDefault: Boolean get() = prefs?.getBoolean("desktopDefault", false) ?: false
    val blockedSites: List<String>
        get() = (prefs?.getString("blockedSites", null) ?: DEFAULT_BLOCKED)
            .split('\n', ',', ' ').map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/') }
            .filter { it.isNotEmpty() }

    const val DEFAULT_BLOCKED =
        "mitid.dk\nborger.dk\nskat.dk\ne-boks.dk\nmit.dk\nsundhed.dk\nnetbank.dk\ndanskebank.dk\nnordea.dk\n" +
            "jyskebank.dk\nsydbank.dk\nspks.dk\narbejdernes.dk\nlunar.app\nrevolut.com\npaypal.com\nmobilepay.dk\n" +
            "mail.google.com\naccounts.google.com\noutlook.live.com\noutlook.office.com\nmail.yahoo.com"

    fun isBlocked(url: String?): Boolean {
        if (url == null) return false
        val host = try {
            android.net.Uri.parse(if (url.contains("://")) url else "https://$url").host?.lowercase() ?: return false
        } catch (e: Exception) {
            return false
        }
        return blockedSites.any { host == it || host.endsWith(".$it") }
    }

    fun addLog(line: String) {
        val t = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        synchronized(logLines) {
            logLines.addFirst("$t  $line")
            while (logLines.size > 300) logLines.removeLast()
        }
    }

    fun logText(): String = synchronized(logLines) { if (logLines.isEmpty()) "Nothing yet." else logLines.joinToString("\n") }
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val releaseTask = Runnable { releaseLocks() }

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (wakeLock != null) return
        val app = ctx.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteBrowser:busy").also {
            it.setReferenceCounted(false)
        }
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteBrowser:busy").also {
            it.setReferenceCounted(false)
        }
    }

    /** Called on every request from Claude: keep the phone awake until 30 s after the last one. */
    fun busy() {
        lastRequest = System.currentTimeMillis()
        try {
            wakeLock?.acquire(2 * 60 * 1000L)
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (e: Exception) {
        }
        handler.removeCallbacks(releaseTask)
        handler.postDelayed(releaseTask, 30_000)
    }

    fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
        }
    }

    fun setPaused(p: Boolean) {
        paused = p
        for (l in listeners) {
            try {
                l()
            } catch (e: Exception) {
            }
        }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}

/** Small ad/tracker blocklist: less to download and run, so pages are lighter on battery. */
object AdBlock {
    private val hosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com", "scorecardresearch.com",
        "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com", "moatads.com", "hotjar.com",
        "quantserve.com", "adsrvr.org", "bidswitch.net", "smartadserver.com", "yieldlab.net", "adform.net",
        "teads.tv", "media.net", "zemanta.com", "sharethrough.com", "3lift.com", "indexww.com", "clarity.ms",
        "adition.com", "improvedigital.com", "seedtag.com", "everesttech.net", "demdex.net", "omtrdc.net"
    )

    fun isBlocked(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        return hosts.any { h == it || h.endsWith(".$it") }
    }
}
