package com.openband.app.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ble.BandManager
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Stats
import com.openband.app.data.Workout
import com.openband.app.workout.ActiveState
import com.openband.app.workout.Gps
import com.openband.app.workout.SportDef
import com.openband.app.workout.Sports
import com.openband.app.workout.WorkoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val ZONE_COLORS = listOf(Color(0xFF8FA3B8), Color(0xFF4FA3FF), Color(0xFF3DDC84), Color(0xFFFFC233), Color(0xFFFF4D5E))
private val ZONE_NAMES = listOf("Warm-up", "Fat burn", "Aerobic", "Anaerobic", "Max effort")

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

internal fun fmtPace(secPerKm: Double?, imperial: Boolean): String {
    if (secPerKm == null || secPerKm.isNaN() || secPerKm > 3600 || secPerKm < 60) return "--"
    val s = if (imperial) secPerKm * 1.609344 else secPerKm
    return "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())
}

private fun fmtSpeed(kmh: Double?, imperial: Boolean): String =
    if (kmh == null) "--" else "%.1f".format(if (imperial) kmh / 1.609344 else kmh)

@Composable
fun SportsScreen(modifier: Modifier) {
    val active by WorkoutManager.active.collectAsState()
    var screen by rememberSaveable { mutableStateOf("list") }
    var sportId by rememberSaveable { mutableStateOf("run") }
    val scope = rememberCoroutineScope()
    val open = LocalOpenMetric.current

    // a running workout always owns this tab
    if (active != null) {
        ActiveWorkoutScreen(active!!, modifier,
            onFinish = { save -> scope.launch { val w = WorkoutManager.finish(save); screen = "list"; if (w != null) open("page:workout:${w.id}") } })
        return
    }
    BackHandler(enabled = screen != "list") { screen = "list" }
    when (screen) {
        "prep" -> PrepScreen(Sports.byId(sportId), modifier, onCancel = { screen = "list" })
        else -> SportHome(modifier,
            onPick = { sportId = it; screen = "prep" },
            onOpen = { open("page:workout:${it.id}") })
    }
}

// ================================================================================================ home (start / history)

@Composable
private fun SportHome(modifier: Modifier, onPick: (String) -> Unit, onOpen: (Workout) -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val ver by Db.changes.collectAsState()
    var history by remember { mutableStateOf<List<Workout>>(emptyList()) }
    var streak by remember { mutableStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ver) {
        withContext(Dispatchers.IO) {
            history = runCatching { db.workouts(400) }.getOrDefault(emptyList())
            streak = runCatching { WorkoutManager.streak(p.stepGoal) }.getOrDefault(0)
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        ScreenTitle(look.names.sport, "Pick a sport · your band checks its sensors first")

        // streak + week
        val weekStart = System.currentTimeMillis() - 7 * 86_400_000L
        val week = history.filter { it.start >= weekStart }
        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetricIcon(AppIcons.Flame, Palette.metric(M.CALORIES, !look.light), 44.dp)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("$streak-day streak", fontWeight = FontWeight.Black, fontSize = 20.sp, color = look.text)
                    Text("a workout or ${"%,d".format(p.stepGoal)} steps counts as an active day", style = MaterialTheme.typography.labelSmall, color = look.dim)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat("This week", "${week.size}", "workouts", Modifier.weight(1f))
                MiniStat("Time", Stats.fmtMinutes(week.sumOf { it.movingMs } / 60000.0), "", Modifier.weight(1f))
                MiniStat("Distance", fmtDistance(week.sumOf { it.distanceM }, p.imperial).let { "${it.first} ${it.second}" }, "", Modifier.weight(1f))
            }
        }

        InsightLinks()

        // segmented: Start | History
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(50)).background(look.panel).padding(4.dp)) {
            listOf("Start", "History (${history.size})").forEachIndexed { i, label ->
                val sel = tab == i
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) look.primary else Color.Transparent).clickable { tab = i }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (sel) onColor(look.primary) else look.dim) }
            }
        }

        if (tab == 0) StartTab(history, onPick) else HistoryTab(history, onOpen)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StartTab(history: List<Workout>, onPick: (String) -> Unit) {
    val look = LocalRace.current
    val recent = history.map { it.sport }.distinct().take(6).map { Sports.byId(it) }
    val chips = (if (recent.isNotEmpty()) listOf("Recent") else emptyList()) + Sports.categories
    var chip by rememberSaveable { mutableStateOf(chips.first()) }
    if (chip !in chips) chip = chips.first()
    // category chips: one row, scrolls sideways - so the page never becomes a 35-sport wall
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { c ->
            val sel = c == chip
            Text(
                c, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (sel) onColor(look.primary) else look.text, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) look.primary else look.panel).clickable { chip = c }.padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
    val list = if (chip == "Recent") recent else Sports.all.filter { it.category == chip }
    Spacer(Modifier.height(6.dp))
    list.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { sp -> SportChip(sp, Modifier.weight(1f)) { onPick(sp.id) } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HistoryTab(history: List<Workout>, onOpen: (Workout) -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf("all") }
    var shown by rememberSaveable { mutableIntStateOf(15) }
    val sportsDone = history.map { it.sport }.distinct()
    if (history.isEmpty()) {
        SectionCard(null) {
            Text("No workouts yet. Pick a sport on the Start tab.", color = look.dim, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            RaceButton("Add a demo run to see how it looks", Modifier.fillMaxWidth(), primary = false) {
                scope.launch(Dispatchers.IO) { WorkoutManager.insertDemo() }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf("all") + sportsDone).forEach { id ->
            val sel = id == filter
            Text(
                if (id == "all") "All" else Sports.byId(id).name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                color = if (sel) onColor(look.primary) else look.text,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) look.primary else look.panel).clickable { filter = id; shown = 15 }.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
    val list = history.filter { filter == "all" || it.sport == filter }
    val byMonth = list.take(shown).groupBy { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it.start)) }
    byMonth.forEach { (month, items) ->
        val all = list.filter { SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(it.start)) == month }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(month, fontWeight = FontWeight.Bold, color = look.text, modifier = Modifier.weight(1f))
            Text(
                "${all.size} · ${Stats.fmtMinutes(all.sumOf { it.movingMs } / 60000.0)}" + (all.sumOf { it.distanceM }.takeIf { it > 100 }?.let { " · " + fmtDistance(it, p.imperial).let { d -> "${d.first} ${d.second}" } } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = look.dim,
            )
        }
        items.forEach { w -> WorkoutRow(w) { onOpen(w) } }
    }
    if (list.size > shown) RaceButton("Show more (${list.size - shown})", Modifier.fillMaxWidth().padding(top = 10.dp), primary = false) { shown += 15 }
}

@Composable
private fun MiniStat(label: String, value: String, sub: String, modifier: Modifier) {
    val look = LocalRace.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(look.panelHi).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = look.text, maxLines = 1, softWrap = false)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1)
    }
}

@Composable
private fun SportChip(sp: SportDef, modifier: Modifier, onClick: () -> Unit) {
    val look = LocalRace.current
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(look.panel).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SportIcon(sp, 44.dp)
        Text(sp.name, style = MaterialTheme.typography.labelMedium, color = look.text, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        Text(if (sp.gps == Gps.NEEDED) "HR · GPS · map" else "HR", style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1)
    }
}

@Composable
private fun WorkoutRow(w: Workout, onClick: () -> Unit) {
    val look = LocalRace.current
    val sp = Sports.byId(w.sport)
    val p = look.prefs
    val df = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).background(look.panel).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SportIcon(sp, 40.dp)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(sp.name + if (w.gps) " · GPS" else "", fontWeight = FontWeight.Bold, color = look.text)
            Text(df.format(Date(w.start)), style = MaterialTheme.typography.labelSmall, color = look.dim)
            if (w.imported) Text("From ${w.source}", style = MaterialTheme.typography.labelSmall, color = Palette.metric(M.STEPS, !look.light))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtDuration(w.movingMs), fontWeight = FontWeight.Bold, color = look.text)
            val extra = buildList {
                if (w.distanceM >= 50) add(fmtDistance(w.distanceM, p.imperial).let { "${it.first} ${it.second}" })
                if (w.avgHr > 0) add("♥ ${w.avgHr}")
                add("${w.kcal.toInt()} kcal")
            }.joinToString(" · ")
            Text(extra, style = MaterialTheme.typography.labelSmall, color = look.dim)
        }
    }
}

// ================================================================================================ sensor check

private enum class CS { OK, WAIT, FAIL, INFO }
private class Check(val title: String, val detail: String, val state: CS, val required: Boolean = true, val action: (@Composable () -> Unit)? = null)

@Composable
private fun PrepScreen(sp: SportDef, modifier: Modifier, onCancel: () -> Unit) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val st by BandManager.state.collectAsState()
    val gps by WorkoutManager.gps.collectAsState()
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        WorkoutManager.refreshGpsPermission(); WorkoutManager.startGps()
    }
    DisposableEffect(sp.id) {
        WorkoutManager.startPrep(sp)
        onDispose { WorkoutManager.stopPrep() }
    }
    // coming back from the phone's settings: re-check permission + GPS switch
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { WorkoutManager.refreshGpsPermission(); if (sp.gps == Gps.NEEDED) WorkoutManager.startGps() } }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val hrFresh = st.liveHr != null && now - BandManager.liveHrAt < 8_000 && st.liveHr!! in 30..230
    val btOn = remember(now / 3000) { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }
    fun openAppSettings() = ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    val checks = ArrayList<Check>()
    checks += Check("Phone Bluetooth", if (btOn) "On" else "Turn Bluetooth on", if (btOn) CS.OK else CS.FAIL)
    checks += Check(
        "Band connection",
        when {
            st.ready -> "Connected · ${st.deviceName ?: "band"}"
            st.waitingOfficial -> "Open the Pebble app once - OpenBand joins its connection"
            st.address == null -> "No band paired - open Settings → Band"
            else -> "Connecting…"
        },
        if (st.ready) CS.OK else CS.WAIT,
    ) { if (!st.ready && st.address != null) SmallButton("Retry") { BandManager.ensureConnected() } }
    checks += Check(
        "Band battery", st.battery?.let { "$it%" + if (it < 15) " - charge soon" else "" } ?: "Reading…",
        when { st.battery == null -> CS.WAIT; st.battery!! < 10 -> CS.FAIL; else -> CS.OK }, required = false,
    )
    checks += Check(
        "Heart-rate sensor (wrist)",
        when {
            hrFresh -> "Reading ${st.liveHr} bpm · band is on your wrist"
            st.ready -> "Measuring… keep the band snug and stay still (about 25 s)"
            else -> "Waiting for the band"
        },
        if (hrFresh) CS.OK else CS.WAIT,
    )
    checks += Check("Motion sensor (steps)", if (st.ready) "Band step counter active" else "Waiting for the band", if (st.ready) CS.OK else CS.WAIT, required = false)
    if (sp.gps == Gps.NEEDED) {
        checks += when {
            gps.coarseOnly -> Check("GPS (phone)", "Only approximate location is allowed. Tracking needs PRECISE location.", CS.FAIL) { SmallButton("Fix") { openAppSettings() } }
            !gps.hasPermission -> Check("GPS (phone)", "Location permission needed for distance, pace and the map", CS.FAIL) {
                SmallButton("Allow") { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
            }
            !gps.enabled -> Check("GPS (phone)", "Location is switched off in the phone", CS.FAIL) {
                SmallButton("Turn on") { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            gps.fix -> Check("GPS (phone)", "Locked · ±${gps.accuracy!!.toInt()} m · ${gps.usedSats} satellites in use", CS.OK)
            else -> Check(
                "GPS (phone)",
                "Searching… ${gps.satellites} satellites in view, ${gps.usedSats} locked" + (gps.accuracy?.let { " (±${it.toInt()} m)" } ?: "") +
                    ".\nGo outside with a clear view of the sky - indoors there is no GPS.",
                CS.WAIT,
            )
        }
    }
    if (sp.note.isNotEmpty()) checks += Check("Note", sp.note, CS.INFO, required = false)

    val hardOk = st.ready && hrFresh
    val gpsOk = sp.gps == Gps.NONE || gps.fix

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onCancel)
            SportIcon(sp, 38.dp)
            Text("  " + sp.name, fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = look.text)
        }
        Text("Sensors this sport uses - you can start right away", style = MaterialTheme.typography.bodyMedium, color = look.dim, modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp))

        // live map preview while GPS searches
        if (sp.gps == Gps.NEEDED && gps.lat != null && gps.lon != null) {
            OsmMap(emptyList(), height = 170.dp, follow = true, me = gps.lat!! to gps.lon!!, modifier = Modifier.padding(bottom = 6.dp))
        }

        SectionCard(null) {
            checks.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (sym, col) = when (c.state) {
                        CS.OK -> "✓" to look.ok
                        CS.WAIT -> "…" to look.warn
                        CS.FAIL -> "✕" to look.danger
                        CS.INFO -> "i" to look.dim
                    }
                    Box(Modifier.size(26.dp).clip(CircleShape).background(col.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Text(sym, color = col, fontWeight = FontWeight.Black, fontSize = 14.sp)
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(c.title + if (!c.required) "  · optional" else "", fontWeight = FontWeight.SemiBold, color = look.text, fontSize = 14.sp)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = look.dim)
                    }
                    c.action?.invoke()
                }
                if (i < checks.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(look.panelHi))
            }
        }

        // starting never waits for a sensor: heart rate and GPS keep connecting while the timer already runs
        RaceButton("Start ${sp.name.lowercase()}", Modifier.fillMaxWidth().padding(top = 8.dp)) {
            WorkoutManager.start(sp, withGps = sp.gps == Gps.NEEDED)
        }
        if (!(hardOk && gpsOk)) Text(
            buildString {
                if (!st.ready) append("Band not connected yet - heart rate will join as soon as it connects. ")
                else if (!hrFresh) append("Heart rate needs a few seconds after you start. ")
                if (sp.gps == Gps.NEEDED && !gps.fix) append("GPS is still searching - the route starts when it locks.")
            }.trim(),
            style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "During a workout OpenBand keeps GPS and the band's heart rate running even with the screen off (a notification shows it). " +
                "Outside workouts it only talks to the band while it is open.",
            style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    val look = LocalRace.current
    Text(
        text, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = onColor(look.primary),
        modifier = Modifier.clip(RoundedCornerShape(50)).background(look.primary).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// ================================================================================================ active workout

@Composable
private fun ActiveWorkoutScreen(a: ActiveState, modifier: Modifier, onFinish: (save: Boolean) -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val gps by WorkoutManager.gps.collectAsState()
    val st by BandManager.state.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val max = p.maxHr
    val zone = a.hr?.let { b -> val pct = b.toDouble() / max; when { pct >= 0.9 -> 4; pct >= 0.8 -> 3; pct >= 0.7 -> 2; pct >= 0.6 -> 1; pct >= 0.5 -> 0; else -> -1 } } ?: -1
    val hrCol = if (zone >= 0) ZONE_COLORS[zone] else look.text

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SportIcon(a.sport, 36.dp)
            Text("  " + a.sport.name, fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = look.text, modifier = Modifier.weight(1f))
            val live = st.ready && a.hr != null
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (a.paused) look.warn else if (live) look.ok else look.danger))
            Text(
                if (a.paused) "  PAUSED" else if (live) "  LIVE" else "  NO HEART RATE",
                style = MaterialTheme.typography.labelMedium, color = if (a.paused) look.warn else if (live) look.ok else look.danger,
            )
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TIME", style = MaterialTheme.typography.labelMedium, color = look.dim)
            Text(fmtDuration(a.elapsedMs), fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 60.sp, color = look.text, maxLines = 1, softWrap = false)
        }

        // live map with the track so far, and rounds counted as you go
        if (a.gpsUsed) {
            val laps = remember(a.route.size / 5) { com.openband.app.workout.Laps.detect(a.route) }
            OsmMap(a.route, follow = true, height = 260.dp, me = gps.lat?.let { la -> gps.lon?.let { lo -> la to lo } }, lapLine = laps?.line)
            if (laps != null) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                StatTile("Rounds", "${laps.count}", "laps", Modifier.weight(1f))
                StatTile("Last round", fmtDuration(laps.laps.last().seconds * 1000L), "${laps.laps.last().meters.toInt()} m", Modifier.weight(1f))
            }
            Text(
                if (gps.fix) "GPS ±${gps.accuracy!!.toInt()} m · ${gps.usedSats} satellites" else "GPS searching… ${gps.usedSats} satellites",
                style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 6.dp),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            val dist = fmtDistance(a.distanceM, p.imperial)
            StatTile("Distance", dist.first, dist.second + if (!a.gpsUsed && a.sport.gps == Gps.NEEDED) " · no GPS" else "", Modifier.weight(1f))
            if (a.sport.paceMode) StatTile("Pace", fmtPace(a.paceSecPerKm, p.imperial), if (p.imperial) "min/mi" else "min/km", Modifier.weight(1f))
            else StatTile("Speed", fmtSpeed(a.speedKmh, p.imperial), if (p.imperial) "mph" else "km/h", Modifier.weight(1f))
        }

        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HEART RATE", style = MaterialTheme.typography.labelMedium, color = look.dim)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(a.hr?.toString() ?: "--", fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 48.sp, color = hrCol)
                        Text(" bpm", color = look.dim, modifier = Modifier.padding(bottom = 9.dp))
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (zone >= 0) "ZONE ${zone + 1}" else "—", fontWeight = FontWeight.Black, fontSize = 18.sp, color = hrCol)
                    Text(if (zone >= 0) ZONE_NAMES[zone] else "below zone 1", style = MaterialTheme.typography.labelSmall, color = look.dim)
                    Text("avg ${a.avgHr ?: "--"} · max ${a.maxHr ?: "--"}", style = MaterialTheme.typography.labelSmall, color = look.dim)
                }
            }
            if (a.hrSeries.size > 3) {
                Spacer(Modifier.height(6.dp))
                val tail = a.hrSeries.takeLast(300)
                LineChart(tail.map { it.first.toLong() to it.second.toDouble() }, tail.first().first.toLong(), tail.last().first.toLong().coerceAtLeast(tail.first().first + 1L),
                    color = hrCol, height = 90.dp, grid = false, unit = "bpm", xLabel = { fmtDuration(it * 1000) })
            }
            ZoneBar(a.zones, Modifier.padding(top = 8.dp))
        }

        Row(Modifier.fillMaxWidth()) {
            StatTile("Calories", "${a.kcal.toInt()}", "kcal", Modifier.weight(1f))
            StatTile("Steps", if (a.sport.steps) "%,d".format(a.steps) else "--", if (a.sport.steps && a.elapsedMs > 60_000) "${(a.steps * 60_000L / a.elapsedMs)} /min" else "", Modifier.weight(1f))
            if (a.gpsUsed) StatTile("Climb", "${a.elevGain.toInt()}", "m", Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RaceButton(if (a.paused) "Resume" else "Pause", Modifier.weight(1f), primary = false) { if (a.paused) WorkoutManager.resume() else WorkoutManager.pause() }
            RaceButton("Finish", Modifier.weight(1f)) { confirm = true }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Finish workout?") },
        text = { Text("${fmtDuration(a.elapsedMs)} · ${fmtDistance(a.distanceM, p.imperial).let { "${it.first} ${it.second}" }} · ${a.kcal.toInt()} kcal") },
        confirmButton = { TextButton(onClick = { confirm = false; onFinish(true) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirm = false; onFinish(false) }) { Text("Discard") }
                TextButton(onClick = { confirm = false }) { Text("Keep going") }
            }
        },
    )
}

@Composable
private fun ZoneBar(zones: List<Int>, modifier: Modifier = Modifier) {
    val total = zones.sum()
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(Color(0x22FFFFFF), cornerRadius = CornerRadius(size.height))
        if (total <= 0) return@Canvas
        var x = 0f
        zones.forEachIndexed { i, s ->
            val w = size.width * s / total
            if (w > 0) drawRect(ZONE_COLORS[i], Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

// ================================================================================================ summary

/** Where the workout began and ended, and how the two relate. */
@Composable
private fun RouteCard(w: Workout) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val clock = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
    val a = w.route.first(); val b = w.route.last()
    val gap = com.openband.app.workout.Laps.straight(a, b)
    SectionCard("Start and finish") {
        listOf(Triple("Start", a, Color(0xFF1F9D58)), Triple("Finish", b, Color(0xFFD63B3B))).forEach { (name, pt, c) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                    runCatching {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:${pt.lat},${pt.lon}?q=${pt.lat},${pt.lon}($name)")))
                    }
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(c))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(name + " · " + clock.format(Date(w.start + pt.t * 1000L)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = look.text)
                    Text("%.5f, %.5f".format(Locale.US, pt.lat, pt.lon), style = MaterialTheme.typography.labelSmall, color = look.dim)
                }
                Text("Open", style = MaterialTheme.typography.labelMedium, color = look.primary)
            }
        }
        val d = fmtDistance(gap, p.imperial)
        Text(
            if (gap < 40) "Loop: you finished where you started (${gap.toInt()} m apart)."
            else "You finished ${if (gap < 1000) "${gap.toInt()} m" else "${d.first} ${d.second}"} in a straight line from the start.",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Rounds walked or run around the same loop. */
@Composable
private fun LapsCard(r: com.openband.app.workout.Laps.Result) {
    val look = LocalRace.current
    val p = look.prefs
    val fast = r.fastest
    val slowest = r.laps.maxOf { it.seconds / it.meters }
    SectionCard("Rounds") {
        Row(Modifier.fillMaxWidth()) {
            FigureColumn("Rounds", "${r.count}", "", Modifier.weight(1f))
            FigureColumn("One round", "${r.avgMeters.toInt()}", "m", Modifier.weight(1f))
            FigureColumn("Average time", fmtDuration(r.avgSeconds * 1000L), "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        r.laps.forEach { lap ->
            val per = lap.seconds / lap.meters
            val frac = (fast.seconds / fast.meters / per).toFloat().coerceIn(0.2f, 1f)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${lap.number}", Modifier.width(30.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = look.text)
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(look.panelHi)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(look.primary.copy(alpha = if (per <= fast.seconds / fast.meters + 1e-6) 1f else 0.5f)))
                }
                Text(fmtDuration(lap.seconds * 1000L), Modifier.width(60.dp), style = MaterialTheme.typography.bodyMedium, color = look.text, textAlign = TextAlign.End)
                Text("${lap.meters.toInt()} m", Modifier.width(62.dp), style = MaterialTheme.typography.bodySmall, color = look.dim, textAlign = TextAlign.End)
            }
        }
        val pace = fast.seconds / (fast.meters / (if (p.imperial) 1609.344 else 1000.0))
        Text(
            "Fastest: round ${fast.number} at ${"%d:%02d".format((pace / 60).toInt(), (pace % 60).toInt())} ${if (p.imperial) "/mi" else "/km"}. " +
                "Rounds are worked out from the GPS track; phone GPS can be a few metres off." + if (slowest <= 0) "" else "",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Everything else that can be worked out from the recording. */
@Composable
private fun MoreStats(w: Workout, sp: com.openband.app.workout.SportDef) {
    val look = LocalRace.current
    val p = look.prefs
    val clock = SimpleDateFormat("h:mm a", Locale.getDefault())
    val movingMin = w.movingMs / 60_000.0
    // fastest speed held for about 20 seconds, so a single GPS jump does not count
    val top = remember(w.id) {
        var best = 0.0
        var j = 0
        val r = w.route
        val acc = DoubleArray(r.size)
        for (i in 1 until r.size) acc[i] = acc[i - 1] + com.openband.app.workout.Laps.straight(r[i - 1], r[i])
        for (i in r.indices) {
            while (j < i && r[i].t - r[j].t > 25) j++
            val dt = r[i].t - r[j].t
            if (dt >= 15) best = maxOf(best, (acc[i] - acc[j]) / dt * 3.6)
        }
        best
    }
    val alts = w.route.map { it.alt }.filter { it != 0.0 }
    SectionCard("More detail") {
        KeyValue("Started", clock.format(Date(w.start)))
        KeyValue("Finished", clock.format(Date(w.end)))
        KeyValue("Total time", fmtDuration(w.end - w.start))
        KeyValue("Moving time", fmtDuration(w.movingMs))
        if (w.distanceM >= 50 && w.movingMs > 0) KeyValue("Average speed", fmtSpeed(w.distanceM / (w.movingMs / 1000.0) * 3.6, p.imperial) + if (p.imperial) " mph" else " km/h")
        if (top > 1) KeyValue("Best speed", fmtSpeed(top, p.imperial) + if (p.imperial) " mph" else " km/h")
        if (sp.steps && w.steps > 0 && movingMin > 0.5) {
            KeyValue("Steps", "%,d".format(w.steps))
            KeyValue("Cadence", "${(w.steps / movingMin).toInt()} steps/min")
            if (w.distanceM >= 50) KeyValue("Stride length", "%.2f m".format(w.distanceM / w.steps))
        }
        if (movingMin > 0.5) KeyValue("Energy rate", "%.1f kcal/min".format(w.kcal / movingMin))
        if (alts.size > 5) {
            KeyValue("Lowest / highest point", "${alts.min().toInt()} m / ${alts.max().toInt()} m")
            KeyValue("Total climb", "${WorkoutManager.elevationGain(w.route).toInt()} m")
        }
        if (w.route.size >= 2) KeyValue("GPS points", "${w.route.size}")
    }
}

@Composable
fun SummaryScreen(w: Workout, modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val sp = Sports.byId(w.sport)
    var confirmDelete by remember { mutableStateOf(false) }
    val df = SimpleDateFormat("EEEE, d MMM yyyy · HH:mm", Locale.getDefault())
    val max = p.maxHr
    val zones = IntArray(5)
    w.hr.forEachIndexed { i, (t, b) ->
        val next = if (i + 1 < w.hr.size) w.hr[i + 1].first else t + 1
        val secs = (next - t).coerceIn(0, 30)
        val pct = b.toDouble() / max
        val z = when { pct >= 0.9 -> 4; pct >= 0.8 -> 3; pct >= 0.7 -> 2; pct >= 0.6 -> 1; pct >= 0.5 -> 0; else -> -1 }
        if (z >= 0) zones[z] += secs
    }
    val moving = w.movingMs
    val pace = if (w.distanceM > 50) moving / 1000.0 / (w.distanceM / 1000.0) else null
    val speed = if (w.distanceM > 50 && moving > 0) w.distanceM / (moving / 1000.0) * 3.6 else null
    val gain = remember(w.id) { WorkoutManager.elevationGain(w.route) }
    val splits = remember(w.id, p.imperial) { WorkoutManager.splits(w, if (p.imperial) 1609.344 else 1000.0) }
    val laps = remember(w.id) { com.openband.app.workout.Laps.detect(w.route) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Column(Modifier.weight(1f)) {
                Text(sp.name, fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = look.text)
                Text(df.format(Date(w.start)), style = MaterialTheme.typography.labelSmall, color = look.dim)
            }
            androidx.compose.material3.Icon(AppIcons.Trash, "Delete", Modifier.size(44.dp).clip(CircleShape).clickable { confirmDelete = true }.padding(10.dp), tint = look.dim)
        }

        // the map first, like Strava
        if (w.route.size >= 2) {
            Spacer(Modifier.height(8.dp))
            OsmMap(w.route, height = 300.dp, lapLine = laps?.line)
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (w.distanceM >= 50) fmtDistance(w.distanceM, p.imperial).let { "${it.first} ${it.second}" } else fmtDuration(moving),
                fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 50.sp, color = look.text,
            )
            Text(if (w.distanceM >= 50) "in ${fmtDuration(moving)}" else "moving time", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = look.primary)
        }

        Row(Modifier.fillMaxWidth()) {
            if (sp.paceMode) StatTile("Pace", fmtPace(pace, p.imperial), if (p.imperial) "min/mi" else "min/km", Modifier.weight(1f))
            else StatTile("Speed", fmtSpeed(speed, p.imperial), if (p.imperial) "mph" else "km/h", Modifier.weight(1f))
            StatTile("Calories", "${w.kcal.toInt()}", "kcal", Modifier.weight(1f))
            if (w.gps) StatTile("Climb", "${gain.toInt()}", "m", Modifier.weight(1f)) else StatTile("Steps", if (sp.steps) "%,d".format(w.steps) else "--", "", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            StatTile("Avg HR", if (w.avgHr > 0) "${w.avgHr}" else "--", "bpm", Modifier.weight(1f))
            StatTile("Max HR", if (w.maxHr > 0) "${w.maxHr}" else "--", "bpm", Modifier.weight(1f))
            StatTile("Min HR", if (w.minHr > 0) "${w.minHr}" else "--", "bpm", Modifier.weight(1f))
        }
        if (w.pausedMs > 1000) Text("Paused for ${fmtDuration(w.pausedMs)} · elapsed ${fmtDuration(w.end - w.start)}", style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(start = 6.dp, top = 4.dp))

        WorkoutActions(w)
        SameRouteCard(w)
        if (w.route.size >= 2) RouteCard(w)
        if (laps != null) LapsCard(laps)
        MoreStats(w, sp)

        // splits
        if (splits.isNotEmpty()) SectionCard(look.pick("Lap times", "Splits", "SPLITS")) {
            val unit = if (p.imperial) "mi" else "km"
            val paces = splits.map { it.seconds / (it.meters / (if (p.imperial) 1609.344 else 1000.0)) }
            val fastest = paces.minOrNull() ?: 1.0
            val slowest = paces.maxOrNull() ?: 1.0
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(unit.uppercase(), Modifier.width(34.dp), style = MaterialTheme.typography.labelSmall, color = look.dim)
                Text("PACE", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = look.dim)
                Text("♥", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = look.dim, textAlign = TextAlign.End)
                Text("↕ m", Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = look.dim, textAlign = TextAlign.End)
            }
            splits.forEachIndexed { i, s ->
                val perUnit = paces[i]
                val frac = if (slowest - fastest < 1) 1f else (0.35f + 0.65f * ((slowest - perUnit) / (slowest - fastest)).toFloat())
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.meters < (if (p.imperial) 1609.344 else 1000.0) * 0.95) "%.2f".format(s.meters / (if (p.imperial) 1609.344 else 1000.0)) else "${s.index}", Modifier.width(34.dp), color = look.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Box(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(frac).height(22.dp).clip(RoundedCornerShape(6.dp)).background(look.primary.copy(alpha = if (perUnit == fastest) 1f else 0.55f)))
                        Text(
                            "%d:%02d".format((perUnit / 60).toInt(), (perUnit % 60).toInt()), Modifier.padding(start = 8.dp), color = onColor(look.primary),
                            fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1,
                        )
                    }
                    Text(if (s.avgHr > 0) "${s.avgHr}" else "--", Modifier.width(44.dp), color = look.text, fontSize = 13.sp, textAlign = TextAlign.End)
                    Text("%+d".format(s.elevDelta.toInt()), Modifier.width(48.dp), color = look.dim, fontSize = 13.sp, textAlign = TextAlign.End)
                }
            }
        }
        if (w.route.size > 10 && w.route.any { it.alt != 0.0 }) SectionCard(look.pick("Elevation", "Elevation", "ELEVATION")) {
            val pts = w.route.map { it.t.toLong() to it.alt }
            LineChart(pts, 0L, pts.last().first.coerceAtLeast(1L), color = Color(0xFF3DDC84), height = 100.dp, unit = "m", xLabel = { fmtDuration(it * 1000) })
        }

        if (w.hr.size > 2) SectionCard(look.pick("Heart rate trace", "Heart rate", "HR_TRACE")) {
            LineChart(w.hr.map { it.first.toLong() to it.second.toDouble() }, 0L, w.hr.last().first.toLong().coerceAtLeast(1L), color = Color(0xFFFF4D5E), height = 130.dp,
                unit = "bpm", xLabel = { fmtDuration(it * 1000) })
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text("0:00", style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.weight(1f))
                Text(fmtDuration(w.hr.last().first * 1000L), style = MaterialTheme.typography.labelSmall, color = look.dim)
            }
        }
        if (zones.sum() > 0) SectionCard(look.pick("Rev zones", "Time in zones", "HR_ZONES")) {
            val total = zones.sum().coerceAtLeast(1)
            for (i in 4 downTo 0) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Z${i + 1}", fontWeight = FontWeight.Bold, color = ZONE_COLORS[i], modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text("${ZONE_NAMES[i]} · ${(max * (0.5 + i * 0.1)).toInt()}+ bpm", style = MaterialTheme.typography.labelSmall, color = look.dim)
                    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                        drawRoundRect(ZONE_COLORS[i].copy(alpha = 0.15f), cornerRadius = CornerRadius(size.height))
                        drawRoundRect(ZONE_COLORS[i], size = Size(size.width * zones[i] / total, size.height), cornerRadius = CornerRadius(size.height))
                    }
                }
                Text(fmtDuration(zones[i] * 1000L), color = look.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).width(60.dp), textAlign = TextAlign.End)
            }
        }
        SectionCard(look.pick("Telemetry used", "Sensors used", "SENSORS")) {
            KeyValue("Heart rate", if (w.avgHr > 0) "Band · optical sensor ✓" else "no readings")
            KeyValue("Motion / steps", if (sp.steps) "Band · accelerometer ✓" else "not used")
            KeyValue("Location", if (w.gps) "Phone GPS ✓ · ${w.route.size} points" else if (sp.gps == Gps.NEEDED) "not used" else "not needed")
            KeyValue("Calories", if (w.imported) "From the ${w.source} app" else if (p.calTotal) "Heart-rate formula" else "Band (same basis as Pebble)")
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this workout?") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; db.deleteWorkout(w.id); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
