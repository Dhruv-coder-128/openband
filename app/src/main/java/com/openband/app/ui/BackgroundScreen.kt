package com.openband.app.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import com.openband.app.ble.BandManager
import com.openband.app.health.HealthRepo
import com.openband.app.live.LiveService
import java.text.DateFormat
import java.util.Date

/** Settings page for everything that keeps working while the app is closed. */
@Composable
fun BackgroundContent() {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    var notifOn by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    var battery by remember { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                notifOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                battery = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val last = remember { HealthRepo(ctx).lastImport }
    val official = remember { BandManager.officialInstalled() }

    SectionCard("Always-on notification") {
        Toggle("Show today's steps, heart rate and calories", p.liveNotif) { v ->
            RaceSettings.update { it.copy(liveNotif = v) }
            if (v) LiveService.start(ctx) else LiveService.stop(ctx)
        }
        Text(
            "Like the Pebble app's step counter: one quiet notification that stays up to date, and keeps your widgets fresh " +
                "even when OpenBand is closed. It reads what the Pebble app saves to Health Connect, so it never touches the band.",
            style = MaterialTheme.typography.bodySmall, color = look.dim,
        )
        if (last > 0) Text(
            "Last full import: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last)),
            style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(top = 6.dp),
        )
        if (!notifOn) {
            Spacer(Modifier.height(10.dp))
            Text("Notifications are switched off for OpenBand, so nothing will show.", style = MaterialTheme.typography.bodySmall, color = look.warn)
            Spacer(Modifier.height(6.dp))
            RaceButton("Allow notifications", Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (!battery) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Some phones stop background apps to save battery. Allowing OpenBand to run freely keeps the notification and widgets updating.",
                style = MaterialTheme.typography.bodySmall, color = look.dim,
            )
            Spacer(Modifier.height(6.dp))
            RaceButton("Allow background activity", Modifier.fillMaxWidth(), primary = false) {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    SectionCard("Little extras") {
        Toggle("Goal celebration", p.celebrate) { v -> RaceSettings.update { it.copy(celebrate = v) } }
        Text("A short buzz and a note when you reach your step goal, with your streak of goal days.", style = MaterialTheme.typography.bodySmall, color = look.dim)
        Spacer(Modifier.height(10.dp))
        Toggle("Evening recap", p.recap) { v -> RaceSettings.update { it.copy(recap = v) } }
        Text("At 9 pm: steps, calories and last night's sleep in one line.", style = MaterialTheme.typography.bodySmall, color = look.dim)
        Spacer(Modifier.height(10.dp))
        Toggle("Move reminder", p.moveNudge) { v -> RaceSettings.update { it.copy(moveNudge = v) } }
        Text("A nudge if you have hardly moved for an hour between 9 am and 9 pm.", style = MaterialTheme.typography.bodySmall, color = look.dim)
    }

    if (official) SectionCard("Extra readings in the background") {
        Toggle("Also visit the band in the background", p.bgBand) { v -> RaceSettings.update { it.copy(bgBand = v) } }
        Text(
            "Off by default. When on, OpenBand makes a short read-only visit every ~10 minutes, only while the Pebble app already has the band connected, " +
                "to collect SpO2, HRV, stress and temperature. It sends nothing to the band, but it is one more thing talking to it. " +
                "Leave it off if your Pebble app ever feels slower.",
            style = MaterialTheme.typography.bodySmall, color = look.dim,
        )
    }
    LaunchedEffect(Unit) { }
}
