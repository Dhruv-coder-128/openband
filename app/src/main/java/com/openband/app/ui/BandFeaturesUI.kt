package com.openband.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openband.app.ble.BandManager
import com.openband.app.ble.OfficialApp
import com.openband.app.detect.ActivityDetect
import com.openband.app.live.LiveService
import com.openband.app.notify.Buzz
import com.openband.app.notify.BuzzStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Changes every time the screen comes back, e.g. after the user visited a system settings page. */
@Composable
private fun rememberVisits(): Int {
    val owner = LocalLifecycleOwner.current
    var n by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) n++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return n
}

private fun openSettings(ctx: Context, action: String) {
    runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Note(text: String, warn: Boolean = false) {
    val look = LocalRace.current
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (warn) look.warn else look.dim, modifier = Modifier.padding(top = 4.dp))
}

/** Switching background reading on also needs the always-on service that does the visits. */
private fun setBackgroundRead(ctx: Context, on: Boolean) {
    RaceSettings.update { it.copy(bgBand = on, liveNotif = it.liveNotif || on) }
    if (on) { LiveService.stop(ctx); LiveService.start(ctx) }
}

/** Reading the band while the app is closed. */
@Composable
fun BackgroundBandCard() {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current.applicationContext
    val visits = rememberVisits()
    val band by BandManager.state.collectAsState()
    val official = remember { BandManager.officialInstalled() }
    val usage = remember(visits) { OfficialApp.usageGranted(ctx) }
    SectionCard("Read the band in the background") {
        if (official) Toggle("Keep reading when the app is closed", p.bgBand) { setBackgroundRead(ctx, it) }
        Note(
            "About every 3 minutes OpenBand collects new readings from the band, then the notification and widgets update. " +
                if (official) "It only joins the connection the Pebble app has made, and stays silent while the Pebble app is on screen." else "",
        )
        if (official && p.bgBand && !usage) {
            Note("Allow \"Usage access\" so OpenBand can tell when the Pebble app is on screen. Until then it will not read in the background.", warn = true)
            Spacer(Modifier.height(8.dp))
            RaceButton("Allow Usage access", Modifier.fillMaxWidth()) { openSettings(ctx, Settings.ACTION_USAGE_ACCESS_SETTINGS) }
        }
        if (band.lastSync > 0) Note("Last read from the band: ${ago(band.lastSync)}")
    }
}

/** How often the band measures heart rate by itself. */
@Composable
fun HeartIntervalCard() {
    val look = LocalRace.current
    val saved = look.prefs.hrInterval
    val band by BandManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var choice by remember(saved) { mutableIntStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    LaunchedEffect(band.ready) {
        if (band.ready && band.hrSetting == null) BandManager.readHrInterval()
        if (band.ready) BandManager.pollNow(true) // brings the band's latest heart-rate log
    }
    val ctx = LocalContext.current.applicationContext
    val ver by com.openband.app.data.Db.changes.collectAsState()
    var arrivals by remember { mutableStateOf<com.openband.app.data.HrArrivals.Result?>(null) }
    LaunchedEffect(ver) {
        arrivals = withContext(Dispatchers.IO) { runCatching { com.openband.app.data.HrArrivals.today(com.openband.app.data.Db.get(ctx)) }.getOrNull() }
    }
    SectionCard("Heart rate every") {
        // two even rows, so every choice is in view
        listOf(listOf(10, 15, 30), listOf(45, 60, 0)).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    FilterChip(selected = choice == m, onClick = { choice = m; message = null }, label = { Text(if (m == 0) "As is" else "$m min", maxLines = 1) }, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        RaceButton(if (busy) "Setting…" else "OK", Modifier.fillMaxWidth(), enabled = !busy) {
            val m = choice
            RaceSettings.update { it.copy(hrInterval = m) }
            if (m == 0) { message = true to "OpenBand will leave the band's setting as it is."; return@RaceButton }
            busy = true; message = null
            scope.launch {
                val r = BandManager.setHrInterval(m)
                busy = false
                message = when {
                    r == null -> false to "The band could not be reached now. It will be set at the next connection."
                    r.enabled && r.minutes == m -> true to "Done. The band now measures every $m minutes."
                    else -> false to "The band kept ${r.minutes} minutes. It may not support $m."
                }
            }
        }
        message?.let { Note(it.second, warn = !it.first) }
        band.hrSetting?.let { Note("On the band now: " + if (it.enabled) "every ${it.minutes} min" else "automatic measuring is off") }
        arrivals?.let { a ->
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            Note("Latest automatic readings: " + a.times.joinToString(" · ") { time.format(java.util.Date(it)) })
            a.gapMinutes?.let { gap ->
                val want = band.hrSetting?.minutes
                Note(if (want != null && gap == want) "✓ Arriving every $gap minutes." else "Last two were $gap minutes apart.", warn = false)
            }
        }
        Note("The band measures by itself, so these readings also reach the Pebble app. Shorter intervals use more band battery. If the Pebble app changes it back, OpenBand sets yours again on its next visit.")
    }
}

/** Noticing activities that were never started. */
@Composable
fun AutoDetectCard() {
    val look = LocalRace.current
    val ctx = LocalContext.current.applicationContext
    val visits = rememberVisits()
    var problem by remember { mutableStateOf<String?>(null) }
    val permitted = remember(visits, look.prefs.autoDetect) { ActivityDetect.permitted(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) ActivityDetect.sync(ctx) { problem = it } else problem = "Without the permission activities cannot be noticed."
    }
    SectionCard("Activity detection") {
        Toggle("Ask me about walks, runs and rides", look.prefs.autoDetect) { on ->
            RaceSettings.update { it.copy(autoDetect = on) }
            problem = null
            if (on && !ActivityDetect.permitted(ctx)) ask.launch(ActivityDetect.permission()) else ActivityDetect.sync(ctx) { problem = it }
        }
        Note("If you forget to start an activity, OpenBand asks afterwards whether to save it (walks from 12 minutes, runs and rides from 8). Your phone does the sensing, so keep it on you.")
        if (look.prefs.autoDetect && !permitted) {
            Spacer(Modifier.height(8.dp))
            RaceButton("Allow physical activity", Modifier.fillMaxWidth()) { ask.launch(ActivityDetect.permission()) }
        }
        problem?.let { Note(it, warn = true) }
    }
}

// ================================================================================================ vibration

@Composable
fun BuzzScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val visits = rememberVisits()
    val ver by BuzzStore.changes.collectAsState()
    val on = remember(ver) { BuzzStore.enabled(ctx) }
    val access = remember(visits) { BuzzStore.accessGranted(ctx) }
    val usage = remember(visits) { OfficialApp.usageGranted(ctx) }
    val official = remember { BandManager.officialInstalled() }
    var test by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var others by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(adding) { if (adding) others = withContext(Dispatchers.IO) { BuzzStore.launchable(ctx) } }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        PageHeader("Band vibration", "Feel which app is calling", onBack)
        SectionCard(null) {
            Toggle("Vibrate the band for notifications", on) { BuzzStore.setEnabled(ctx, it) }
            if (on && !access) {
                Note("Allow \"Notification access\" for OpenBand. It looks at which app notified you; messages are not stored or sent anywhere.", warn = true)
                Spacer(Modifier.height(8.dp))
                RaceButton("Allow notification access", Modifier.fillMaxWidth()) { openSettings(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            }
            if (on && official && !look.prefs.bgBand) {
                Note("To vibrate while OpenBand is closed, switch on background reading.", warn = true)
                Spacer(Modifier.height(8.dp))
                RaceButton("Switch on background reading", Modifier.fillMaxWidth(), primary = false) { setBackgroundRead(ctx, true) }
            }
            if (on && official && look.prefs.bgBand && !usage) {
                Note("Allow \"Usage access\" as well, so OpenBand stays quiet while the Pebble app is on screen.", warn = true)
                Spacer(Modifier.height(8.dp))
                RaceButton("Allow Usage access", Modifier.fillMaxWidth(), primary = false) { openSettings(ctx, Settings.ACTION_USAGE_ACCESS_SETTINGS) }
            }
            if (on && official) Note("If notifications are also switched on in the Pebble app, the band will vibrate twice. Use one of the two.")
        }
        SectionCard("Try each pattern on your wrist") {
            Buzz.Pattern.entries.filter { it != Buzz.Pattern.OFF }.forEach { pattern ->
                RaceButton("${pattern.label} · ${pattern.about}", Modifier.fillMaxWidth().padding(vertical = 3.dp), primary = false) {
                    test = "Sending ${pattern.label.lowercase()}…"
                    scope.launch {
                        val sent = BandManager.alert(Buzz.steps(pattern, "Test"))
                        if (pattern == Buzz.Pattern.CALL) { kotlinx.coroutines.delay(5000); BandManager.alert(Buzz.endCall) }
                        test = if (sent) "${pattern.label} sent to the band." else "Not sent: the band is not connected."
                    }
                }
            }
            test?.let { Note(it) }
        }
        if (on) SectionCard("Apps") {
            val listed = remember(ver) { BuzzStore.listed(ctx) }
            val known = remember(ver) { Buzz.KNOWN.mapNotNull { k -> k.packages.filter { BuzzStore.installed(ctx, it) }.takeIf { it.isNotEmpty() }?.let { k.title to it } } }
            val knownPackages = Buzz.KNOWN.flatMap { it.packages }.toSet()
            val rows = known + listed.filter { it !in knownPackages }.map { BuzzStore.label(ctx, it) to listOf(it) }
            rows.forEach { (title, packages) ->
                val current = BuzzStore.pattern(ctx, packages.first())
                AppDropdown(
                    Buzz.Pattern.entries.map { it.label }, Buzz.Pattern.entries.indexOf(current),
                    { i -> BuzzStore.set(ctx, packages, Buzz.Pattern.entries[i]) }, Modifier.padding(vertical = 5.dp), label = title,
                )
            }
            Spacer(Modifier.height(6.dp))
            RaceButton(if (adding) "Close the list" else "Add another app", Modifier.fillMaxWidth(), primary = false) { adding = !adding }
            if (adding) {
                val shown = (listed + knownPackages).toSet()
                others.filter { it.first !in shown }.forEach { (pkg, name) ->
                    LinkRow(AppIcons.Plus, look.primary, name, null, null) { BuzzStore.set(ctx, listOf(pkg), Buzz.Pattern.ONE); adding = false }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
