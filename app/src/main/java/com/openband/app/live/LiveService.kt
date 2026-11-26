package com.openband.app.live

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
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openband.app.MainActivity
import com.openband.app.R
import com.openband.app.ble.BandManager
import com.openband.app.data.Calories
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Stats
import com.openband.app.health.HealthRepo
import com.openband.app.ui.RaceSettings
import com.openband.app.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/**
 * The always-on "today" notification (like the Pebble app's step counter) and the background refresh behind it.
 *
 * It never talks to the band. Every minute and a half it reads what the Pebble app (or any other app) has written to
 * Health Connect, updates the database, redraws the widgets and refreshes the notification. Optional extras on top:
 * a goal celebration, an evening recap and a "time to move" nudge.
 */
class LiveService : Service() {

    companion object {
        const val CHANNEL = "live"
        const val ALERTS = "alerts"
        private const val NOTIF_ID = 2
        private const val REFRESH_MS = 90_000L
        private const val TAG = "LiveService"

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Today (always on)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Steps, heart rate and calories, always up to date"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Goals and reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Goal reached, evening recap and move reminders"
            })
        }

        /** Starts or stops the service to match the setting. Safe to call from anywhere; failures are swallowed. */
        fun sync(ctx: Context) {
            if (RaceSettings.prefs.value.liveNotif) start(ctx) else stop(ctx)
        }

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveService::class.java)) }
                .onFailure { Log.w(TAG, "start not allowed right now", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LiveService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "error", e) })
    private val prefs by lazy { getSharedPreferences("live", Context.MODE_PRIVATE) }
    private var lastText = ""
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(title: String, text: String, steps: Int, goal: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_band)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(goal, steps.coerceIn(0, goal), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        val first = buildNow()
        try {
            val healthOk = Build.VERSION.SDK_INT >= 34 &&
                runCatching { packageManager.checkPermission("android.permission.health.READ_STEPS", packageName) == 0 }.getOrDefault(false)
            val type = when {
                Build.VERSION.SDK_INT >= 34 && healthOk -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                else -> 0
            }
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, first, type)
            } catch (e: SecurityException) {
                // health type needs a granted health permission; fall back to the always-allowed special-use type
                ServiceCompat.startForeground(this, NOTIF_ID, first, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            }
            started = true
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf(); return
        }

        // 1) refresh from Health Connect, redraw widgets, update the notification
        scope.launch {
            val repo = HealthRepo(this@LiveService)
            var backoff = 0
            var bandAt = 0L
            while (true) {
                if (RaceSettings.prefs.value.liveNotif.not()) { stopSelf(); return@launch }
                try {
                    withContext(Dispatchers.IO) { repo.import(quick = true) }
                    backoff = 0
                } catch (e: Throwable) {
                    Log.w(TAG, "refresh failed", e); backoff = (backoff + 1).coerceAtMost(5)
                }
                // opt-in: a short, read-only visit to the band (only joins the Pebble app's own link)
                val p = RaceSettings.prefs.value
                if (p.bgBand && System.currentTimeMillis() - bandAt > 10 * 60_000L) {
                    bandAt = System.currentTimeMillis()
                    runCatching { BandManager.backgroundSync() }
                }
                runCatching { withContext(Dispatchers.IO) { logSources() } }
                runCatching { withContext(Dispatchers.IO) { WidgetUpdater.updateAll(this@LiveService) } }
                update()
                delay(REFRESH_MS * (1 + backoff))
            }
        }
        // 2) any new data (band, import, workout) refreshes straight away
        scope.launch {
            Db.changes.debounce(2500).collect { update() }
        }
        // 3) keep the clock-dependent figures (resting kcal grows all day) ticking
        scope.launch {
            while (true) { delay(5 * 60_000L); update() }
        }
    }

    /** Today's totals per source, to the system log - makes it easy to check OpenBand against the Pebble app. */
    private fun logSources() {
        val db = Db.get(this)
        val (from, to) = Stats.todayRange()
        for (t in listOf(M.STEPS, M.CALORIES, M.TOTAL_CAL)) {
            val by = db.series(t, from, to).groupBy { it.source }.mapValues { (_, l) -> "${l.sumOf { it.value }.toInt()} (${l.size})" }
            Log.i(TAG, "$t today $by")
        }
    }

    private class Snap(val steps: Int, val kcal: Double, val distM: Double, val hr: Int?, val hrAt: Long)

    private fun snap(): Snap {
        val db = Db.get(this)
        val (from, to) = Stats.todayRange()
        val steps = Stats.total(db, M.STEPS, from, to)?.toInt() ?: 0
        val active = Stats.total(db, M.CALORIES, from, to)
        val dist = Stats.total(db, M.DISTANCE, from, to) ?: 0.0
        val shown = Calories.shown(active, LocalDate.now()) ?: 0.0
        val h = db.latest(M.HR)
        return Snap(steps, shown, dist, h?.value?.toInt(), h?.ts ?: 0L)
    }

    private fun buildNow(): Notification {
        val p = RaceSettings.prefs.value
        val s = runCatching { snap() }.getOrNull() ?: return build("OpenBand", "Starting…", 0, p.stepGoal)
        val parts = ArrayList<String>()
        if (s.hr != null && System.currentTimeMillis() - s.hrAt < 6 * 3_600_000L) {
            val age = (System.currentTimeMillis() - s.hrAt) / 60_000
            parts += if (age <= 30) "Heart ${s.hr} bpm" else "Heart ${s.hr} bpm (${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(s.hrAt))})"
        }
        parts += "%,d kcal".format(s.kcal.toLong())
        if (s.distM >= 50) {
            parts += if (p.imperial) "%.2f mi".format(s.distM / 1609.344) else "%.2f km".format(s.distM / 1000.0)
        }
        return build("%,d steps".format(s.steps), parts.joinToString(" · "), s.steps, p.stepGoal)
    }

    private fun update() {
        val n = buildNow()
        val key = n.extras.getString(Notification.EXTRA_TITLE) + "|" + n.extras.getString(Notification.EXTRA_TEXT)
        if (key != lastText) {
            lastText = key
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
        }
        runCatching { alerts() }
    }

    // ---------------------------------------------------------------- the fun part

    private fun alerts() {
        val p = RaceSettings.prefs.value
        val now = LocalDateTime.now()
        val today = LocalDate.now().toString()
        val db = Db.get(this)
        val (from, to) = Stats.todayRange()
        val steps = Stats.total(db, M.STEPS, from, to)?.toInt() ?: 0

        if (p.celebrate && steps >= p.stepGoal && prefs.getString("celebrated", "") != today) {
            prefs.edit().putString("celebrated", today).apply()
            val streak = streak(db, p.stepGoal)
            post(
                10, "Goal reached: %,d steps".format(steps),
                if (streak >= 2) "That is $streak days in a row. Keep it going." else "You hit your daily goal. Nice work.",
            )
            buzz()
        }

        if (p.recap && now.hour >= 21 && prefs.getString("recap", "") != today) {
            prefs.edit().putString("recap", today).apply()
            val active = Stats.total(db, M.CALORIES, from, to)
            val sleepGoalLeft = Stats.sleepFor(db, LocalDate.now())?.total
            val pct = steps * 100 / p.stepGoal.coerceAtLeast(1)
            post(
                11, "Your day in numbers",
                "%,d steps (%d%% of goal) · %,d kcal".format(steps, pct, (Calories.shown(active, LocalDate.now()) ?: 0.0).toLong()) +
                    (sleepGoalLeft?.let { " · last night ${Stats.fmtMinutes(it)}" } ?: ""),
            )
        }

        if (p.moveNudge && now.hour in 9..20) {
            val last = prefs.getLong("nudge", 0)
            val sinceNudge = System.currentTimeMillis() - last
            val recent = db.series(M.STEPS, System.currentTimeMillis() - 3_600_000L, System.currentTimeMillis() + 1).sumOf { it.value }
            val haveData = db.latest(M.STEPS)?.let { System.currentTimeMillis() - it.endTs < 4 * 3_600_000L } == true
            if (haveData && recent < 30 && sinceNudge > 2 * 3_600_000L) {
                prefs.edit().putLong("nudge", System.currentTimeMillis()).apply()
                post(12, "Time to move", "Almost no steps in the last hour. A short walk will do.")
            }
        }
    }

    /** Consecutive finished days (ending yesterday, plus today if already reached) with at least [goal] steps. */
    private fun streak(db: Db, goal: Int): Int {
        var d = LocalDate.now()
        var n = 0
        while (n < 365) {
            val v = Stats.dayValue(db, M.STEPS, d) ?: 0.0
            if (v >= goal) n++ else if (d != LocalDate.now()) break
            d = d.minusDays(1)
        }
        return n
    }

    private fun post(id: Int, title: String, text: String) {
        val pi = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_stat_band).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun buzz() {
        runCatching {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60, 80, 160), -1))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
