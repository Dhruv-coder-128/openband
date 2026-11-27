package com.openband.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Everything the dashboards show, loaded once per data change. */
data class TodaySummary(
    val totals: Map<String, Double?>,
    val latest: Map<String, Pair<Double, Long>?>,
    val hr: Stats.MinMaxAvg?,
    val hrPoints: List<Pair<Long, Double>>,
    val sleep: Stats.Sleep?,
    val week: Map<String, List<Double?>>,
    val recovery: Int?,
    val from: Long,
    val to: Long,
    /** Resting heart rate: from the band/Health Connect if present, else estimated from last night's lowest HR. */
    val restingHr: Double? = null,
    val restingEstimated: Boolean = false,
    /** Day strain 0..21 from time spent above resting heart rate (own estimate, Whoop-like scale). */
    val strain: Double? = null,
    /** Minutes today in HR zones 1..5 (50-60-70-80-90 % of max HR). */
    val zones: List<Int> = List(5) { 0 },
    /** 30-day average of the daily value, per metric (today excluded). */
    val baseline: Map<String, Double?> = emptyMap(),
    val hourlySteps: List<Double> = List(24) { 0.0 },
    val loadedAt: Long = System.currentTimeMillis(),
)

private val SUM_TYPES = listOf(M.STEPS, M.CALORIES, M.TOTAL_CAL, M.DISTANCE, M.EXERCISE)
private val LATEST_TYPES = listOf(M.HR, M.RESTING_HR, M.SPO2, M.HRV, M.TEMP, M.STRESS, M.RESP, M.BP_SYS, M.BP_DIA, M.WEIGHT, M.VO2, M.BATTERY)

fun loadToday(db: Db, goalSleepMin: Int, maxHr: Int = 195): TodaySummary {
    val (from, to) = Stats.todayRange()
    val hrRows = db.series(M.HR, from, to)
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val week = HashMap<String, List<Double?>>()
    for (t in listOf(M.STEPS, M.CALORIES, M.DISTANCE, M.HR, M.RESTING_HR, M.SPO2, M.HRV, M.TEMP, M.STRESS, M.RESP, M.TOTAL_CAL, M.WEIGHT, M.BATTERY)) {
        week[t] = days.map { Stats.dayValue(db, t, it) }
    }
    week[INST_SLEEP] = days.map { Stats.sleepFor(db, it)?.total }
    val sleep = Stats.sleepFor(db, today)
    val (rhr, estimated) = restingHr(db, sleep)
    week[M.RESTING_HR] = days.map { restingHr(db, Stats.sleepFor(db, it), it).first }
    val baseline = HashMap<String, Double?>()
    for (t in listOf(M.HRV, M.SPO2, M.TEMP, M.STRESS, M.STEPS, M.CALORIES, M.DISTANCE, M.HR, M.RESTING_HR)) {
        baseline[t] = (1..30).mapNotNull { Stats.dayValue(db, t, today.minusDays(it.toLong())) }.takeIf { it.size >= 2 }?.average()
    }
    baseline[INST_SLEEP] = (1..30).mapNotNull { Stats.sleepFor(db, today.minusDays(it.toLong()))?.total }.takeIf { it.size >= 2 }?.average()
    if (baseline[M.RESTING_HR] == null) baseline[M.RESTING_HR] =
        (1..14).mapNotNull { d -> restingHr(db, Stats.sleepFor(db, today.minusDays(d.toLong())), today.minusDays(d.toLong())).first }.takeIf { it.size >= 2 }?.average()
    val hourly = MutableList(24) { 0.0 }
    // only short records say when the steps happened; a record covering many hours would pile them all on its first hour
    db.series(M.STEPS, from, to).filter { it.endTs - it.ts <= 2 * 3_600_000L }.groupBy { it.source }.values.maxByOrNull { l -> l.sumOf { it.value } }?.forEach {
        val h = ((it.ts - from) / 3_600_000L).toInt()
        if (h in 0..23) hourly[h] += it.value
    }
    val (strain, zones) = strainAndZones(hrRows, rhr ?: 65.0, maxHr)
    return TodaySummary(
        restingHr = rhr, restingEstimated = estimated, strain = strain, zones = zones, baseline = baseline, hourlySteps = hourly,
        totals = SUM_TYPES.associateWith { Stats.total(db, it, from, to) }.toMutableMap().also { m ->
            m[M.TOTAL_CAL] = Stats.dayValue(db, M.TOTAL_CAL, today)
        },
        latest = LATEST_TYPES.associateWith { t -> db.latest(t)?.let { it.value to it.ts } },
        hr = Stats.mma(hrRows),
        hrPoints = hrRows.map { it.ts to it.value },
        sleep = sleep,
        week = week,
        recovery = recoveryScore(db, goalSleepMin),
        from = from, to = to,
    )
}

/**
 * Estimated recovery 1..100 (own formula, not a medical value):
 * HRV vs 14-day baseline (higher = better), resting HR vs baseline (lower = better), last night's sleep vs goal.
 */
/**
 * Resting HR for the night that ended on [date]: a stored value if one exists, otherwise the average of the
 * three lowest heart-rate readings during that night's sleep (or the lowest readings of the day as a fallback).
 */
fun restingHr(db: Db, sleep: Stats.Sleep?, date: LocalDate = LocalDate.now()): Pair<Double?, Boolean> {
    val from = Stats.dayStart(date)
    db.series(M.RESTING_HR, from - 12 * 3_600_000L, from + 86_400_000L).lastOrNull()?.let { return it.value to false }
    val rows = if (sleep != null) db.series(M.HR, sleep.start, sleep.end) else emptyList()
    val pool = rows.ifEmpty { db.series(M.HR, from, from + 86_400_000L) }.map { it.value }.filter { it in 30.0..120.0 }.sorted()
    if (pool.size < 3) return null to true
    return pool.take(3).average() to true
}

/** Whoop-style 0..21 strain from heart-rate reserve (Banister TRIMP), plus minutes per HR zone. */
fun strainAndZones(hr: List<com.openband.app.data.Sample>, rest: Double, maxHr: Int): Pair<Double?, List<Int>> {
    val zones = IntArray(5)
    if (hr.size < 2) return null to zones.toList()
    var trimp = 0.0
    for (i in hr.indices) {
        val next = if (i + 1 < hr.size) hr[i + 1].ts else hr[i].ts + 5 * 60_000L
        val min = ((next - hr[i].ts) / 60_000.0).coerceIn(0.0, 30.0)
        val bpm = hr[i].value
        val r = ((bpm - rest) / (maxHr - rest)).coerceIn(0.0, 1.0)
        trimp += min * r * 0.64 * Math.exp(1.92 * r)
        val pct = bpm / maxHr
        val z = when { pct >= 0.9 -> 4; pct >= 0.8 -> 3; pct >= 0.7 -> 2; pct >= 0.6 -> 1; pct >= 0.5 -> 0; else -> -1 }
        if (z >= 0) zones[z] += min.toInt()
    }
    return (21 * (1 - Math.exp(-trimp / 250.0))).coerceIn(0.0, 21.0) to zones.toList()
}

private fun recoveryScore(db: Db, goalSleepMin: Int): Int? = com.openband.app.data.Recovery.compute(db, LocalDate.now(), goalSleepMin)?.score

@Composable
fun rememberToday(): TodaySummary? {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val ver by Db.changes.collectAsState()
    val goal = LocalRace.current.prefs.sleepGoalMin
    val maxHr = LocalRace.current.prefs.maxHr
    var sum by remember { mutableStateOf<TodaySummary?>(null) }
    LaunchedEffect(ver, goal, maxHr) {
        // coalesce bursts of updates (live polling) into one reload
        kotlinx.coroutines.delay(300)
        sum = withContext(Dispatchers.IO) { runCatching { loadToday(db, goal, maxHr) }.getOrNull() } ?: sum
    }
    return sum
}

// ------------------------------------------------------------------ units & formatting

fun fmtDistance(m: Double?, imperial: Boolean): Pair<String, String> {
    if (m == null) return "--" to if (imperial) "mi" else "km"
    return if (imperial) "%.2f".format(m / 1609.344) to "mi" else "%.2f".format(m / 1000) to "km"
}

fun fmtTemp(c: Double?, f: Boolean): Pair<String, String> {
    if (c == null) return "--" to if (f) "°F" else "°C"
    return if (f) "%.1f".format(c * 9 / 5 + 32) to "°F" else "%.1f".format(c) to "°C"
}

private fun frac(v: Double?, min: Double, max: Double): Float? = v?.let { ((it - min) / (max - min)).toFloat().coerceIn(0f, 1f) }

fun ago(ts: Long): String {
    val min = (System.currentTimeMillis() - ts) / 60_000
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m ago"
        min < 1440 -> "${min / 60}h ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }
}

/** True if this metric has any value to show. */
fun hasData(id: String, s: TodaySummary, liveHr: Int?, battery: Int?): Boolean = when (id) {
    M.HR -> liveHr != null || s.hrPoints.isNotEmpty() || s.latest[M.HR] != null
    M.BATTERY -> battery != null || s.latest[M.BATTERY] != null
    INST_SLEEP -> s.sleep != null
    M.RESTING_HR -> s.restingHr != null
    M.STEPS, M.CALORIES, M.TOTAL_CAL, M.DISTANCE -> s.totals[id] != null
    else -> s.latest[id] != null
}

/** Builds display data (value, unit, needle position, danger zone) for one metric. */
fun specFor(id: String, s: TodaySummary, p: RacePrefs, liveHr: Int?, battery: Int? = null): GaugeSpec {
    fun latest(t: String) = s.latest[t]
    return when (id) {
        M.HR -> {
            val v = liveHr?.toDouble() ?: s.hrPoints.lastOrNull()?.second ?: latest(M.HR)?.first
            GaugeSpec(
                "Heart rate", v?.let { "${it.toInt()}" } ?: "--", "bpm", frac(v, 40.0, p.maxHr.toDouble()),
                dangerFrom = ((p.maxHr * 0.9 - 40) / (p.maxHr - 40)).toFloat(),
                sub = s.hr?.let { "avg ${it.avg.toInt()} · max ${it.max.toInt()}" },
                ticks = (0..5).map { i -> "${40 + (p.maxHr - 40) * i / 5}" },
            )
        }
        M.STEPS -> {
            val v = s.totals[M.STEPS]
            GaugeSpec(
                "Steps", v?.let { "%,d".format(it.toLong()) } ?: "--", "of ${"%,d".format(p.stepGoal)}", frac(v, 0.0, p.stepGoal.toDouble()),
                sub = v?.let { "${(it * 100 / p.stepGoal).toInt()}% of goal" },
                ticks = (0..5).map { i -> "${p.stepGoal * i / 5 / 1000}k" },
            )
        }
        M.CALORIES -> {
            val v = s.totals[M.CALORIES]
            GaugeSpec("Active kcal", v?.let { "${it.toInt()}" } ?: "--", "kcal", frac(v, 0.0, p.calGoal.toDouble()), sub = "goal ${p.calGoal}")
        }
        M.TOTAL_CAL -> {
            val v = s.totals[M.TOTAL_CAL]
            GaugeSpec("Total kcal", v?.let { "${it.toInt()}" } ?: "--", "kcal", frac(v, 0.0, 3000.0))
        }
        M.DISTANCE -> {
            val v = s.totals[M.DISTANCE]
            val (txt, unit) = fmtDistance(v, p.imperial)
            GaugeSpec("Distance", txt, unit, frac(v, 0.0, p.stepGoal * 0.78))
        }
        M.SPO2 -> {
            val l = latest(M.SPO2)
            GaugeSpec("SpO2", l?.let { "${it.first.toInt()}" } ?: "--", "%", frac(l?.first, 80.0, 100.0), dangerFrom = 0.5f, dangerLow = true, sub = l?.let { ago(it.second) })
        }
        M.HRV -> {
            val l = latest(M.HRV)
            GaugeSpec("HRV", l?.let { "${it.first.toInt()}" } ?: "--", "ms", frac(l?.first, 0.0, 150.0), dangerFrom = 0.13f, dangerLow = true, sub = l?.let { ago(it.second) })
        }
        M.TEMP -> {
            val l = latest(M.TEMP)
            val (txt, unit) = fmtTemp(l?.first, p.fahrenheit)
            GaugeSpec("Temperature", txt, unit, frac(l?.first, 34.0, 40.0), dangerFrom = 0.58f, sub = l?.let { ago(it.second) })
        }
        M.STRESS -> {
            val l = latest(M.STRESS)
            GaugeSpec("Stress", l?.let { "${it.first.toInt()}" } ?: "--", "/100", frac(l?.first, 0.0, 100.0), dangerFrom = 0.8f, sub = l?.let { ago(it.second) })
        }
        M.RESTING_HR -> {
            val v = s.restingHr
            GaugeSpec("Resting HR", v?.let { "${it.toInt()}" } ?: "--", "bpm", frac(v, 40.0, 100.0), dangerFrom = 0.75f,
                sub = if (v == null) null else if (s.restingEstimated) "from last night" else latest(M.RESTING_HR)?.let { ago(it.second) })
        }
        M.RESP -> {
            val l = latest(M.RESP)
            GaugeSpec("Breathing", l?.let { "%.1f".format(it.first) } ?: "--", "/min", frac(l?.first, 8.0, 25.0), dangerFrom = 0.8f, sub = l?.let { ago(it.second) })
        }
        M.WEIGHT -> {
            val l = latest(M.WEIGHT)
            val kg = l?.first
            val txt = kg?.let { if (p.imperial) "%.1f".format(it * 2.20462) else "%.1f".format(it) } ?: "--"
            GaugeSpec("Weight", txt, if (p.imperial) "lb" else "kg", frac(kg, 40.0, 120.0))
        }
        M.BATTERY -> {
            val b = battery?.toDouble() ?: latest(M.BATTERY)?.first
            GaugeSpec("Band battery", b?.let { "${it.toInt()}" } ?: "--", "%", frac(b, 0.0, 100.0), dangerFrom = 0.2f, dangerLow = true)
        }
        INST_SLEEP -> {
            val sl = s.sleep
            GaugeSpec("Sleep", sl?.let { Stats.fmtMinutes(it.total) } ?: "--", "", sl?.let { (it.total / p.sleepGoalMin).toFloat().coerceIn(0f, 1f) },
                sub = sl?.let { "${(it.total * 100 / p.sleepGoalMin).toInt()}% of goal" })
        }
        else -> GaugeSpec(id, "--", "", null)
    }
}
