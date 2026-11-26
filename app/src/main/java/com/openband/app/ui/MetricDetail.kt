package com.openband.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ble.BandManager
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Sample
import com.openband.app.data.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Opens the detail page of a metric (id = M.* or INST_SLEEP). Provided by MainActivity. */
val LocalOpenMetric = staticCompositionLocalOf<(String) -> Unit> { {} }

private val CUMULATIVE = setOf(M.STEPS, M.CALORIES, M.TOTAL_CAL, M.DISTANCE, M.EXERCISE)

enum class Span(val label: String, val days: Int) { DAY("Day", 1), WEEK("Week", 7), MONTH("Month", 30) }

private data class Detail(
    val points: List<Pair<Long, Double>>,
    val hourly: List<Double>?,
    val daily: List<Pair<LocalDate, Double?>>,
    val min: Double?, val avg: Double?, val max: Double?, val count: Int, val total: Double?,
    val latest: Sample?,
    val baseline: Double?,
    val recent: List<Sample>,
    val sleep: Stats.Sleep?,
    val zones: List<Int>?,
    val from: Long, val to: Long,
)

/** Daily value of any metric, including the derived ones (sleep, resting HR). */
private fun dailyValue(db: Db, id: String, d: LocalDate): Double? = when (id) {
    INST_SLEEP -> Stats.sleepFor(db, d)?.total
    M.RESTING_HR -> restingHr(db, Stats.sleepFor(db, d), d).first
    else -> Stats.dayValue(db, id, d)
}

private fun loadDetail(db: Db, id: String, span: Span, end: LocalDate, maxHr: Int): Detail {
    val from = Stats.dayStart(end.minusDays(span.days - 1L))
    val to = Stats.dayStart(end.plusDays(1))
    val rows = if (id == INST_SLEEP || id == M.RESTING_HR) emptyList() else db.series(id, from, to).let { r ->
        // one source only for cumulative metrics (the one with the biggest total), so nothing is double counted
        if (id in CUMULATIVE) r.groupBy { it.source }.values.maxByOrNull { l -> l.sumOf { it.value } } ?: emptyList() else r
    }
    val days = (span.days - 1 downTo 0).map { end.minusDays(it.toLong()) }
    val daily = if (span == Span.DAY) emptyList() else days.map { it to dailyValue(db, id, it) }
    val vals = if (span == Span.DAY && id != INST_SLEEP && id != M.RESTING_HR && id !in CUMULATIVE) rows.map { it.value } else daily.mapNotNull { it.second }
    val hourly = if (span == Span.DAY && id in CUMULATIVE) MutableList(24) { 0.0 }.also { h ->
        rows.forEach { val i = ((it.ts - from) / 3_600_000L).toInt(); if (i in 0..23) h[i] += it.value }
    } else null
    val total = if (id in CUMULATIVE) rows.sumOf { it.value }.takeIf { rows.isNotEmpty() } else null
    val sleep = if (id == INST_SLEEP) Stats.sleepFor(db, end) else null
    val dayValue = when {
        span != Span.DAY -> null
        id == INST_SLEEP -> sleep?.total
        id == M.RESTING_HR -> restingHr(db, Stats.sleepFor(db, end), end).first
        else -> null
    }
    val stats = if (span == Span.DAY && id in CUMULATIVE) hourly!!.filter { it > 0 } else if (dayValue != null) listOf(dayValue) else vals
    val baseline = (1..30).mapNotNull { dailyValue(db, id, end.minusDays(it.toLong())) }.takeIf { it.size >= 2 }?.average()
    val zones = if (id == M.HR && span == Span.DAY) {
        val (rest, _) = restingHr(db, Stats.sleepFor(db, end), end)
        strainAndZones(rows, rest ?: 65.0, maxHr).second
    } else null
    return Detail(
        points = rows.map { it.ts to it.value },
        hourly = hourly,
        daily = daily,
        min = stats.minOrNull(), avg = stats.takeIf { it.isNotEmpty() }?.average(), max = stats.maxOrNull(), count = if (span == Span.DAY) rows.size else daily.count { it.second != null },
        total = total,
        latest = if (id == INST_SLEEP || id == M.RESTING_HR) null else db.latest(id),
        baseline = baseline,
        recent = rows.takeLast(20).reversed(),
        sleep = sleep, zones = zones, from = from, to = to,
    )
}

/** Value + unit in the user's units. */
fun fmtMetric(id: String, v: Double?, p: RacePrefs): Pair<String, String> {
    if (v == null) return "--" to ""
    return when (id) {
        M.DISTANCE -> fmtDistance(v, p.imperial)
        M.TEMP -> fmtTemp(v, p.fahrenheit)
        INST_SLEEP -> Stats.fmtMinutes(v) to ""
        M.STEPS -> "%,d".format(v.toLong()) to "steps"
        M.CALORIES, M.TOTAL_CAL -> "${v.toInt()}" to "kcal"
        M.STRESS -> "${v.toInt()}" to "/100"
        M.HRV -> "${v.toInt()}" to "ms"
        M.HR, M.RESTING_HR -> "${v.toInt()}" to "bpm"
        M.SPO2, M.BATTERY -> "${v.toInt()}" to "%"
        else -> {
            val i = M.info[id]
            (if ((i?.decimals ?: 0) == 0) "${Math.round(v)}" else "%.${i!!.decimals}f".format(v)) to (i?.unit ?: "")
        }
    }
}

fun metricTitle(id: String) = when (id) {
    INST_SLEEP -> "Sleep"
    M.HRV -> "Heart rate variability"
    M.SPO2 -> "Blood oxygen (SpO2)"
    M.TEMP -> "Skin temperature"
    M.CALORIES -> "Active calories"
    M.TOTAL_CAL -> "Total calories"
    else -> M.label(id)
}

/** Short explanation + healthy range, shown under every chart. */
private fun about(id: String): Pair<String, String> = when (id) {
    M.HR -> "Measured by the band's optical sensor every few minutes, plus live readings when you tap Measure." to "Resting adults: 60–100 bpm. Lower at rest usually means better fitness."
    M.RESTING_HR -> "Your lowest heart rate while asleep. One of the best markers of recovery: a value well above your baseline often means stress, illness, alcohol or a hard workout." to "Typical: 50–75 bpm. Athletes can be in the 40s."
    M.HRV -> "Variation between heartbeats (RMSSD), measured automatically. Higher than your own baseline = well recovered; a drop means your body is under strain." to "Very personal: compare with your own 30-day baseline, not with others."
    M.SPO2 -> "Oxygen saturation of your blood, measured automatically during the day and night." to "Normal: 95–100%. Repeated values under 92% are worth checking with a doctor."
    M.TEMP -> "Wrist skin temperature. It is lower than core body temperature and changes with the room - watch for changes against your baseline." to "Look for jumps of +0.5 °C or more above your baseline (fever, illness, cycle)."
    M.STRESS -> "Estimated by the band from heart-rate variability. 1–29 relaxed, 30–59 normal, 60–79 medium, 80+ high." to "Lower is better. Short peaks are normal; long high periods mean you should rest."
    M.STEPS -> "Counted by the band's motion sensor, updated live while the app is open." to "A common goal is 7,000–10,000 steps a day."
    M.CALORIES -> "Energy burned by movement, measured by the band (the same figure the Pebble app shows). It leaves out your resting metabolism." to "Depends on body size and activity."
    M.TOTAL_CAL -> "Everything your body burned: resting energy (Mifflin-St Jeor from your age, height, weight and sex, spread over the day) plus the active energy from the band. This is how Whoop, Noise and Apple Health count calories. Set your sex in Profile for the best accuracy." to "Typical adults burn 1,600-2,600 kcal a day in total."
    M.DISTANCE -> "Estimated from your steps and stride length." to "~0.7–0.8 m per step for most adults."
    M.BATTERY -> "The band's own battery level, read on every sync." to "Charge below 20%."
    INST_SLEEP -> "Total time from falling asleep to waking up, including short awakenings, split into deep, light and REM sleep." to "Adults need 7–9 h. Deep ≈ 13–23%, REM ≈ 20–25%, light ≈ 50–60%."
    else -> "" to ""
}

@Composable
fun MetricDetailScreen(id: String, modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val ver by Db.changes.collectAsState()
    val band by BandManager.state.collectAsState()
    var span by remember { mutableStateOf(Span.DAY) }
    var back by remember { mutableIntStateOf(0) }
    val end = LocalDate.now().minusDays(back.toLong())
    var d by remember { mutableStateOf<Detail?>(null) }
    LaunchedEffect(id, span, back, ver) {
        d = withContext(Dispatchers.IO) { runCatching { loadDetail(db, id, span, end, p.maxHr) }.getOrNull() }
    }
    val accent = metricColor(id, look)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Text(
                if (look.upperTitles) metricTitle(id).uppercase() else metricTitle(id),
                fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = look.text, modifier = Modifier.weight(1f),
            )
        }

        // span tabs
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(50)).background(look.panel).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Span.entries.forEach { s ->
                val sel = s == span
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (sel) accent else Color.Transparent)
                        .clickable { span = s }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(s.label, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (sel) onColor(accent) else look.dim)
                }
            }
        }

        // date navigation
        val df = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
        val rangeText = when {
            span == Span.DAY && back == 0 -> if (id == INST_SLEEP) "Last night" else "Today"
            span == Span.DAY && back == 1 -> if (id == INST_SLEEP) "Night before yesterday" else "Yesterday"
            span == Span.DAY -> end.format(df)
            else -> end.minusDays(span.days - 1L).format(df) + " – " + end.format(df)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("◀", color = look.dim, modifier = Modifier.clip(CircleShape).clickable { back += span.days }.padding(10.dp))
            Text(rangeText, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, color = look.text)
            Text("▶", color = if (back > 0) look.dim else look.dim.copy(alpha = 0.25f),
                modifier = Modifier.clip(CircleShape).clickable(enabled = back > 0) { back = (back - span.days).coerceAtLeast(0) }.padding(10.dp))
        }

        val data = d ?: return@Column
        HeroValue(id, data, span, back, band.liveHr, accent)

        // chart
        SectionCard(null) {
            when {
                id == INST_SLEEP && span == Span.DAY -> {
                    val sl = data.sleep
                    if (sl == null) EmptyChart("No sleep recorded for this night")
                    else Hypnogram(sl, Modifier.fillMaxWidth())
                }
                span == Span.DAY && data.hourly != null -> {
                    LabeledBars(data.hourly.map { it.takeIf { v -> v > 0 } }, (0..23).map { if (it % 6 == 0) "%02d".format(it) else "" }, accent,
                        tipOf = { i -> fmtMetric(id, data.hourly[i], p).let { "${it.first} ${it.second}".trim() } to "%02d:00 – %02d:59".format(i, i) })
                }
                span == Span.DAY -> {
                    if (data.points.size < 2) EmptyChart(if (data.points.isEmpty()) "No readings for this day yet" else "Only one reading so far")
                    else {
                        val pts = data.points.map { it.first to displayValue(id, it.second, p) }
                        AxisLabels(pts.minOf { it.second }, pts.maxOf { it.second })
                        LineChart(pts, data.from, data.to, color = accent, height = 170.dp, unit = fmtMetric(id, 1.0, p).second, decimals = if (id == M.TEMP) 1 else if (id == M.DISTANCE) 2 else 0)
                        TimeAxis()
                    }
                }
                else -> {
                    val labels = data.daily.mapIndexed { i, (date, _) ->
                        if (span == Span.WEEK) date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())).take(2)
                        else if (i % 5 == 0 || i == data.daily.lastIndex) "${date.dayOfMonth}" else ""
                    }
                    if (data.daily.all { it.second == null }) EmptyChart("No data in this period")
                    else LabeledBars(data.daily.map { it.second?.let { v -> displayValue(id, v, p) } }, labels, accent,
                        goal = goalFor(id, p)?.let { displayValue(id, it, p) },
                        tipOf = { i ->
                            val (day, v) = data.daily[i]
                            (v?.let { x -> fmtMetric(id, x, p).let { "${it.first} ${it.second}".trim() } } ?: "no data") to day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
                        })
                }
            }
        }

        // numbers
        StatsGrid(id, data, span, p)

        // zones
        data.zones?.takeIf { z -> z.any { it > 0 } }?.let { ZonesCard(it, p.maxHr) }

        // sleep breakdown
        if (id == INST_SLEEP && span == Span.DAY) data.sleep?.let { SleepBreakdown(it) }

        // baseline comparison
        BaselineCard(id, data, span, p, accent)

        // about
        val (what, range) = about(id)
        if (what.isNotEmpty()) SectionCard(look.pick("Briefing", "About this metric", "INFO")) {
            Text(what, style = MaterialTheme.typography.bodyMedium, color = look.text)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(accent))
                Text("  $range", style = MaterialTheme.typography.bodySmall, color = look.dim)
            }
        }

        // recent readings
        if (span == Span.DAY && data.recent.isNotEmpty() && id !in CUMULATIVE) SectionCard(look.pick("Lap times · readings", "Readings", "RAW_FEED")) {
            val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
            data.recent.forEach { r ->
                val (v, u) = fmtMetric(id, r.value, p)
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tf.format(Date(r.ts)), style = MaterialTheme.typography.bodyMedium, color = look.dim, modifier = Modifier.width(64.dp))
                    Text(if (r.source == "band") "Band" else "Health Connect", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.weight(1f))
                    Text("$v $u", fontWeight = FontWeight.SemiBold, color = look.text)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun goalFor(id: String, p: RacePrefs): Double? = when (id) {
    M.STEPS -> p.stepGoal.toDouble()
    M.CALORIES -> p.calGoal.toDouble()
    INST_SLEEP -> p.sleepGoalMin.toDouble()
    else -> null
}

/** Stored value -> chart value in the user's units. */
private fun displayValue(id: String, v: Double, p: RacePrefs): Double = when (id) {
    M.DISTANCE -> if (p.imperial) v / 1609.344 else v / 1000
    M.TEMP -> if (p.fahrenheit) v * 9 / 5 + 32 else v
    INST_SLEEP -> v / 60
    else -> v
}

fun onColor(c: Color) = if (c.luminance() > 0.55f) Color.Black else Color.White

/** Colour used for a metric across the app, derived from the theme where it makes sense. */
fun metricColor(id: String, look: RaceLook): Color = if (look.style == UiStyle.MIDNIGHT && id == M.STEPS) look.primary else if (look.style == UiStyle.MONO) {
    if (id == M.HR || id == M.RESTING_HR || id == M.STEPS) look.primary else look.text
} else if (look.style.studio) Palette.metric(id, !look.light) else when (id) {
    M.HR, M.RESTING_HR -> Color(0xFFFF4D5E)
    M.SPO2 -> Color(0xFF4FA3FF)
    M.HRV -> Color(0xFF9D7BFF)
    M.TEMP -> Color(0xFFFF8A4C)
    M.STRESS -> Color(0xFFFFC233)
    INST_SLEEP -> look.secondary
    M.BATTERY -> Color(0xFF3DDC84)
    else -> look.primary
}

@Composable
private fun HeroValue(id: String, d: Detail, span: Span, back: Int, liveHr: Int?, accent: Color) {
    val look = LocalRace.current
    val p = look.prefs
    val (value, sub) = when {
        span != Span.DAY -> {
            val v = if (id in CUMULATIVE || id == INST_SLEEP) d.avg else d.avg
            v to "daily average · ${d.count} days with data"
        }
        id == INST_SLEEP -> d.sleep?.total to d.sleep?.let {
            val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
            "${tf.format(Date(it.start))} → ${tf.format(Date(it.end))} · asleep ${Stats.fmtMinutes(it.asleep)}"
        }
        id in CUMULATIVE -> d.total to (if (back == 0) "so far today" else "that day")
        id == M.HR && back == 0 && liveHr != null -> liveHr.toDouble() to "live from the band"
        id == M.RESTING_HR -> d.avg to "from your lowest heart rate while asleep"
        back == 0 && d.latest != null -> d.latest.value to "measured ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(d.latest.ts))} · ${ago(d.latest.ts)}"
        else -> d.avg to "average that day"
    }
    val (txt, unit) = fmtMetric(id, value, p)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(txt, fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 54.sp, color = look.text)
            if (unit.isNotEmpty()) Text(" $unit", fontSize = 18.sp, color = accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = look.dim, textAlign = TextAlign.Center)
        goalFor(id, p)?.let { g ->
            if (value != null && span == Span.DAY) {
                val f = (value / g).toFloat().coerceIn(0f, 1f)
                Canvas(Modifier.padding(top = 10.dp).width(220.dp).height(8.dp)) {
                    drawRoundRect(accent.copy(alpha = 0.18f), cornerRadius = CornerRadius(size.height))
                    drawRoundRect(accent, size = Size(size.width * f, size.height), cornerRadius = CornerRadius(size.height))
                }
                Text("${(value * 100 / g).toInt()}% of your goal (${fmtMetric(id, g, p).let { "${it.first} ${it.second}".trim() }})",
                    style = MaterialTheme.typography.labelMedium, color = look.dim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun StatsGrid(id: String, d: Detail, span: Span, p: RacePrefs) {
    val cells = ArrayList<Pair<String, String>>()
    fun f(v: Double?) = fmtMetric(id, v, p).let { (a, b) -> if (a == "--") a else "$a ${if (id == M.STEPS) "" else b}".trim() }
    if (id in CUMULATIVE && span == Span.DAY) {
        cells += "Total" to f(d.total)
        cells += "Best hour" to f(d.max)
        cells += "Active hours" to "${d.hourly?.count { it > 0 } ?: 0}"
    } else if (id == INST_SLEEP && span == Span.DAY) {
        val s = d.sleep
        cells += "In bed" to (s?.let { Stats.fmtMinutes(it.total) } ?: "--")
        cells += "Asleep" to (s?.let { Stats.fmtMinutes(it.asleep) } ?: "--")
        cells += "Efficiency" to (s?.let { "${it.efficiency.toInt()}%" } ?: "--")
    } else {
        cells += (if (span == Span.DAY) "Min" else "Lowest day") to f(d.min)
        cells += (if (span == Span.DAY) "Average" else "Average") to f(d.avg)
        cells += (if (span == Span.DAY) "Max" else "Best day") to f(d.max)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        cells.forEach { (k, v) -> StatTile(k, v, modifier = Modifier.weight(1f)) }
    }
    if (span == Span.DAY && id !in CUMULATIVE && id != INST_SLEEP && id != M.RESTING_HR) {
        Text("${d.count} readings", style = MaterialTheme.typography.labelSmall, color = LocalRace.current.dim, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun BaselineCard(id: String, d: Detail, span: Span, p: RacePrefs, accent: Color) {
    val look = LocalRace.current
    val base = d.baseline ?: return
    val now = when {
        span != Span.DAY -> d.avg
        id == INST_SLEEP -> d.sleep?.total
        id in CUMULATIVE -> d.total
        id == M.RESTING_HR -> d.avg
        else -> d.avg
    } ?: return
    val diff = (now - base) / base * 100
    val higherIsBetter = id in setOf(M.HRV, M.STEPS, M.CALORIES, M.DISTANCE, INST_SLEEP, M.SPO2)
    val lowerIsBetter = id in setOf(M.RESTING_HR, M.STRESS)
    val good = if (higherIsBetter) diff >= 0 else if (lowerIsBetter) diff <= 0 else kotlin.math.abs(diff) < 3
    val col = if (kotlin.math.abs(diff) < 2) look.dim else if (good) look.ok else look.warn
    SectionCard(look.pick("Vs your pace · 30 days", "Compared with your 30-day baseline", "BASELINE_30D")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (span == Span.DAY) "This day" else "This period", style = MaterialTheme.typography.labelMedium, color = look.dim)
                Text(fmtMetric(id, now, p).let { "${it.first} ${it.second}" }, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = look.text)
            }
            Column(Modifier.weight(1f)) {
                Text("30-day baseline", style = MaterialTheme.typography.labelMedium, color = look.dim)
                Text(fmtMetric(id, base, p).let { "${it.first} ${it.second}" }, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = look.text)
            }
            Text((if (diff >= 0) "▲ " else "▼ ") + "%.0f%%".format(kotlin.math.abs(diff)), color = col, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
        // where today sits between 70 % and 130 % of the baseline
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val y = size.height / 2
            drawLine(look.panelHi, Offset(0f, y), Offset(size.width, y), strokeWidth = 6f)
            drawLine(look.dim, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            val x = (((now / base) - 0.7) / 0.6).toFloat().coerceIn(0f, 1f) * size.width
            drawCircle(accent, 7.dp.toPx() / 1.2f, Offset(x, y))
        }
        Text(
            when {
                kotlin.math.abs(diff) < 2 -> "Right on your usual level."
                good -> "Better than your usual level."
                else -> "Off your usual level - keep an eye on it."
            },
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun ZonesCard(zones: List<Int>, maxHr: Int) {
    val look = LocalRace.current
    val cols = listOf(Color(0xFF8FA3B8), Color(0xFF4FA3FF), Color(0xFF3DDC84), Color(0xFFFFC233), Color(0xFFFF4D5E))
    val names = listOf("Warm-up", "Fat burn", "Aerobic", "Anaerobic", "Max")
    val total = zones.sum().coerceAtLeast(1)
    SectionCard(look.pick("Rev zones", "Heart-rate zones", "HR_ZONES")) {
        for (i in 4 downTo 0) {
            val lo = (maxHr * (0.5 + i * 0.1)).toInt()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Z${i + 1}", fontWeight = FontWeight.Bold, color = cols[i], modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text("${names[i]} · $lo+ bpm", style = MaterialTheme.typography.labelSmall, color = look.dim)
                    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                        drawRoundRect(cols[i].copy(alpha = 0.15f), cornerRadius = CornerRadius(size.height))
                        drawRoundRect(cols[i], size = Size(size.width * zones[i] / total, size.height), cornerRadius = CornerRadius(size.height))
                    }
                }
                Text(Stats.fmtMinutes(zones[i].toDouble()), color = look.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).width(64.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
fun SleepBreakdown(s: Stats.Sleep) {
    val look = LocalRace.current
    val rows = listOf(
        Triple("Awake", s.awake, sleepColor(M.SLEEP_AWAKE, look)) to "",
        Triple("REM", s.rem, sleepColor(M.SLEEP_REM, look)) to "20–25%",
        Triple("Light", s.light + s.other, sleepColor(M.SLEEP_LIGHT, look)) to "50–60%",
        Triple("Deep", s.deep, sleepColor(M.SLEEP_DEEP, look)) to "13–23%",
    )
    val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
    SectionCard(look.pick("Pit stop breakdown", "Sleep stages", "SLEEP_STAGES")) {
        rows.forEach { (t, typical) ->
            val (name, min, c) = t
            val pct = if (s.total > 0) min * 100 / s.total else 0.0
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(name, fontWeight = FontWeight.SemiBold, color = look.text)
                    if (typical.isNotEmpty()) Text("typical $typical", style = MaterialTheme.typography.labelSmall, color = look.dim)
                }
                Text("${pct.toInt()}%", color = look.dim, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                Text(Stats.fmtMinutes(min), fontWeight = FontWeight.Bold, color = look.text, modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(look.panelHi))
        KeyValue("Fell asleep", tf.format(Date(s.start)))
        KeyValue("Woke up", tf.format(Date(s.end)))
        KeyValue("Time in bed", Stats.fmtMinutes(s.total))
        KeyValue("Time asleep", Stats.fmtMinutes(s.asleep))
        KeyValue("Efficiency", "${s.efficiency.toInt()}%")
        KeyValue("Wake-ups", "${s.wakeUps}")
    }
}

fun sleepColor(type: String, look: RaceLook): Color = if (look.style == UiStyle.MONO) {
    when (type) {
        M.SLEEP_DEEP -> look.text
        M.SLEEP_LIGHT, M.SLEEP_ASLEEP -> androidx.compose.ui.graphics.lerp(look.text, look.panel, 0.45f)
        M.SLEEP_REM -> androidx.compose.ui.graphics.lerp(look.text, look.panel, 0.70f)
        else -> look.primary
    }
} else if (look.style.studio) {
    val base = Palette.metric(INST_SLEEP, !look.light)
    when (type) {
        M.SLEEP_DEEP -> androidx.compose.ui.graphics.lerp(base, Color.Black, if (look.light) 0.30f else 0.45f)
        M.SLEEP_LIGHT, M.SLEEP_ASLEEP -> base
        M.SLEEP_REM -> androidx.compose.ui.graphics.lerp(base, Color.White, 0.42f)
        else -> if (look.light) Color(0xFFD69A2D) else Color(0xFFE8B552)
    }
} else when (type) {
    M.SLEEP_DEEP -> mix(look.secondary, Color(0xFF2B1F8F), 0.35f)
    M.SLEEP_LIGHT, M.SLEEP_ASLEEP -> look.secondary
    M.SLEEP_REM -> Color(0xFF5CE1E6)
    else -> Color(0xFFFFB86B)
}

/** Stage-by-stage timeline of the night (awake on top, deep at the bottom). */
@Composable
fun Hypnogram(s: Stats.Sleep, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val look = LocalRace.current
    val levels = listOf(M.SLEEP_AWAKE, M.SLEEP_REM, M.SLEEP_LIGHT, M.SLEEP_DEEP)
    val names = listOf("Awake", "REM", "Light", "Deep")
    Row(modifier) {
        Column(Modifier.height(height).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceAround) {
            names.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = look.dim) }
        }
        Column(Modifier.weight(1f)) {
            val tm = androidx.compose.ui.text.rememberTextMeasurer()
            var sel by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(sel) { if (sel != null) { kotlinx.coroutines.delay(4000); sel = null } }
            Canvas(Modifier.fillMaxWidth().height(height).chartPointer { sel = it }) {
                val span = (s.end - s.start).coerceAtLeast(1).toFloat()
                val rowH = size.height / levels.size
                for (i in levels.indices) drawLine(look.panelHi, Offset(0f, rowH * (i + 0.5f)), Offset(size.width, rowH * (i + 0.5f)), 1f)
                var prev: Pair<Float, Float>? = null
                s.segments.forEach { seg ->
                    val lvl = levels.indexOf(if (seg.type == M.SLEEP_ASLEEP) M.SLEEP_LIGHT else seg.type).coerceAtLeast(0)
                    val x0 = (seg.start - s.start) / span * size.width
                    val x1 = ((seg.end - s.start) / span * size.width).coerceAtLeast(x0 + 2f)
                    val top = rowH * lvl + rowH * 0.18f
                    val c = sleepColor(seg.type, look)
                    prev?.let { (px, py) -> drawLine(c.copy(alpha = 0.5f), Offset(x0, py), Offset(x0, top + rowH * 0.32f), 2f) }
                    drawRoundRect(c, Offset(x0, top), Size(x1 - x0, rowH * 0.64f), CornerRadius(4f))
                    prev = x1 to top + rowH * 0.32f
                }
                sel?.let { px ->
                    val t = s.start + (px / size.width * span).toLong()
                    val seg = s.segments.firstOrNull { t >= it.start && t < it.end } ?: s.segments.minByOrNull { kotlin.math.abs((it.start + it.end) / 2 - t) }
                    if (seg != null) {
                        val lvl = levels.indexOf(if (seg.type == M.SLEEP_ASLEEP) M.SLEEP_LIGHT else seg.type).coerceAtLeast(0)
                        val tf2 = SimpleDateFormat("HH:mm", Locale.getDefault())
                        val x = ((seg.start + seg.end) / 2 - s.start) / span * size.width
                        drawLine(look.text.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, size.height), 2f)
                        drawTip(tm, look, names[lvl] + " · " + Stats.fmtMinutes((seg.end - seg.start) / 60000.0), tf2.format(Date(seg.start)) + " – " + tf2.format(Date(seg.end)), Offset(x, rowH * lvl + rowH * 0.3f), sleepColor(seg.type, look))
                    }
                }
            }
            val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(tf.format(Date(s.start)), style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.weight(1f))
                Text(tf.format(Date(s.end)), style = MaterialTheme.typography.labelSmall, color = look.dim)
            }
        }
    }
}

/** Bars with labels underneath; optional dashed goal line. Tap, drag or hover over a bar for its value. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun LabeledBars(
    values: List<Double?>, labels: List<String>, color: Color, goal: Double? = null, height: Dp = 170.dp,
    tipOf: ((Int) -> Pair<String, String>)? = null,
) {
    val look = LocalRace.current
    val max = (listOfNotNull(values.filterNotNull().maxOrNull(), goal).maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    var sel by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(sel) { if (sel != null) { kotlinx.coroutines.delay(4000); sel = null } }
    Column {
        Canvas(Modifier.fillMaxWidth().height(height).chartPointer { sel = it }) {
            val slot = size.width / values.size
            val bw = (slot * 0.62f).coerceAtLeast(2f)
            val picked = sel?.let { (it / slot).toInt().coerceIn(0, values.lastIndex) }
            values.forEachIndexed { i, v ->
                val h = if (v == null) 3f else ((v / max) * (size.height - 4)).toFloat().coerceAtLeast(3f)
                drawRoundRect(
                    if (v == null) look.panelHi else if (picked == i) look.text else if (i == values.lastIndex) color else color.copy(alpha = if (picked == null) 0.7f else 0.4f),
                    Offset(i * slot + (slot - bw) / 2, size.height - h), Size(bw, h), CornerRadius(bw / 3),
                )
            }
            goal?.let {
                val y = size.height - ((it / max) * (size.height - 4)).toFloat()
                drawLine(look.text.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            if (picked != null && tipOf != null) {
                val v = values[picked]
                val h = if (v == null) 3f else ((v / max) * (size.height - 4)).toFloat().coerceAtLeast(3f)
                val (a, b) = tipOf(picked)
                drawTip(tm, look, a, b, Offset(picked * slot + slot / 2, size.height - h), color)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = look.dim, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Visible) }
        }
    }
}

@Composable
private fun AxisLabels(min: Double, max: Double) {
    val look = LocalRace.current
    Row(Modifier.fillMaxWidth()) {
        Text("max ${"%.0f".format(max)}", style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.weight(1f))
        Text("min ${"%.0f".format(min)}", style = MaterialTheme.typography.labelSmall, color = look.dim)
    }
}

@Composable
private fun TimeAxis() {
    val look = LocalRace.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        listOf("00", "06", "12", "18", "24").forEachIndexed { i, t ->
            Text(t, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = look.dim,
                textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center })
        }
    }
}

@Composable
private fun EmptyChart(msg: String) {
    val look = LocalRace.current
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        Text(msg, color = look.dim, style = MaterialTheme.typography.bodyMedium)
    }
}
