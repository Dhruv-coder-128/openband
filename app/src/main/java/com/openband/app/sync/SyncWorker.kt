package com.openband.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openband.app.ble.BandManager
import com.openband.app.ble.BandService
import com.openband.app.health.HealthRepo
import java.util.concurrent.TimeUnit

/** Runs every 15 minutes: imports new Health Connect data and nudges the band connection. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        try {
            runCatching { HealthRepo(applicationContext).import() }
            // short connect -> sync -> release, so the official app keeps working
            runCatching { BandManager.backgroundSync() }
        } catch (_: Throwable) {
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("auto-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Restarts the band connection after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { BandService.start(context) }
        runCatching { SyncWorker.schedule(context) }
    }
}
