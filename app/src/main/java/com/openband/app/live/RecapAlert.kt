package com.openband.app.live

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openband.app.MainActivity
import com.openband.app.R
import com.openband.app.data.Db
import com.openband.app.data.WeeklyRecap
import com.openband.app.health.HealthRepo
import com.openband.app.ui.RaceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** Sends the weekly recap on Sunday night. */
object RecapAlert {
    private const val NOTIF_ID = 20

    private fun sp(c: Context) = c.getSharedPreferences("live", Context.MODE_PRIVATE)

    /** Sends the recap if one is due and has not been sent. Safe to call often. */
    @Synchronized
    fun check(c: Context, now: LocalDateTime = LocalDateTime.now()) {
        val p = RaceSettings.prefs.value
        if (!p.weeklyRecap) return
        val sunday = WeeklyRecap.due(now) ?: return
        if (sp(c).getString("weekly", "") == sunday.toString()) return
        val r = WeeklyRecap.load(Db.get(c), sunday, p.stepGoal, p.sleepGoalMin) ?: return
        sp(c).edit().putString("weekly", sunday.toString()).apply()
        LiveService.createChannels(c)
        val open = PendingIntent.getActivity(
            c, NOTIF_ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_OPEN, "page:recap"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, LiveService.ALERTS)
            .setSmallIcon(R.drawable.ic_stat_band).setContentTitle("Weekly recap: ${r.headline.replaceFirstChar { it.lowercase() }}")
            .setContentText(r.short).setStyle(NotificationCompat.BigTextStyle().bigText(r.short + "\nTap to see your week."))
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    /** Asks Android to wake the app next Sunday evening, even if nothing else is running. */
    fun schedule(c: Context) {
        runCatching {
            val at = WeeklyRecap.nextAlarm(LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val pi = PendingIntent.getBroadcast(c, NOTIF_ID, Intent(c, RecapReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { Log.w("RecapAlert", "schedule", it) }
    }
}

class RecapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { HealthRepo(app).import(quick = true) }
                RecapAlert.check(app)
                RecapAlert.schedule(app)
            } catch (e: Throwable) {
                Log.w("RecapAlert", "receive", e)
            } finally {
                pending.finish()
            }
        }
    }
}
