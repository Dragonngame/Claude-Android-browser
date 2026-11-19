package com.remotebrowser.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder

/**
 * Foreground service: keeps the app's process (browser, server, tunnel) alive while you use other apps.
 * It does NOT keep the phone awake; AppState holds the CPU/Wi-Fi only while Claude is working.
 * The notification has a Pause/Resume button.
 */
class KeepAliveService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.remotebrowser.app.TOGGLE_PAUSE"
    }

    private val onStateChange: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("keepalive", "Remote Browser running", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, build())
        }
        AppState.addListener(onStateChange)
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val paused = AppState.paused
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
            if (paused) "Resume Claude" else "Pause Claude",
            toggle
        ).build()
        return Notification.Builder(this, "keepalive")
            .setContentTitle(if (paused) "Remote Browser is paused" else "Remote Browser is running")
            .setContentText(if (paused) "Claude can't use the browser until you resume." else "Claude can use the browser.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    private fun refresh() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) AppState.setPaused(!AppState.paused)
        return START_STICKY
    }

    override fun onDestroy() {
        AppState.removeListener(onStateChange)
        AppState.releaseLocks()
        super.onDestroy()
    }
}
