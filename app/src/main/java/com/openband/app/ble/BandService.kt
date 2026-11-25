package com.openband.app.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openband.app.MainActivity
import com.openband.app.R
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps the band connected and syncs it periodically. */
class BandService : Service() {

    companion object {
        const val CHANNEL = "band"
        private const val NOTIF_ID = 1
        private const val SYNC_EVERY_MS = 30 * 60_000L

        fun start(ctx: Context) {
            val ok = runCatching { BandManager.savedAddress != null && BandManager.hasPermissions(ctx) && BandManager.alwaysOn }.getOrDefault(false)
            if (!ok) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BandService::class.java)) }
        }

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Band connection", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Keeps your band connected for automatic sync"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> android.util.Log.e("BandService", "error", e) })
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_band)
            .setContentTitle(BandManager.savedName ?: "OpenBand")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification("Starting…"), type)
            started = true
        } catch (e: Throwable) {
            android.util.Log.e("BandService", "startForeground failed", e)
            stopSelf(); return
        }

        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            BandManager.state
                .map { s ->
                    buildString {
                        append(s.status)
                        s.battery?.let { append(" · battery $it%") }
                        s.liveHr?.let { append(" · ♥ $it") }
                    }
                }
                .distinctUntilChanged()
                .collect { runCatching { nm.notify(NOTIF_ID, notification(it)) } }
        }
        // periodic history sync
        scope.launch {
            while (true) {
                delay(SYNC_EVERY_MS)
                runCatching { BandManager.sync() }
            }
        }
        // save live samples every minute
        scope.launch {
            while (true) {
                delay(60_000)
                runCatching { BandManager.flush() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) { stopSelf(); return START_NOT_STICKY }
        runCatching { BandManager.ensureConnected() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
