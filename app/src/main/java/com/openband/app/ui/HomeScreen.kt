package com.openband.app.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ble.BandManager
import com.openband.app.ble.BandState
import com.openband.app.data.M
import com.openband.app.data.Stats
import com.openband.app.health.HealthRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale

/** Shared state + actions handed to whichever dashboard layout is active. */
class DashCtx(
    val s: TodaySummary?,
    val band: BandState,
    val busy: Boolean,
    val sync: () -> Unit,
    val openBand: () -> Unit,
) {
    val battery: Int? get() = band.battery ?: s?.latest?.get(M.BATTERY)?.first?.toInt()
}

@Composable
fun HomeScreen(modifier: Modifier, onOpenBand: () -> Unit, onOpenHealth: () -> Unit) {
    val ctx = LocalContext.current
    val look = LocalRace.current
    val band by BandManager.state.collectAsState()
    val s = rememberToday()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val d = DashCtx(s, band, refreshing || band.syncing, sync = {
        if (!refreshing) {
            refreshing = true
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { HealthRepo(ctx).import() } }
                if (!band.connected) BandManager.ensureConnected()
                runCatching { BandManager.sync() }
                refreshing = false
            }
        }
    }, openBand = onOpenBand)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        DetectedCard()
        if (look.style == UiStyle.HACKER) DashHacker(d) else if (look.style == UiStyle.TERMINAL) DashTerminal(d)
        else if (look.style.studio) DashStudio(d) else when (look.family) {
            UiStyle.RACING -> DashRacing(d)
            UiStyle.CYBER -> DashCyber(d)
            UiStyle.RINGS -> DashRings(d)
            else -> DashClean(d)
        }
        // Studio looks place these inside their layout; Hacker and Terminal show them in their own manner
        if (!look.style.studio && !look.isConsole) { RecoveryCard(); BodyCards(); CoachCard() }
        if (look.isConsole) BodyCards()
        MeasureNowCard(d.band)
        Spacer(Modifier.height(24.dp))
    }
}

/** Metrics from the user's layout, minus sleep/hero ones, minus empty ones if wanted. Returns (shown, hiddenCount). */
private fun visible(d: DashCtx, p: RacePrefs, exclude: Set<String>): Pair<List<String>, Int> {
    val s = d.s ?: return emptyList<String>() to 0
    val ids = p.layout.filter { it !in exclude }
    if (!p.hideEmpty) return ids to 0
    val shown = ids.filter { hasData(it, s, d.band.liveHr, d.battery) }
    return shown to (ids.size - shown.size)
}

@Composable
private fun WaitingNote(hidden: Int, d: DashCtx) {
    val look = LocalRace.current
    if (hidden <= 0) return
    val msg = if (d.band.ready) (if (hidden == 1) "1 more metric will appear once the band sends it." else "$hidden more metrics will appear once the band sends them.")
    else "$hidden metrics (SpO2, HRV, stress…) come straight from the band — connect it in ${look.names.garage}."
    Text(
        look.pick(msg, msg, "> $hidden SENSORS OFFLINE // " + if (d.band.ready) "AWAITING DATA" else "LINK REQUIRED"),
        style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(vertical = 8.dp),
    )
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Good night"
}

// =============================================================================================== RACING

@Composable
private fun DashRacing(d: DashCtx) {
    val look = LocalRace.current
    val p = look.prefs
    val s = d.s
    // header
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).background(look.primary).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(p.carNumber.take(3), fontFamily = look.display, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 28.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text((p.displayName.ifEmpty { "Driver" }).uppercase(), fontFamily = look.display, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 19.sp, color = look.text, maxLines = 1)
            Text((d.band.deviceName ?: "No car in garage").uppercase(), style = MaterialTheme.typography.labelMedium, color = look.dim, maxLines = 1)
        }
        LinkStatus(d)
    }
    LiveLine(d, Modifier.padding(top = 6.dp))
    val open = LocalOpenMetric.current
    val hrNow = d.band.liveHr?.toDouble() ?: s?.hrPoints?.lastOrNull()?.second
    if (p.shiftLights) ShiftLights(hrNow?.let { ((it - 50) / (p.maxHr - 50)).toFloat().coerceIn(0f, 1f) }, Modifier.padding(vertical = 8.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RaceButton(if (d.busy) "BOX BOX…" else look.names.sync, Modifier.weight(1f), enabled = !d.busy, onClick = d.sync)
        if (d.band.address == null) RaceButton(look.names.pair, primary = false, onClick = d.openBand)
    }
    if (s == null) return

    // two hero gauges = first two non-sleep metrics in the layout
    val heroes = p.layout.filter { it != INST_SLEEP }.take(2)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        heroes.forEach { id ->
            val spec = specFor(id, s, p, d.band.liveHr, d.battery)
            Column(Modifier.weight(1f).clickable { open(id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Gauge(spec, Modifier.fillMaxWidth(), big = true)
                spec.sub?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = look.dim) }
            }
        }
    }

    val (rest, hidden) = visible(d, p, heroes.toSet() + INST_SLEEP)
    if (rest.isNotEmpty()) SectionCard("Telemetry") {
        rest.forEach { id ->
            val spec = specFor(id, s, p, d.band.liveHr, d.battery)
            MetricBar(spec, Modifier.clickable { open(id) }.padding(vertical = 5.dp))
        }
    }
    WaitingNote(hidden, d)
    if (INST_SLEEP in p.layout && (s.sleep != null || !p.hideEmpty)) SleepCard(s, p, "Pit stop · last night")
    if (s.hrPoints.size > 1) SectionCard("Heart rate trace", Modifier.clickable { open(M.HR) }) {
        s.hr?.let { Text("MIN ${it.min.toInt()} · AVG ${it.avg.toInt()} · MAX ${it.max.toInt()}", style = MaterialTheme.typography.labelMedium, color = look.dim) }
        Spacer(Modifier.height(6.dp))
        LineChart(s.hrPoints, s.from, s.to, unit = "bpm", height = 120.dp)
    }
}

@Composable
private fun LinkStatus(d: DashCtx) {
    val look = LocalRace.current
    val (dot, label) = when {
        d.band.ready -> look.ok to look.pick("ECU LINK", "Connected", "LINK:ON")
        d.band.connected -> look.warn to look.pick("LINKING", "Connecting", "LINK:SYNC")
        d.band.address != null -> look.danger to look.pick("NO SIGNAL", "Not connected", "LINK:LOST")
        else -> look.dim to look.pick("OFFLINE", "No band", "LINK:NONE")
    }
    Column(horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(" $label", style = MaterialTheme.typography.labelMedium, color = dot)
        }
        d.battery?.let { Text(look.pick("FUEL $it%", "Band $it%", "PWR:$it%"), style = MaterialTheme.typography.labelMedium, color = if (it < 20) look.danger else look.dim) }
    }
}

/** One compact row: label, value, thin progress bar. Used by Racing telemetry. */
@Composable
private fun MetricBar(spec: GaugeSpec, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    val danger = spec.fraction != null && spec.dangerFrom != null &&
        (if (spec.dangerLow) spec.fraction <= spec.dangerFrom else spec.fraction >= spec.dangerFrom)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(spec.label.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = look.dim)
            Text(spec.value, fontFamily = look.display, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, fontSize = 17.sp, color = if (danger) look.danger else look.text)
            Text(" " + spec.unit, style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(bottom = 2.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
            drawRect(Color(0xFF23262C), size = size)
            val f = spec.fraction ?: 0f
            drawRect(if (danger) look.danger else look.primary, size = Size(size.width * f, size.height))
        }
        spec.sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = look.dim.copy(alpha = 0.8f)) }
    }
}

@Composable
private fun SleepCard(s: TodaySummary, p: RacePrefs, title: String?) {
    val look = LocalRace.current
    val open = LocalOpenMetric.current
    SectionCard(title?.takeIf { it.isNotEmpty() }, Modifier.clickable { open(INST_SLEEP) }) {
        val sl = s.sleep
        if (sl == null) { Text("No sleep data yet", color = look.dim); return@SectionCard }
        val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Stats.fmtMinutes(sl.total), fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 28.sp, color = look.text,
                fontStyle = if (look.italicTitles) FontStyle.Italic else FontStyle.Normal)
            Text("  ${tf.format(Date(sl.start))} → ${tf.format(Date(sl.end))}", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(bottom = 5.dp))
        }
        Text("${(sl.total * 100 / p.sleepGoalMin).toInt()}% of your ${Stats.fmtMinutes(p.sleepGoalMin.toDouble())} goal · asleep ${Stats.fmtMinutes(sl.asleep)} · ${sl.efficiency.toInt()}% efficiency",
            style = MaterialTheme.typography.bodySmall, color = look.dim)
        Spacer(Modifier.height(10.dp))
        if (sl.segments.size > 1) Hypnogram(sl, Modifier.fillMaxWidth(), height = 96.dp) else StintBar(sl.deep, sl.light, sl.rem, sl.other, sl.awake)
        Spacer(Modifier.height(8.dp))
        val racing = look.family == UiStyle.RACING
        if (look.style.studio) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf(Triple("Deep", sl.deep, M.SLEEP_DEEP), Triple("Light", sl.light + sl.other, M.SLEEP_LIGHT), Triple("REM", sl.rem, M.SLEEP_REM), Triple("Awake", sl.awake, M.SLEEP_AWAKE)).forEach { (n, v, t) ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(sleepColor(t, look)))
                            Text(n, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall, color = look.dim)
                        }
                        Text(Stats.fmtMinutes(v), style = MaterialTheme.typography.bodyMedium, color = look.text, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            return@SectionCard
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sl.deep > 0) Legend(Color(0xFFEDEDED), if (racing) "H" else "", "Deep ${Stats.fmtMinutes(sl.deep)}")
            if (sl.light > 0) Legend(Color(0xFFFFD12E), if (racing) "M" else "", "Light ${Stats.fmtMinutes(sl.light)}")
            if (sl.rem > 0) Legend(Color(0xFFFF2D2D), if (racing) "S" else "", "REM ${Stats.fmtMinutes(sl.rem)}")
        }
        if (sl.other > 0 || sl.awake > 0) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sl.other > 0) Legend(Color(0xFF39B54A), "", "Asleep ${Stats.fmtMinutes(sl.other)}")
            if (sl.awake > 0) Legend(Color(0xFF5A5F68), "", "Awake ${Stats.fmtMinutes(sl.awake)}")
        }
    }
}

@Composable
private fun Legend(c: Color, letter: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (letter.isNotEmpty()) TyreDot(c, letter) else Box(Modifier.size(10.dp).clip(CircleShape).background(c))
        Text(" $text", style = MaterialTheme.typography.bodySmall, color = LocalRace.current.text)
    }
}

// =============================================================================================== CYBERPUNK

@Composable
private fun DashCyber(d: DashCtx) {
    val look = LocalRace.current
    val p = look.prefs
    val s = d.s
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OPERATOR", style = MaterialTheme.typography.labelSmall, color = look.dim)
                Box {
                    val n = (p.displayName.ifEmpty { "runner" }).uppercase()
                    Text(n, fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 26.sp, color = look.secondary.copy(alpha = 0.7f), modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                    Text(n, fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 26.sp, color = look.primary)
                }
            }
            LinkStatus(d)
        }
        Text("> DEVICE: ${(d.band.deviceName ?: "NONE").uppercase()}  ${SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(Date())}",
            style = MaterialTheme.typography.labelSmall, color = look.dim)
        LiveLine(d, Modifier.padding(top = 4.dp))
    }
    val open = LocalOpenMetric.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RaceButton(if (d.busy) "SYNCING..." else look.names.sync, Modifier.weight(1f), enabled = !d.busy, onClick = d.sync)
        if (d.band.address == null) RaceButton(look.names.pair, primary = false, onClick = d.openBand)
    }
    if (s == null) return

    // big heart-rate HUD
    val hr = specFor(M.HR, s, p, d.band.liveHr)
    SectionCard("bio.monitor", Modifier.clickable { open(M.HR) }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Box {
                Text(hr.value, fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 64.sp, color = look.secondary.copy(alpha = 0.55f), modifier = Modifier.padding(start = 3.dp, top = 3.dp))
                Text(hr.value, fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 64.sp, color = look.primary)
            }
            Column(Modifier.padding(start = 10.dp, bottom = 12.dp)) {
                Text("BPM", style = MaterialTheme.typography.labelLarge, color = look.secondary)
                hr.sub?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = look.dim) }
            }
        }
        if (s.hrPoints.size > 1) LineChart(s.hrPoints, s.from, s.to, unit = "bpm", color = look.secondary, height = 90.dp, grid = false)
    }

    val (rest, hidden) = visible(d, p, setOf(M.HR, INST_SLEEP))
    rest.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { id -> CyberTile(specFor(id, s, p, d.band.liveHr, d.battery), Modifier.weight(1f).clickable { open(id) }) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    WaitingNote(hidden, d)
    if (INST_SLEEP in p.layout && (s.sleep != null || !p.hideEmpty)) SleepCard(s, p, "sleep.cycle")

    // terminal log of latest readings
    val lines = s.latest.entries.filter { it.value != null && it.key != M.BP_DIA }.sortedByDescending { it.value!!.second }.take(6)
    if (lines.isNotEmpty()) SectionCard("sys.log") {
        val tf = SimpleDateFormat("HH:mm", Locale.US)
        lines.forEach { (t, v) ->
            Text("> [${tf.format(Date(v!!.second))}] ${M.label(t).uppercase().replace(' ', '_')} = ${M.format(t, v.first)}",
                style = MaterialTheme.typography.bodySmall, color = look.secondary)
        }
    }
}

@Composable
private fun CyberTile(spec: GaugeSpec, modifier: Modifier) {
    val look = LocalRace.current
    val shape = CutCornerShape(topEnd = 14.dp, bottomStart = 14.dp)
    val danger = spec.fraction != null && spec.dangerFrom != null &&
        (if (spec.dangerLow) spec.fraction <= spec.dangerFrom else spec.fraction >= spec.dangerFrom)
    Column(modifier.clip(shape).background(look.panel.copy(alpha = 0.9f)).border(1.dp, look.border, shape).padding(12.dp)) {
        Text(spec.label.uppercase().replace(' ', '_'), style = MaterialTheme.typography.labelSmall, color = look.dim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(spec.value, fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 24.sp, color = if (danger) look.danger else look.primary, maxLines = 1)
            Text(" " + spec.unit.uppercase(), style = MaterialTheme.typography.labelSmall, color = look.secondary, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp)) {
            val n = 12
            val g = size.width * 0.02f
            val w = (size.width - g * (n - 1)) / n
            val lit = ((spec.fraction ?: 0f) * n).toInt()
            for (i in 0 until n) drawRect(
                if (i < lit) (if (danger) look.danger else look.secondary) else look.secondary.copy(alpha = 0.12f),
                Offset(i * (w + g), 0f), Size(w, size.height),
            )
        }
        spec.sub?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1) }
    }
}

// =============================================================================================== ATHLETE RINGS (Whoop-like)

private val REC_GREEN = Color(0xFF16EC06)
private val REC_YELLOW = Color(0xFFFFDE00)
private val REC_RED = Color(0xFFFF0026)

private fun recoveryColor(r: Int?, dim: Color) = when {
    r == null -> dim
    r >= 67 -> REC_GREEN
    r >= 34 -> REC_YELLOW
    else -> REC_RED
}

@Composable
private fun DashRings(d: DashCtx) {
    val look = LocalRace.current
    val p = look.prefs
    val s = d.s
    val open = LocalOpenMetric.current
    val strainCol = look.primary
    val sleepCol = look.secondary

    // header: avatar · TODAY · battery
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(40.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TODAY", fontWeight = FontWeight.Black, fontSize = 17.sp, letterSpacing = 2.sp, color = look.text)
            Text(SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date()).uppercase(), style = MaterialTheme.typography.labelSmall, color = look.dim)
        }
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(look.panel).clickable(onClick = d.openBand).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (d.band.ready) look.ok else look.dim))
            Text(" " + (d.battery?.let { "$it%" } ?: "--"), style = MaterialTheme.typography.labelMedium, color = look.text)
        }
    }
    LiveLine(d, Modifier.padding(top = 6.dp).fillMaxWidth(), center = true)
    if (s == null) return

    // three rings - only real band numbers: sleep vs goal, steps vs goal, active calories vs goal
    val sl = s.sleep
    val steps = s.totals[M.STEPS]
    val kcal = s.totals[M.CALORIES]
    val kcalCol = Color(0xFFFF8A4C)
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        GlowRing("SLEEP", sl?.let { Stats.fmtMinutes(it.total).replace(" ", "") } ?: "--", "of ${p.sleepGoalMin / 60}h",
            sl?.let { (it.total / p.sleepGoalMin).toFloat() }, sleepCol, Modifier.weight(1f)) { open(INST_SLEEP) }
        GlowRing("STEPS", steps?.let { "%,d".format(it.toLong()) } ?: "--", "of ${"%,d".format(p.stepGoal)}",
            steps?.let { (it / p.stepGoal).toFloat() }, strainCol, Modifier.weight(1f)) { open(M.STEPS) }
        GlowRing("CALORIES", kcal?.let { "${it.toInt()}" } ?: "--", "of ${p.calGoal} kcal",
            kcal?.let { (it / p.calGoal).toFloat() }, kcalCol, Modifier.weight(1f)) { open(M.CALORIES) }
    }

    // latest readings straight from the band
    SectionCard(null) {
        Text("LATEST FROM YOUR BAND", style = MaterialTheme.typography.labelMedium, color = look.dim)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in listOf(M.HRV, M.SPO2, M.STRESS, M.TEMP)) {
                val l = s.latest[id]
                val (v, u) = fmtMetric(id, l?.first, p)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(look.panelHi).clickable { open(id) }.padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(metricColor(id, look)))
                        Text(when (id) { M.HRV -> "HRV"; M.SPO2 -> "SpO2"; M.STRESS -> "Stress"; else -> "Temp" },
                            style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(v, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = look.text, maxLines = 1, softWrap = false)
                        Text(when (id) { M.TEMP -> "°"; M.STRESS -> ""; else -> u }, fontSize = 10.sp, color = look.dim, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                    }
                    Text(if (l == null) "no data" else ago(l.second).removeSuffix(" ago"), style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1, softWrap = false)
                }
            }
        }
    }

    // key statistics (user's layout order)
    val (rest, hidden) = visible(d, p, setOf(INST_SLEEP))
    if (rest.isNotEmpty()) SectionCard("Key statistics") {
        Text("vs your 30-day average", style = MaterialTheme.typography.labelSmall, color = look.dim.copy(alpha = 0.8f))
        rest.forEachIndexed { i, id ->
            val spec = specFor(id, s, p, d.band.liveHr, d.battery)
            val now = when (id) {
                M.STEPS, M.CALORIES, M.DISTANCE, M.TOTAL_CAL -> s.totals[id]
                M.RESTING_HR -> s.restingHr
                M.HR -> s.hr?.avg
                M.BATTERY -> d.battery?.toDouble()
                else -> s.latest[id]?.first
            }
            KeyStatRow(spec, now, s.baseline[id], id, Modifier.clickable { open(id) })
            if (i < rest.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(look.panelHi))
        }
    }
    WaitingNote(hidden, d)

    // heart rate today
    SectionCard(null, Modifier.clickable { open(M.HR) }) {
        val hrCol = Color(0xFFFF4D5E)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HEART RATE", style = MaterialTheme.typography.labelMedium, color = look.dim)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(specFor(M.HR, s, p, d.band.liveHr).value, fontWeight = FontWeight.Black, fontSize = 34.sp, color = hrCol)
                    Text(" bpm", style = MaterialTheme.typography.labelMedium, color = look.dim, modifier = Modifier.padding(bottom = 7.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                s.hr?.let { Text("MIN ${it.min.toInt()} · AVG ${it.avg.toInt()} · MAX ${it.max.toInt()}", style = MaterialTheme.typography.labelMedium, color = look.text) }
                s.restingHr?.let { Text("Lowest while asleep ${it.toInt()} bpm", style = MaterialTheme.typography.labelSmall, color = look.dim) }
            }
        }
        ZoneStrip(s.zones, Modifier.padding(vertical = 8.dp))
        if (s.hrPoints.size > 1) LineChart(s.hrPoints, s.from, s.to, unit = "bpm", color = hrCol, height = 110.dp)
        else Text("Heart-rate trace appears after the band's first readings.", style = MaterialTheme.typography.bodySmall, color = look.dim)
    }

    // sleep
    if (INST_SLEEP in p.layout && sl != null) {
        SectionCard(null, Modifier.clickable { open(INST_SLEEP) }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("SLEEP", style = MaterialTheme.typography.labelMedium, color = look.dim)
                    Text(Stats.fmtMinutes(sl.total), fontWeight = FontWeight.Black, fontSize = 30.sp, color = sleepCol)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("NEEDED ${Stats.fmtMinutes(p.sleepGoalMin.toDouble())}", style = MaterialTheme.typography.labelMedium, color = look.text)
                    Text("${sl.efficiency.toInt()}% efficiency · ${sl.wakeUps} wake-ups", style = MaterialTheme.typography.labelSmall, color = look.dim)
                }
            }
            Spacer(Modifier.height(10.dp))
            Hypnogram(sl, Modifier.fillMaxWidth(), height = 110.dp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                StageChip("Deep", sl.deep, sleepColor(M.SLEEP_DEEP, look))
                StageChip("Light", sl.light + sl.other, sleepColor(M.SLEEP_LIGHT, look))
                StageChip("REM", sl.rem, sleepColor(M.SLEEP_REM, look))
                StageChip("Awake", sl.awake, sleepColor(M.SLEEP_AWAKE, look))
            }
        }
    }

    // activity
    SectionCard(null, Modifier.clickable { open(M.STEPS) }) {
        val steps = s.totals[M.STEPS]
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("ACTIVITY", style = MaterialTheme.typography.labelMedium, color = look.dim)
                Text(steps?.let { "%,d".format(it.toLong()) } ?: "--", fontWeight = FontWeight.Black, fontSize = 30.sp, color = look.text)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${s.totals[M.CALORIES]?.toInt() ?: "--"} KCAL · ${fmtDistance(s.totals[M.DISTANCE], p.imperial).let { "${it.first} ${it.second.uppercase()}" }}",
                    style = MaterialTheme.typography.labelMedium, color = look.text)
                Text("${steps?.let { (it * 100 / p.stepGoal).toInt() } ?: 0}% of ${"%,d".format(p.stepGoal)} steps", style = MaterialTheme.typography.labelSmall, color = look.dim)
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledBars(s.hourlySteps.map { it.takeIf { v -> v > 0 } }, (0..23).map { if (it % 6 == 0) "%02d".format(it) else "" }, strainCol, height = 80.dp,
            tipOf = { i -> "${"%,d".format(s.hourlySteps[i].toLong())} steps" to "%02d:00 – %02d:59".format(i, i) })
    }
}

/** Ring with a soft glow, value in the middle and the goal under it. All three rings share one size. */
@Composable
private fun GlowRing(label: String, center: String, sub: String, fractionTarget: Float?, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val look = LocalRace.current
    // the ring sweeps from empty to its value the first time it appears, then follows live changes smoothly
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val animated by androidx.compose.animation.core.animateFloatAsState(
        if (shown) (fractionTarget ?: 0f) else 0f,
        androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "ring",
    )
    val fraction: Float? = if (fractionTarget == null) null else animated
    val size = 102.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.minDimension * 0.085f
                val pad = w * 1.6f
                val tl = Offset(pad, pad)
                val sz = Size(this.size.width - pad * 2, this.size.height - pad * 2)
                drawArc(color.copy(alpha = 0.14f), 0f, 360f, false, tl, sz, style = Stroke(w))
                val f = fraction?.coerceIn(0f, 1f) ?: return@Canvas
                if (f <= 0f) return@Canvas
                val sweep = 360f * f.coerceAtLeast(0.01f)
                // soft glow under the arc
                drawIntoCanvas { c ->
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = w * 1.4f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        this.color = color.copy(alpha = 0.6f).toArgb()
                        maskFilter = android.graphics.BlurMaskFilter(w * 1.1f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    c.nativeCanvas.drawArc(tl.x, tl.y, tl.x + sz.width, tl.y + sz.height, -90f, sweep, false, paint)
                }
                drawArc(color, -90f, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(center, fontWeight = FontWeight.Black, color = look.text, maxLines = 1, softWrap = false,
                    fontSize = when { center.length <= 4 -> 22.sp; center.length <= 5 -> 17.sp; else -> 15.sp })
                Text(sub, fontSize = 10.sp, color = look.dim, maxLines = 1, softWrap = false)
            }
        }
        Text(label, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.2.sp, color = look.dim, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 6.dp))
        Text(fraction?.let { "${(it * 100).toInt()}%" } ?: "", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color)
    }
}

@Composable
private fun Contributor(label: String, value: String?, now: Double?, base: Double?, higherBetter: Boolean, modifier: Modifier) {
    val look = LocalRace.current
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(look.panelHi).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = look.dim)
        Text(value ?: "--", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = look.text, maxLines = 1)
        Trend(now, base, higherBetter)
    }
}

@Composable
private fun Trend(now: Double?, base: Double?, higherBetter: Boolean?) {
    val look = LocalRace.current
    if (now == null || base == null || base == 0.0) { Text("no baseline yet", style = MaterialTheme.typography.labelSmall, color = look.dim.copy(alpha = 0.7f)); return }
    val diff = (now - base) / base * 100
    val good = when (higherBetter) { true -> diff >= 0; false -> diff <= 0; null -> kotlin.math.abs(diff) < 3 }
    val col = if (kotlin.math.abs(diff) < 2) look.dim else if (good) look.ok else look.warn
    Text((if (diff >= 0) "▲ " else "▼ ") + "%.0f%%".format(kotlin.math.abs(diff)), style = MaterialTheme.typography.labelSmall, color = col)
}

@Composable
private fun KeyStatRow(spec: GaugeSpec, now: Double?, base: Double?, id: String, modifier: Modifier) {
    val look = LocalRace.current
    val p = look.prefs
    val higher = when (id) {
        M.HRV, M.STEPS, M.CALORIES, M.DISTANCE, M.SPO2 -> true
        M.RESTING_HR, M.STRESS -> false
        else -> null
    }
    Row(modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(metricColor(id, look)))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(spec.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = look.text)
            Text(spec.sub ?: "", style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(spec.value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = look.text)
                Text(" " + spec.unit.removePrefix("of ").let { if (id == M.STEPS) "" else it }, style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(bottom = 3.dp))
            }
            when {
                id == M.BATTERY -> {}
                // a day that is still running can't be compared with full days: show the usual daily total instead
                id in setOf(M.STEPS, M.CALORIES, M.DISTANCE, M.TOTAL_CAL) ->
                    base?.let { Text("usually " + fmtMetric(id, it, p).let { (a, u) -> if (id == M.STEPS) a else "$a $u" } + "/day", style = MaterialTheme.typography.labelSmall, color = look.dim) }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    base?.let { Text(fmtMetric(id, it, p).first + "  ", style = MaterialTheme.typography.labelSmall, color = look.dim) }
                    Trend(now, base, higher)
                }
            }
        }
        Text("  ›", color = look.dim, fontSize = 18.sp)
    }
}

@Composable
private fun ZoneStrip(zones: List<Int>, modifier: Modifier = Modifier) {
    val cols = listOf(Color(0xFF8FA3B8), Color(0xFF4FA3FF), Color(0xFF3DDC84), Color(0xFFFFC233), Color(0xFFFF4D5E))
    val total = zones.sum()
    if (total <= 0) return
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        var x = 0f
        zones.forEachIndexed { i, m ->
            val w = size.width * m / total
            if (w > 0) drawRect(cols[i], Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun StageChip(name: String, minutes: Double, c: Color) {
    val look = LocalRace.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Text(" $name", style = MaterialTheme.typography.labelSmall, color = look.dim)
        }
        Text(Stats.fmtMinutes(minutes), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = look.text)
    }
}

/** "● Live · updated 8s ago" - shows that the numbers follow the band in real time. */
@Composable
private fun LiveLine(d: DashCtx, modifier: Modifier = Modifier, center: Boolean = false) {
    val look = LocalRace.current
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(5_000) }
    }
    val b = d.band
    val (col, txt) = when {
        b.address == null -> look.dim to look.pick("No car paired", "No band paired", "NO_DEVICE")
        b.waitingOfficial -> look.warn to look.pick("Waiting for the Pebble app to connect", "Waiting for the Pebble app to connect the band", "AWAITING_PEBBLE_LINK")
        b.otherApp -> look.warn to look.pick("Official app on track · waiting", "Official app is using the band · paused", "SHARED_LINK // PAUSED")
        b.syncing -> look.warn to look.pick("Pit stop · syncing", "Syncing…", "SYNCING…")
        b.ready && b.lastLive > 0 -> look.ok to look.pick("LIVE", "Live", "LIVE") + " · " + "updated ${((now - b.lastLive) / 1000).coerceAtLeast(0)}s ago"
        b.ready -> look.ok to look.pick("LIVE", "Live", "LIVE")
        b.connected -> look.warn to look.pick("Linking…", "Connecting…", "LINKING…")
        else -> look.dim to look.pick("Band asleep", "Connecting when the band is near", "LINK:IDLE")
    }
    Row(modifier, horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(col))
        Text("  $txt", style = MaterialTheme.typography.labelSmall, color = col)
    }
}

/** Thick progress ring with the value in the middle. */
@Composable
fun BigRing(label: String, fraction: Float?, center: String, color: Color, size: Dp = 104.dp) {
    val look = LocalRace.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.minDimension * 0.10f
                val inset = w / 2
                val tl = Offset(inset, inset)
                val sz = Size(this.size.width - w, this.size.height - w)
                drawArc(color.copy(alpha = 0.16f), -90f, 360f, false, tl, sz, style = Stroke(w))
                if (fraction != null) drawArc(color, -90f, 360f * fraction.coerceIn(0.005f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            }
            Text(center, fontWeight = FontWeight.Bold, fontSize = if (center.length > 4) 17.sp else 22.sp, color = look.text, textAlign = TextAlign.Center)
        }
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = look.dim, modifier = Modifier.padding(top = 8.dp))
    }
}

// =============================================================================================== CLEAN

@Composable
private fun DashClean(d: DashCtx) {
    val look = LocalRace.current
    val p = look.prefs
    val s = d.s
    Column(Modifier.padding(top = 18.dp)) {
        Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), style = MaterialTheme.typography.bodyMedium, color = look.dim)
        Text(greeting() + if (p.displayName.isEmpty()) "" else ", ${p.displayName}", fontWeight = FontWeight.Bold, fontSize = 28.sp, color = look.text)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.band.ready) look.ok else look.dim))
            Text(
                "  " + (d.band.deviceName?.let { "$it · ${if (d.band.ready) "connected" else d.band.status.lowercase()}" } ?: "No band paired") +
                    (d.battery?.let { " · $it%" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = look.dim,
            )
        }
        LiveLine(d, Modifier.padding(top = 4.dp))
    }
    val open = LocalOpenMetric.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RaceButton(if (d.busy) "Syncing…" else look.names.sync, Modifier.weight(1f), enabled = !d.busy, onClick = d.sync)
        if (d.band.address == null) RaceButton(look.names.pair, primary = false, onClick = d.openBand)
    }
    if (s == null) return

    val (shown, hidden) = visible(d, p, setOf(INST_SLEEP))
    shown.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { id -> CleanTile(id, specFor(id, s, p, d.band.liveHr, d.battery), s.week[id], Modifier.weight(1f).clip(look.panelShape).clickable { open(id) }) }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    WaitingNote(hidden, d)
    if (INST_SLEEP in p.layout && s.sleep != null) SleepCard(s, p, "Last night's sleep")
    if (s.hrPoints.size > 1) SectionCard("Heart rate today", Modifier.clickable { open(M.HR) }) {
        s.hr?.let { Text("Avg ${it.avg.toInt()} · Min ${it.min.toInt()} · Max ${it.max.toInt()} bpm", style = MaterialTheme.typography.bodySmall, color = look.dim) }
        Spacer(Modifier.height(6.dp))
        LineChart(s.hrPoints, s.from, s.to, unit = "bpm", height = 120.dp)
    }
}

private val TILE_COLORS = mapOf(
    M.HR to Color(0xFFE5484D), M.STEPS to Color(0xFF30A46C), M.CALORIES to Color(0xFFF76B15), M.DISTANCE to Color(0xFF0090FF),
    M.SPO2 to Color(0xFF3E63DD), M.HRV to Color(0xFF8E4EC6), M.TEMP to Color(0xFFE54D2E), M.STRESS to Color(0xFFFFB224),
    M.RESTING_HR to Color(0xFFD6409F), M.RESP to Color(0xFF12A594), M.BATTERY to Color(0xFF46A758),
)

@Composable
private fun CleanTile(id: String, spec: GaugeSpec, week: List<Double?>?, modifier: Modifier) {
    val look = LocalRace.current
    val accent = TILE_COLORS[id] ?: look.primary
    Column(modifier.clip(look.panelShape).background(look.panel).border(1.dp, look.border, look.panelShape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            Text("  " + spec.label, style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(spec.value, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = look.text, maxLines = 1)
            Text(" " + spec.unit, style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(bottom = 3.dp), maxLines = 1)
        }
        if (id == M.STEPS || id == M.CALORIES || id == M.DISTANCE) {
            Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
                drawRoundRect(accent.copy(alpha = 0.15f), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
                drawRoundRect(accent, size = Size(size.width * (spec.fraction ?: 0f), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
            }
        } else if (week != null && week.count { it != null } >= 2) {
            Sparkline(week, Modifier.padding(top = 2.dp), color = accent)
        }
        spec.sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/** Each button opens the full-screen measurement for that reading. */
@Composable
private fun MeasureNowCard(st: BandState, withHeader: Boolean = true) {
    val look = LocalRace.current
    val open = LocalOpenMetric.current
    SectionCard(if (look.style.studio) null else look.pick("Launch control · measure now", "Measure now", "MEASURE_NOW")) {
        Text(
            if (st.ready) "Take a live reading on the band. Keep it snug on your wrist and stay still."
            else "The band is not connected yet - the measurement will try to connect it.",
            style = MaterialTheme.typography.bodySmall, color = look.dim,
        )
        Spacer(Modifier.height(8.dp))
        listOf(listOf("Heart rate" to "measure-hr", "SpO2" to "measure-spo2"), listOf("HRV" to "measure-hrv", "Stress" to "measure-stress")).forEachIndexed { i, pair ->
            Row(Modifier.fillMaxWidth().padding(top = if (i == 0) 0.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (title, page) -> RaceButton(title, Modifier.weight(1f), primary = false, enabled = st.address != null) { open("page:$page") } }
            }
        }
    }
}


// =============================================================================================== OPENBAND (Today)

/** The big card at the top. In the Bold look it is filled with the accent colour and everything on it turns white. */
@Composable
private fun HeroSurface(level: Float, content: @Composable (onHero: Color?) -> Unit) {
    val look = LocalRace.current
    if (look.style != UiStyle.BOLD) { SectionCard(null) { content(null) }; return }
    val on = if (look.primary.luminance() > 0.5f) Color(0xFF101113) else Color.White
    val inner = look.copy(
        panel = look.primary, panelHi = on.copy(alpha = 0.24f), border = on.copy(alpha = 0.24f), text = on, dim = on.copy(alpha = 0.82f),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalRace provides inner) {
        if (look.prefs.boldLiquid) LiquidCard(look.primary, level) { content(on) }
        else Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(look.panelShape).background(look.primary).padding(horizontal = 20.dp, vertical = 18.dp)) { content(on) }
    }
}

@Composable
private fun DashStudio(d: DashCtx) {
    val look = LocalRace.current
    val p = look.prefs
    val s = d.s
    val open = LocalOpenMetric.current
    val dark = !look.light

    // ---- header: date, greeting, you
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()).uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp), color = look.dim)
            Text(greeting() + if (p.displayName.isEmpty()) "" else ", ${p.displayName}", style = MaterialTheme.typography.headlineMedium, color = look.text, modifier = Modifier.padding(top = 2.dp))
        }
        ProfileAvatar(44.dp)
    }

    // ---- one quiet status line (only loud when something needs attention)
    val b = d.band
    val (statusText, tone) = when {
        b.address == null -> "No band paired yet - tap to connect" to 1
        b.waitingOfficial -> "Waiting for the Pebble app to connect the band" to 1
        b.otherApp -> "The Pebble app is using the band" to 1
        b.syncing -> "Syncing your band…" to -1
        b.ready -> ("Band connected" + (d.battery?.let { " · $it% battery" } ?: "") + if (b.lastLive > 0) " · updated ${ago(b.lastLive)}" else "") to 0
        b.connected -> "Connecting to your band…" to 1
        else -> "Band not connected - tap to fix" to 1
    }
    Row(
        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).clickable(onClick = d.openBand).padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(toneColor(tone)))
        Text(statusText, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1)
    }
    if (s == null) return

    // ---- hero: today's movement
    val steps = s.totals[M.STEPS]
    val stepsCol = metricColor(M.STEPS, look)
    Spacer(Modifier.height(16.dp))
    HeroSurface(((steps ?: 0.0) / p.stepGoal).toFloat()) { stepsColOnHero ->
    val look = LocalRace.current
    val stepsCol = stepsColOnHero ?: stepsCol
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(AppIcons.Steps, null, Modifier.size(18.dp), tint = stepsCol)
                    Text("Steps today", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = look.dim)
                }
                Text(countUp(steps)?.let { "%,d".format(Math.round(it)) } ?: "--", style = MaterialTheme.typography.displayMedium, color = look.text, modifier = Modifier.padding(top = 6.dp))
                Text("Goal ${"%,d".format(p.stepGoal)}" + (steps?.let { " · ${(it * 100 / p.stepGoal).toInt()}%" } ?: ""), style = MaterialTheme.typography.bodySmall, color = look.dim)
            }
            StudioRing(steps?.let { (it / p.stepGoal).toFloat() }, stepsCol, 96.dp, 10.dp) {
                Text(steps?.let { "${(it * 100 / p.stepGoal).toInt().coerceAtMost(999)}%" } ?: "--", style = MaterialTheme.typography.titleLarge, color = look.text)
            }
        }
        if (look.style.prompt.isNotEmpty()) {
            val filled = ((steps ?: 0.0) / p.stepGoal * 22).toInt().coerceIn(0, 22)
            Text(
                "[" + "#".repeat(filled) + "·".repeat(22 - filled) + "] ${((steps ?: 0.0) * 100 / p.stepGoal).toInt()}%",
                style = MaterialTheme.typography.bodyMedium, color = look.primary, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))
        val dist = fmtDistance(s.totals[M.DISTANCE], p.imperial)
        Row(Modifier.fillMaxWidth()) {
            FigureColumn("Calories", (if (p.calTotal) s.totals[M.TOTAL_CAL] else s.totals[M.CALORIES])?.let { "%,d".format(it.toLong()) } ?: "--", "kcal", Modifier.weight(1f))
            FigureColumn("Distance", dist.first, dist.second, Modifier.weight(1f))
            FigureColumn("Active hours", "${s.hourlySteps.count { it >= 60 }}", "h", Modifier.weight(1f))
        }
        val note = if (p.calTotal) s.totals[M.CALORIES]?.let { "Includes resting energy · ${it.toInt()} kcal from activity" }
        else s.totals[M.TOTAL_CAL]?.let { "Same as the Pebble app · ${"%,d".format(it.toLong())} kcal with resting energy" }
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(top = 10.dp))
    }

    RecoveryCard()
    BodyCards()
    CoachCard()

    // ---- vitals grid
    SectionHeader("Vitals")
    val (ids, hidden) = visible(d, p, setOf(M.STEPS, M.CALORIES, M.DISTANCE, M.BATTERY, M.TOTAL_CAL))
    ids.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { id ->
                val spec = specFor(id, s, p, d.band.liveHr, d.battery)
                val num: Double? = when (id) {
                    M.HR -> d.band.liveHr?.toDouble() ?: s.hrPoints.lastOrNull()?.second ?: s.latest[M.HR]?.first
                    M.RESTING_HR -> s.restingHr
                    INST_SLEEP -> null
                    else -> s.latest[id]?.first
                }
                val status = if (id == INST_SLEEP) s.sleep?.let { sl -> "${(sl.total * 100 / p.sleepGoalMin).toInt()}% of goal" to (if (sl.total >= p.sleepGoalMin * 0.85) 0 else 1) }
                else Palette.status(id, num, s.baseline[id])
                MetricCard(
                    id, if (id == INST_SLEEP) "Sleep" else spec.label, spec.value, spec.unit.removePrefix("of ").let { if (id == M.STEPS) "" else it }, status,
                    s.latest[id]?.second?.let { ago(it) }, s.week[id], Modifier.weight(1f).fillMaxHeight(),
                ) { open(id) }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    WaitingNote(hidden, d)

    // ---- heart rate over the day
    if (s.hrPoints.size > 1) {
        SectionHeader("Heart rate today")
        SectionCard(null, Modifier.clickable { open(M.HR) }) {
            s.hr?.let {
                Row(Modifier.fillMaxWidth()) {
                    FigureColumn("Lowest", "${it.min.toInt()}", "bpm", Modifier.weight(1f))
                    FigureColumn("Average", "${it.avg.toInt()}", "bpm", Modifier.weight(1f))
                    FigureColumn("Highest", "${it.max.toInt()}", "bpm", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
            }
            LineChart(s.hrPoints, s.from, s.to, color = metricColor(M.HR, look), height = 120.dp, unit = "bpm")
        }
    }

    // ---- sleep
    if (INST_SLEEP in p.layout && s.sleep != null) {
        SectionHeader("Last night")
        SleepCard(s, p, null)
    }

    // ---- movement through the day
    if (s.hourlySteps.any { it > 0 }) {
        SectionHeader("Steps by hour")
        SectionCard(null, Modifier.clickable { open(M.STEPS) }) {
            LabeledBars(
                s.hourlySteps.map { it.takeIf { v -> v > 0 } }, (0..23).map { if (it % 6 == 0) "%02d".format(it) else "" }, stepsCol, height = 96.dp,
                tipOf = { i -> "${"%,d".format(s.hourlySteps[i].toLong())} steps" to "%02d:00 – %02d:59".format(i, i) },
            )
        }
    }
    SectionHeader("Measure now")
}
