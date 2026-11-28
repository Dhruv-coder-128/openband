package com.openband.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openband.app.data.Db
import com.openband.app.data.Insights
import com.openband.app.data.M
import com.openband.app.data.Recovery
import com.openband.app.data.Stats
import com.openband.app.data.Workout
import com.openband.app.workout.RouteMath
import com.openband.app.workout.Sports
import com.openband.app.workout.WorkoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Full-screen pages that are opened on top of a tab. [id] is "page:name" or "page:name:number". */
@Composable
fun PageScreen(id: String, modifier: Modifier, onBack: () -> Unit) {
    val parts = id.split(':')
    val arg = parts.getOrNull(2)?.toLongOrNull()
    when (parts.getOrNull(1)) {
        "workout" -> WithWorkout(arg, onBack) { SummaryScreen(it, modifier, onBack) }
        "replay" -> WithWorkout(arg, onBack) { ReplayScreen(it, modifier, onBack) }
        "share" -> WithWorkout(arg, onBack) { ShareScreen(it, modifier, onBack) }
        "records" -> RecordsScreen(modifier, onBack)
        "heatmap" -> HeatmapScreen(modifier, onBack)
        "report" -> StoryHubScreen(modifier, onBack)
        "numbers" -> ReportScreen(modifier, onBack)
        "story-week" -> StoryScreen(false, arg?.toInt() ?: 0, modifier, onBack)
        "story-month" -> StoryScreen(true, arg?.toInt() ?: 0, modifier, onBack)
        "recovery" -> RecoveryScreen(modifier, onBack)
        "coach" -> CoachScreen(modifier, onBack)
        "aisetup" -> AiSetupScreen(modifier, onBack)
        "hrr" -> HeartTestScreen(modifier, onBack)
        "energy" -> EnergyScreen(modifier, onBack)
        "bodyage" -> BodyAgeScreen(modifier, onBack)
        // the Sunday notification opens the week it is about
        "recap" -> StoryScreen(false, recapWeeksBack(), modifier, onBack)
        "buzz" -> BuzzScreen(modifier, onBack)
        "measure-hr", "measure-spo2", "measure-hrv", "measure-stress" -> MeasureScreen(MeasureKind.of(parts[1])!!, modifier, onBack)
        else -> LaunchedEffect(id) { onBack() }
    }
}

@Composable
private fun WithWorkout(id: Long?, onBack: () -> Unit, content: @Composable (Workout) -> Unit) {
    val ctx = LocalContext.current
    var w by remember(id) { mutableStateOf<Workout?>(null) }
    LaunchedEffect(id) {
        w = withContext(Dispatchers.IO) { Db.get(ctx).workouts(1000).firstOrNull { it.id == id } }
        if (w == null) onBack()
    }
    w?.let { content(it) }
}

/** Header with a back arrow used by every page. */
@Composable
fun PageHeader(title: String, sub: String?, onBack: () -> Unit) {
    val look = LocalRace.current
    if (look.isConsole) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            ConsoleTitle(title, sub, Modifier.weight(1f).padding(start = 4.dp))
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack)
        Column(Modifier.padding(start = 2.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = look.text)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = look.dim)
        }
    }
}

@Composable
private fun PageColumn(modifier: Modifier, title: String, sub: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        PageHeader(title, sub, onBack)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** A tappable row inside a card: icon, two lines, optional figure on the right. */
@Composable
fun LinkRow(icon: ImageVector, color: Color, title: String, sub: String?, trailing: String? = null, onClick: (() -> Unit)?) {
    val look = LocalRace.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetricIcon(icon, color, 36.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = look.text, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = look.dim)
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium, color = look.text, modifier = Modifier.padding(start = 8.dp))
        if (onClick != null) Icon(AppIcons.Chevron, null, Modifier.padding(start = 6.dp).size(18.dp), tint = look.dim)
    }
}

/** The Activity screen's third tab. */
@Composable
fun InsightsTab() {
    val look = LocalRace.current
    val open = LocalOpenMetric.current
    SectionCard("Your progress") {
        LinkRow(AppIcons.Target, look.primary, "Personal records", "Fastest distances, best rounds and lifts") { open("page:records") }
        LinkRow(AppIcons.Trends, look.primary, "Weekly and monthly story", "Your week or month, card by card") { open("page:report") }
        LinkRow(AppIcons.Mountain, look.primary, "Heatmap", "Everywhere you have been with GPS") { open("page:heatmap") }
    }
    SectionCard("Your body") {
        LinkRow(AppIcons.Coach, look.primary, "Coach", "Today's plan and your questions") { open("page:coach") }
        LinkRow(AppIcons.Heart, look.primary, "Heart recovery test", "How fast your heart calms down") { open("page:hrr") }
        LinkRow(AppIcons.Gauge, look.primary, "Recovery", "Readiness score and its history") { open("page:recovery") }
        LinkRow(AppIcons.Wave, look.primary, "Body energy", "How much you have left today") { open("page:energy") }
        LinkRow(AppIcons.Target, look.primary, "Body age", "How your habits compare with your age") { open("page:bodyage") }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, color: Color, title: String, sub: String, onClick: () -> Unit) = LinkRow(icon, color, title, sub, null, onClick)

// ================================================================================================ recovery

private var lastRecovery: Pair<LocalDate, Recovery.Result>? = null

/** Home card. */
@Composable
fun RecoveryCard() {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    val ver by Db.changes.collectAsState()
    val goal = look.prefs.sleepGoalMin
    var r by remember { mutableStateOf(lastRecovery?.takeIf { it.first == LocalDate.now() }?.second) }
    LaunchedEffect(ver, goal) {
        r = withContext(Dispatchers.IO) { runCatching { Recovery.compute(Db.get(ctx), LocalDate.now(), goal) }.getOrNull() }
        r?.let { lastRecovery = LocalDate.now() to it }
    }
    val res = r ?: return
    SectionCard(null, Modifier.clickable { open("page:recovery") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudioRing(res.score / 100f, toneColor(res.tone), 68.dp, 8.dp) { Text("${res.score}", style = MaterialTheme.typography.titleLarge, color = look.text) }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text("Recovery · ${res.label}", style = MaterialTheme.typography.titleMedium, color = look.text)
                Text(
                    if (res.provisional) "Early estimate: ${res.baselineNights} of ${Recovery.GOOD_BASELINE} nights collected" else res.advice,
                    style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 2,
                )
            }
            Icon(AppIcons.Chevron, null, Modifier.size(18.dp), tint = look.dim)
        }
    }
}

@Composable
private fun RecoveryScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val ver by Db.changes.collectAsState()
    val goal = look.prefs.sleepGoalMin
    var days by remember { mutableStateOf<List<Pair<LocalDate, Recovery.Result?>>?>(null) }
    LaunchedEffect(ver, goal) {
        days = withContext(Dispatchers.IO) {
            val db = Db.get(ctx)
            (13 downTo 0).map { val d = LocalDate.now().minusDays(it.toLong()); d to runCatching { Recovery.compute(db, d, goal) }.getOrNull() }
        }
    }
    PageColumn(modifier, "Recovery", "How ready your body is today", onBack) {
        val list = days
        if (list == null) { SectionCard(null) { Text("Working it out…", color = look.dim) }; return@PageColumn }
        val res = list.last().second
        if (res == null) SectionCard(null) {
            Text("No reading for today yet. Wear the band overnight and let the Pebble app sync in the morning.", style = MaterialTheme.typography.bodyMedium, color = look.dim)
        } else {
            SectionCard(null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    StudioRing(res.score / 100f, toneColor(res.tone), 168.dp, 16.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${res.score}", style = MaterialTheme.typography.displayMedium, color = look.text)
                            Text(res.label, style = MaterialTheme.typography.bodyMedium, color = look.dim)
                        }
                    }
                    Text(res.advice, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium, color = look.text, textAlign = TextAlign.Center)
                    if (res.provisional) Text(
                        "Early estimate: it compares you with your own past nights, and only ${res.baselineNights} of ${Recovery.GOOD_BASELINE} are collected.",
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = look.warn, textAlign = TextAlign.Center,
                    )
                }
            }
            SectionCard("What it is made of") {
                res.parts.forEach { part ->
                    Column(Modifier.padding(vertical = 7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(part.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = look.text)
                            Text(part.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = look.text)
                        }
                        Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(7.dp).clip(CircleShape).background(look.panelHi)) {
                            Box(Modifier.fillMaxWidth(part.score / 100f).fillMaxHeight().clip(CircleShape).background(toneColor(if (part.score >= 67) 0 else if (part.score >= 34) 1 else 2)))
                        }
                        Text(
                            listOfNotNull(part.usual, if (part.weight > 0) "counts ${part.weight}%" else "lowers the score").joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = look.dim,
                        )
                    }
                }
            }
        }
        if (list.count { it.second != null } >= 2) SectionCard("Last 14 days") {
            val f = DateTimeFormatter.ofPattern("EEE d MMM")
            LabeledBars(
                list.map { it.second?.score?.toDouble() }, list.mapIndexed { i, (d, _) -> if (i % 2 == 1) "${d.dayOfMonth}" else "" }, look.primary, height = 120.dp,
                tipOf = { i -> (list[i].second?.let { "${it.score} · ${it.label}" } ?: "No data") to list[i].first.format(f) },
            )
        }
        Text(
            "An estimate made by OpenBand from your own data. It is not Whoop's formula and not medical advice.",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

// ================================================================================================ records

private fun recordValue(r: Insights.Record, imperial: Boolean): String = when (r.kind) {
    Insights.Kind.TIME_FOR_DISTANCE, Insights.Kind.ROUND -> fmtDuration((r.value * 1000).toLong())
    Insights.Kind.METERS -> fmtDistance(r.value, imperial).let { "${it.first} ${it.second}" }
    Insights.Kind.DURATION -> fmtDuration((r.value * 1000).toLong())
    Insights.Kind.COUNT -> "%,d".format(r.value.toLong())
    Insights.Kind.CLIMB -> "${r.value.toInt()} m"
    Insights.Kind.KCAL -> "${r.value.toInt()} kcal"
    Insights.Kind.SLEEP -> Stats.fmtMinutes(r.value)
    Insights.Kind.WEIGHT -> "${r.value.toInt()} kg"
}

@Composable
private fun RecordsScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    val ver by Db.changes.collectAsState()
    var records by remember { mutableStateOf<List<Insights.Record>?>(null) }
    LaunchedEffect(ver) {
        records = withContext(Dispatchers.IO) { runCatching { Insights.records(Db.get(ctx)) { WorkoutManager.elevationGain(it) } }.getOrDefault(emptyList()) }
    }
    PageColumn(modifier, "Personal records", "Your best so far", onBack) {
        val list = records
        if (list == null) { SectionCard(null) { Text("Looking through your workouts…", color = look.dim) }; return@PageColumn }
        if (list.isEmpty()) SectionCard(null) { Text("No records yet. Record a walk or run with GPS to start.", color = look.dim) }
        val titles = mapOf(
            Insights.Group.FOOT to "Walking and running", Insights.Group.CYCLE to "Cycling",
            Insights.Group.ALL to "All workouts", Insights.Group.DAILY to "Daily", Insights.Group.STRENGTH to "Lifting (estimated 1-rep max)",
        )
        val date = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        Insights.Group.entries.forEach { g ->
            val items = list.filter { it.group == g }
            if (items.isNotEmpty()) SectionCard(titles.getValue(g)) {
                items.forEach { r ->
                    val detail = when (r.kind) {
                        Insights.Kind.TIME_FOR_DISTANCE -> r.extra?.let { m ->
                            val pace = r.value / (m / if (p.imperial) 1609.344 else 1000.0)
                            "%d:%02d %s".format((pace / 60).toInt(), (pace % 60).toInt(), if (p.imperial) "/mi" else "/km")
                        }
                        Insights.Kind.ROUND -> r.extra?.let { "${it.toInt()} m round" }
                        else -> null
                    }
                    val icon = when (r.kind) {
                        Insights.Kind.TIME_FOR_DISTANCE, Insights.Kind.ROUND, Insights.Kind.DURATION -> AppIcons.Timer
                        Insights.Kind.METERS, Insights.Kind.CLIMB -> AppIcons.Mountain
                        Insights.Kind.KCAL -> AppIcons.Flame
                        Insights.Kind.SLEEP -> AppIcons.Moon
                        Insights.Kind.COUNT -> if (g == Insights.Group.DAILY) AppIcons.Steps else AppIcons.Target
                        Insights.Kind.WEIGHT -> AppIcons.Dumbbell
                    }
                    LinkRow(
                        icon, look.primary, r.title, listOfNotNull(date.format(Date(r.at)), detail).joinToString(" · "), recordValue(r, p.imperial),
                        r.workoutId?.let { id -> { open("page:workout:$id") } },
                    )
                }
            }
        }
        Text(
            "Distance records come from GPS workouts recorded in OpenBand. They appear once you have covered that distance in one go.",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
}

// ================================================================================================ heatmap

/** Colours from rarely to very often travelled. */
private val HEAT = listOf(Color(0xFFFFE08A), Color(0xFFFFA62B), Color(0xFFF2542D), Color(0xFFB80F2C))

@Composable
private fun HeatmapScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<List<Workout>?>(null) }
    LaunchedEffect(Unit) { all = withContext(Dispatchers.IO) { Db.get(ctx).workouts(1000).filter { it.route.size > 1 } } }
    var heat by remember { mutableStateOf<List<RouteMath.Heat>>(emptyList()) }
    val shown = remember(all, filter) {
        all.orEmpty().filter { when (filter) { 1 -> Insights.isFoot(it.sport); 2 -> Insights.isCycle(it.sport); else -> true } }
    }
    LaunchedEffect(shown) { heat = withContext(Dispatchers.Default) { RouteMath.heat(shown.map { it.route }) } }

    // no outer scroll here: the map gets the drags
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageHeader("Heatmap", "Everywhere you have been with GPS", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            listOf("All", "Walk & run", "Cycle").forEachIndexed { i, l -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(l) }) }
        }
        if (all == null) Text("Loading…", color = look.dim)
        else if (shown.isEmpty()) Text("No GPS workouts here yet.", color = look.dim, modifier = Modifier.padding(top = 12.dp))
        else {
            // frame the places near the latest workout; farther trips are a drag away
            val home = shown.first().route.first()
            val near = shown.filter { com.openband.app.workout.Laps.straight(home, it.route.first()) < 6000 }.flatMap { it.route }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val h = maxHeight
                androidx.compose.runtime.key(filter) {
                    OsmMap(
                        emptyList(), height = h, flags = false, fit = near,
                        lines = heat.map { MapLine(it.points, HEAT[it.level - 1], 3.5f + it.level * 0.5f) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Once", style = MaterialTheme.typography.labelSmall, color = look.dim)
                HEAT.forEach { c -> Box(Modifier.padding(start = 6.dp).width(28.dp).height(8.dp).clip(CircleShape).background(c)) }
                Text("Very often", Modifier.padding(start = 6.dp).weight(1f), style = MaterialTheme.typography.labelSmall, color = look.dim)
            }
            val dist = fmtDistance(shown.sumOf { it.distanceM }, p.imperial)
            Text(
                "${shown.size} ${if (shown.size == 1) "workout" else "workouts"} · ${dist.first} ${dist.second} in total",
                style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
        }
    }
}

// ================================================================================================ reports

@Composable
private fun ReportScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    val ver by Db.changes.collectAsState()
    var monthly by rememberSaveable { mutableStateOf(false) }
    var back by rememberSaveable { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<Pair<Insights.Period, Insights.Period>?>(null) }
    LaunchedEffect(ver, monthly, back) {
        data = null
        data = withContext(Dispatchers.IO) {
            val db = Db.get(ctx)
            val (a, b) = Insights.range(monthly, back)
            val (c, d) = Insights.range(monthly, back + 1)
            Insights.period(db, a, b) to Insights.period(db, c, d)
        }
    }
    val (from, to) = Insights.range(monthly, back)
    val title = if (monthly) from.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    else "${from.format(DateTimeFormatter.ofPattern("d MMM"))} – ${to.format(DateTimeFormatter.ofPattern("d MMM"))}"
    val word = if (monthly) "month" else "week"

    PageColumn(modifier, "Reports", "Your ${if (monthly) "monthly" else "weekly"} summary", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            FilterChip(selected = !monthly, onClick = { monthly = false; back = 0 }, label = { Text("Week") })
            FilterChip(selected = monthly, onClick = { monthly = true; back = 0 }, label = { Text("Month") })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackButton({ if (back < 51) back++ })
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = look.text)
                if (back == 0) Text("This $word so far", style = MaterialTheme.typography.labelSmall, color = look.dim)
            }
            Icon(
                AppIcons.Chevron, "Later", Modifier.size(44.dp).clip(CircleShape).clickable(enabled = back > 0) { back-- }.padding(11.dp),
                tint = if (back > 0) look.text else look.border,
            )
        }
        val d = data
        if (d == null) { SectionCard(null) { Text("Adding it up…", color = look.dim) }; return@PageColumn }
        val (now, before) = d
        if (now.days.all { it.steps == null && it.sleep == null } && now.workouts.isEmpty()) {
            SectionCard(null) { Text("Nothing recorded in this $word.", color = look.dim) }
            return@PageColumn
        }

        /** Compares daily averages, so a week that is still running is judged fairly. */
        @Composable
        fun row(name: String, f: (Insights.Day) -> Double?, fmt: (Double) -> String, higherIsBetter: Boolean?) {
            val v = now.avg(f) ?: return
            val old = before.avg(f)
            val diff = if (old != null && old > 0) (v - old) / old * 100 else null
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = look.text)
                    Text(
                        if (diff == null) "no earlier $word to compare" else if (Math.abs(diff) < 1) "same as the $word before"
                        else "${if (diff > 0) "up" else "down"} ${Math.abs(diff).toInt()}% on the $word before",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (diff == null || Math.abs(diff) < 1 || higherIsBetter == null) look.dim else if ((diff > 0) == higherIsBetter) look.ok else look.warn,
                    )
                }
                Text(fmt(v), style = MaterialTheme.typography.titleMedium, color = look.text)
            }
        }

        val steps = now.sum { it.steps }
        val goalDays = now.days.count { (it.steps ?: 0.0) >= p.stepGoal }
        val dist = fmtDistance(now.sum { it.distance }, p.imperial)
        SectionCard("Movement") {
            Row(Modifier.fillMaxWidth()) {
                FigureColumn("Steps", steps?.let { "%,d".format(it.toLong()) } ?: "--", "", Modifier.weight(1.2f))
                FigureColumn("Distance", dist.first, dist.second, Modifier.weight(1f))
                FigureColumn("Goal days", "$goalDays", "of ${now.days.count { it.steps != null }}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            val f = DateTimeFormatter.ofPattern("EEE d MMM")
            LabeledBars(
                now.days.map { it.steps }, now.days.mapIndexed { i, x -> if (!monthly) x.date.dayOfWeek.name.take(1) else if (i % 5 == 0) "${x.date.dayOfMonth}" else "" },
                metricColor(M.STEPS, look), goal = p.stepGoal.toDouble(), height = 120.dp,
                tipOf = { i -> (now.days[i].steps?.let { "%,d steps".format(it.toLong()) } ?: "No data") to now.days[i].date.format(f) },
            )
            row("Steps per day", { it.steps }, { "%,d".format(it.toLong()) }, true)
            row("Active calories per day", { it.kcal }, { "${it.toInt()} kcal" }, true)
        }
        if (now.days.any { it.sleep != null }) SectionCard("Sleep") {
            val f = DateTimeFormatter.ofPattern("EEE d MMM")
            LabeledBars(
                now.days.map { it.sleep }, now.days.mapIndexed { i, x -> if (!monthly) x.date.dayOfWeek.name.take(1) else if (i % 5 == 0) "${x.date.dayOfMonth}" else "" },
                metricColor(INST_SLEEP, look), goal = p.sleepGoalMin.toDouble(), height = 110.dp,
                tipOf = { i -> (now.days[i].sleep?.let { Stats.fmtMinutes(it) } ?: "No data") to now.days[i].date.format(f) },
            )
            row("Sleep per night", { it.sleep }, { Stats.fmtMinutes(it) }, true)
        }
        SectionCard("Body (daily averages)") {
            row("Resting heart rate", { it.restingHr }, { "${it.toInt()} bpm" }, false)
            row("Heart rate variability", { it.hrv }, { "${it.toInt()} ms" }, true)
            row("Blood oxygen", { it.spo2 }, { "${it.toInt()}%" }, true)
            row("Stress", { it.stress }, { "${it.toInt()} / 100" }, false)
        }
        SectionCard("Workouts") {
            if (now.workouts.isEmpty()) Text("No workouts in this $word.", style = MaterialTheme.typography.bodySmall, color = look.dim)
            else {
                val wd = fmtDistance(now.workouts.sumOf { it.distanceM }, p.imperial)
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    FigureColumn("Workouts", "${now.workouts.size}", "", Modifier.weight(1f))
                    FigureColumn("Time", Stats.fmtMinutes(now.workouts.sumOf { it.movingMs } / 60_000.0), "", Modifier.weight(1.2f))
                    FigureColumn("Distance", wd.first, wd.second, Modifier.weight(1f))
                }
                val df = SimpleDateFormat("EEE d MMM", Locale.getDefault())
                now.workouts.reversed().forEach { w ->
                    val x = fmtDistance(w.distanceM, p.imperial)
                    LinkRow(
                        sportIcon(w.sport), look.primary, Sports.byId(w.sport).name, "${df.format(Date(w.start))} · ${fmtDuration(w.movingMs)}",
                        if (w.distanceM >= 50) "${x.first} ${x.second}" else null,
                    ) { open("page:workout:${w.id}") }
                }
            }
        }
        RaceButton("Share this summary", Modifier.fillMaxWidth().padding(top = 8.dp), primary = false) {
            val text = buildString {
                appendLine("My $word, $title")
                steps?.let { appendLine("Steps: %,d (goal reached on %d days)".format(it.toLong(), goalDays)) }
                appendLine("Distance: ${dist.first} ${dist.second}")
                now.avg { it.sleep }?.let { appendLine("Sleep: ${Stats.fmtMinutes(it)} a night") }
                now.avg { it.restingHr }?.let { appendLine("Resting heart rate: ${it.toInt()} bpm") }
                appendLine("Workouts: ${now.workouts.size}")
                append("Made with OpenBand")
            }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
            ctx.startActivity(android.content.Intent.createChooser(send, "Share summary"))
        }
    }
}

private fun sportIcon(id: String): ImageVector = when {
    Insights.isCycle(id) || id == "spin" -> AppIcons.Bike
    id == "swim" -> AppIcons.Swim
    Insights.isFoot(id) -> AppIcons.Run
    else -> AppIcons.Activity
}
