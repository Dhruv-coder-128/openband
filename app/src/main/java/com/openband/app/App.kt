package com.openband.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch
import com.openband.app.ble.BandManager
import com.openband.app.ble.BandService
import com.openband.app.sync.SyncWorker
import com.openband.app.ui.RaceSettings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        RaceSettings.init(this)
        BandManager.init(this)
        com.openband.app.workout.WorkoutManager.init(this)
        // none of these may take the app down on start-up
        runCatching { BandService.createChannel(this) }.onFailure { Log.w("App", "channel", it) }
        runCatching { com.openband.app.live.LiveService.createChannels(this) }
        runCatching { com.openband.app.live.LiveService.sync(this) }
        runCatching { SyncWorker.schedule(this) }.onFailure { Log.w("App", "worker", it) }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { com.openband.app.health.HealthRepo(this@App).cleanupBogusTemperature() }
            runCatching { com.openband.app.widget.WidgetUpdater.ensurePreviews(this@App) }
        }
    }
}
