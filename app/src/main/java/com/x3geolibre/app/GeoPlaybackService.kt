package com.x3geolibre.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Foreground service that keeps TapGPT alive while the glasses' display sleeps.
 *
 * Typed microphone + mediaPlayback so ChatGPT's voice session keeps capturing
 * the mic and playing back audio when the Activity is backgrounded (the sleep
 * button fires onPause while the user is still wearing the glasses — see the X3
 * starter guide, gotcha #5). Android silently revokes the mic for a backgrounded
 * app without a microphone-typed FGS and a posted notification, so both live here.
 */
class GeoPlaybackService : Service() {
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapGPT:ForegroundAudio").apply {
                setReferenceCounted(false)
            }
    }
    private val wifiLock: WifiManager.WifiLock? by lazy {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TapGPT:ForegroundAudio").apply {
                setReferenceCounted(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        Log.d(TAG, "playback service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        Log.d(TAG, "playback service foreground startId=$startId")
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        Log.d(TAG, "playback service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        runCatching {
            if (!wakeLock.isHeld) wakeLock.acquire()
        }.onFailure {
            Log.w(TAG, "Unable to acquire foreground wake lock: ${it.message}")
        }
        runCatching {
            val lock = wifiLock
            if (lock != null && !lock.isHeld) lock.acquire()
        }.onFailure {
            Log.w(TAG, "Unable to acquire foreground Wi-Fi lock: ${it.message}")
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TapGPT voice",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps ChatGPT voice + audio running while the display is off."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("X3GeoLibre")
            .setContentText("ChatGPT voice session active")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "X3GeoLibre"
        private const val CHANNEL_ID = "tapgpt_playback"
        private const val NOTIFICATION_ID = 9018
    }
}
