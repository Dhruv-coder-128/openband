package com.openband.app.workout

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openband.app.MainActivity
import com.openband.app.R
import com.openband.app.ble.BandService
import com.openband.app.ui.fmtDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps a workout alive with the screen off or the app in the background: GPS keeps flowing, the timer keeps running
 * and the notification shows the live numbers. Only runs while a workout is active.
 */
class WorkoutService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null

    companion object {
        private const val ID = 7
        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WorkoutService::class.java)) }
        }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, WorkoutService::class.java)) } }
    }

    private fun notification(text: String, title: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, BandService.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_band).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT).build()
    }

    override fun onCreate() {
        super.onCreate()
        BandService.createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ID, notification("Starting…", "Workout"), type)
        } catch (e: Throwable) {
            // e.g. location permission missing: fall back to a plain foreground service
            runCatching { ServiceCompat.startForeground(this, ID, notification("Starting…", "Workout"), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) }
                .onFailure { stopSelf(); return }
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "openband:workout").apply {
            runCatching { acquire(8 * 3600_000L) }
        }
        val nm = getSystemService(NotificationManager::class.java)
        scope.launch {
            while (true) {
                val a = WorkoutManager.active.value
                if (a != null) {
                    val km = a.distanceM / 1000.0
                    val txt = buildString {
                        append(fmtDuration(a.elapsedMs))
                        if (a.distanceM > 20) append("  ·  %.2f km".format(km))
                        a.hr?.let { append("  ·  ♥ $it") }
                        if (a.paused) append("  ·  paused")
                    }
                    runCatching { nm.notify(ID, notification(txt, a.sport.name)) }
                }
                delay(2000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        super.onDestroy()
    }
}
