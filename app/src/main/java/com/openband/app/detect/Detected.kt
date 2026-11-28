package com.openband.app.detect

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openband.app.MainActivity
import com.openband.app.R
import com.openband.app.data.Calories
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Sample
import com.openband.app.data.Workout
import com.openband.app.live.LiveService
import com.openband.app.ui.RaceSettings
import com.openband.app.workout.Energy
import com.openband.app.workout.Sports
import com.openband.app.workout.WorkoutManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Activities noticed by the phone that the user has not answered about yet. */
object Detected {
    const val ACTION_SAVE = "com.openband.app.DETECTED_SAVE"
    const val ACTION_DISMISS = "com.openband.app.DETECTED_DISMISS"
    const val EXTRA_SEGMENT = "segment"
    /** Questions older than this are dropped. */
    private const val KEEP_MS = 3 * 86_400_000L

    val changes = MutableStateFlow(0)

    private fun sp(c: Context) = c.getSharedPreferences("detect", Context.MODE_PRIVATE)

    private fun load(c: Context) = ActivitySegments.State(
        sp(c).getString("current", null)?.let { ActivitySegments.Segment.decode(it) },
        sp(c).getString("ready", "")!!.split('\n').mapNotNull { ActivitySegments.Segment.decode(it) },
    )

    private fun store(c: Context, s: ActivitySegments.State) {
        sp(c).edit().putString("current", s.current?.encode()).putString("ready", s.ready.joinToString("\n") { it.encode() }).apply()
        changes.value++
    }

    @Synchronized
    fun record(c: Context, sport: String, entering: Boolean, at: Long) = store(c, ActivitySegments.event(load(c), sport, entering, at))

    fun pending(c: Context): List<ActivitySegments.Segment> = load(c).ready.sortedByDescending { it.start }

    /** Finishes activities whose pause has run out and asks about the new ones. */
    @Synchronized
    fun check(c: Context, now: Long = System.currentTimeMillis()) {
        val before = load(c)
        var s = ActivitySegments.settle(before, now)
        // nothing to ask about when it is already recorded (here or by the Pebble app) or being recorded right now
        val recorded = Db.get(c).workouts(100).map { it.start to it.end } +
            listOfNotNull(WorkoutManager.active.value?.let { it.startMs to now })
        val keep = s.ready.filter { now - it.end < KEEP_MS && !ActivitySegments.covered(it, recorded) }
        s = s.copy(ready = keep)
        if (s != before) store(c, s)
        val asked = sp(c).getStringSet("asked", emptySet())!!
        val fresh = keep.filter { it.encode() !in asked }
        before.ready.filter { it !in keep }.forEach { cancel(c, it) }
        if (fresh.isNotEmpty()) {
            sp(c).edit().putStringSet("asked", keep.map { it.encode() }.toSet()).apply()
            fresh.forEach { ask(c, it) }
        }
    }

    fun title(sport: String) = when (sport) { ActivitySegments.RUN -> "Did you go for a run?"; ActivitySegments.BIKE -> "Did you go cycling?"; else -> "Did you go for a walk?" }

    fun describe(s: ActivitySegments.Segment): String {
        val time = SimpleDateFormat("h:mm a", Locale.getDefault())
        return "${s.length / 60_000} min · ${time.format(Date(s.start))} to ${time.format(Date(s.end))}"
    }

    private fun id(s: ActivitySegments.Segment) = 1000 + (s.start / 60_000 % 100_000).toInt()

    private fun action(c: Context, what: String, s: ActivitySegments.Segment) = PendingIntent.getBroadcast(
        c, id(s) * 2 + if (what == ACTION_SAVE) 0 else 1,
        Intent(c, ActivityReceiver::class.java).setAction(what).putExtra(EXTRA_SEGMENT, s.encode()),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ask(c: Context, s: ActivitySegments.Segment) {
        LiveService.createChannels(c)
        val open = PendingIntent.getActivity(
            c, id(s), Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_OPEN, "home"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, LiveService.ALERTS)
            .setSmallIcon(R.drawable.ic_stat_band).setContentTitle(title(s.sport)).setContentText(describe(s))
            .setContentIntent(open).setAutoCancel(true)
            .addAction(0, "Yes, save it", action(c, ACTION_SAVE, s))
            .addAction(0, "No", action(c, ACTION_DISMISS, s))
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id(s), n) }
    }

    private fun cancel(c: Context, s: ActivitySegments.Segment) = runCatching { c.getSystemService(NotificationManager::class.java).cancel(id(s)) }

    @Synchronized
    fun dismiss(c: Context, s: ActivitySegments.Segment) {
        val state = load(c)
        store(c, state.copy(ready = state.ready.filter { it.start != s.start }))
        cancel(c, s)
    }

    /** Saves the activity as a workout (once), optionally as a different sport than detected. */
    @Synchronized
    fun save(c: Context, s: ActivitySegments.Segment, sport: String = s.sport) {
        val state = load(c)
        if (state.ready.none { it.start == s.start }) { cancel(c, s); return }
        Db.get(c).insertWorkout(workout(c, s, sport))
        dismiss(c, s)
    }

    /** The part of each record that falls inside the window, from the single source that saw the most. */
    private fun share(rows: List<Sample>, from: Long, to: Long): Double = rows.groupBy { it.source }.values.maxOfOrNull { list ->
        list.sumOf { r ->
            if (r.endTs <= r.ts) (if (r.ts in from until to) r.value else 0.0)
            else r.value * ((minOf(r.endTs, to) - maxOf(r.ts, from)).coerceAtLeast(0).toDouble() / (r.endTs - r.ts))
        }
    } ?: 0.0

    /** What the band recorded during that time: heart rate, steps, distance and calories. */
    fun workout(c: Context, s: ActivitySegments.Segment, sport: String): Workout {
        val db = Db.get(c)
        val p = RaceSettings.prefs.value
        val def = Sports.byId(sport)
        // records can start before the window and still overlap it
        fun around(type: String) = db.series(type, s.start - 3_600_000L, s.end)
        val hr = db.series(M.HR, s.start, s.end + 1).map { ((it.ts - s.start) / 1000).toInt() to it.value.toInt() }.filter { it.second in 30..230 }
        val onFoot = sport != ActivitySegments.BIKE
        val steps = if (onFoot) share(around(M.STEPS), s.start, s.end).toInt() else 0
        val distance = if (onFoot) share(around(M.DISTANCE), s.start, s.end) else 0.0
        val seconds = s.length / 1000.0
        val average = hr.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
        val kmh = if (distance > 100) distance / seconds * 3.6 else null
        val energy = Energy.estimate(
            def.id, def.met, def.steps, seconds, p.weightKg.toDouble(), kmh, average?.let { it / p.maxHr }, p.calTotal,
            share(around(M.CALORIES), s.start, s.end), Calories.fromHr(hr, seconds.toInt(), p), Calories.bmrPerDay(p) / 1440.0,
        )
        return Workout(
            0, def.id, s.start, s.end, 0, distance, steps, energy.kcal, average?.toInt() ?: 0,
            hr.maxOfOrNull { it.second } ?: 0, hr.minOfOrNull { it.second } ?: 0, hr, emptyList(), false,
            extra = JSONObject().put("method", energy.method).put("detected", true).toString(),
        )
    }
}
