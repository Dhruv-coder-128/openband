package com.openband.app.detect

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.openband.app.ui.RaceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Asks Android (Google Play services, free) to say when the phone starts and stops walking, running or cycling. It is
 * the phone that is sensed, so the phone has to be on you.
 */
object ActivityDetect {
    private const val TAG = "ActivityDetect"
    const val ACTION_TRANSITION = "com.openband.app.ACTIVITY_TRANSITION"
    const val ACTION_CHECK = "com.openband.app.ACTIVITY_CHECK"

    private val TYPES = mapOf(
        DetectedActivity.WALKING to ActivitySegments.WALK,
        DetectedActivity.RUNNING to ActivitySegments.RUN,
        DetectedActivity.ON_BICYCLE to ActivitySegments.BIKE,
    )

    fun permission() = if (Build.VERSION.SDK_INT >= 29) Manifest.permission.ACTIVITY_RECOGNITION else "com.google.android.gms.permission.ACTIVITY_RECOGNITION"

    fun permitted(c: Context) = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(c, permission()) == PackageManager.PERMISSION_GRANTED

    // transitions add their data to this intent, so it has to be mutable; it is explicit, only our receiver gets it
    private fun transitions(c: Context) = PendingIntent.getBroadcast(
        c, 40, Intent(c, ActivityReceiver::class.java).setAction(ACTION_TRANSITION),
        PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0,
    )

    /** Starts or stops watching to match the setting. Safe to call often. */
    @SuppressLint("MissingPermission")
    fun sync(c: Context, result: (String?) -> Unit = {}) {
        val ctx = c.applicationContext
        runCatching {
            val client = ActivityRecognition.getClient(ctx)
            if (!RaceSettings.prefs.value.autoDetect) { if (permitted(ctx)) client.removeActivityTransitionUpdates(transitions(ctx)); result(null); return }
            if (!permitted(ctx)) { result("Permission is needed first."); return }
            val list = TYPES.keys.flatMap { type ->
                listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .map { ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build() }
            }
            client.requestActivityTransitionUpdates(ActivityTransitionRequest(list), transitions(ctx))
                .addOnSuccessListener { Log.i(TAG, "watching"); result(null) }
                .addOnFailureListener { Log.w(TAG, "not available", it); result("This phone could not start activity detection: ${it.message}") }
        }.onFailure { Log.w(TAG, "sync", it); result("Activity detection is not available on this phone.") }
    }

    /** Wake up once the pause after an activity is long enough to call it finished. */
    fun checkLater(c: Context) {
        val pi = PendingIntent.getBroadcast(
            c, 41, Intent(c, ActivityReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        c.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ActivitySegments.JOIN_MS + 30_000, pi)
    }

    class Event(val sport: String, val entering: Boolean, val at: Long)

    fun events(intent: Intent): List<Event> {
        if (!ActivityTransitionResult.hasResult(intent)) return emptyList()
        val result = ActivityTransitionResult.extractResult(intent) ?: return emptyList()
        return result.transitionEvents.mapNotNull { e ->
            val sport = TYPES[e.activityType] ?: return@mapNotNull null
            // events carry time since boot
            val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - e.elapsedRealTimeNanos) / 1_000_000
            Event(sport, e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER, at)
        }
    }
}

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ActivityDetect.ACTION_TRANSITION -> {
                        val events = ActivityDetect.events(intent)
                        events.forEach { Detected.record(app, it.sport, it.entering, it.at) }
                        Detected.check(app)
                        if (events.any { !it.entering }) ActivityDetect.checkLater(app)
                    }
                    ActivityDetect.ACTION_CHECK -> Detected.check(app)
                    Detected.ACTION_SAVE -> intent.getStringExtra(Detected.EXTRA_SEGMENT)?.let { ActivitySegments.Segment.decode(it) }?.let { Detected.save(app, it) }
                    Detected.ACTION_DISMISS -> intent.getStringExtra(Detected.EXTRA_SEGMENT)?.let { ActivitySegments.Segment.decode(it) }?.let { Detected.dismiss(app, it) }
                }
            } catch (e: Throwable) {
                Log.w("ActivityDetect", "receive", e)
            } finally {
                pending.finish()
            }
        }
    }
}
