package com.openband.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openband.app.data.Db
import com.openband.app.data.RoutePt
import com.openband.app.data.Workout
import com.openband.app.workout.Gpx
import com.openband.app.workout.Laps
import com.openband.app.workout.RouteMath
import com.openband.app.workout.Sports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Replay / share / GPX buttons on a workout. */
@Composable
fun WorkoutActions(w: Workout) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (w.route.size > 2) RaceButton("Replay", Modifier.weight(1f)) { open("page:replay:${w.id}") }
        RaceButton("Share", Modifier.weight(1f), primary = false) { open("page:share:${w.id}") }
        if (w.route.size > 2) RaceButton("GPX file", Modifier.weight(1f), primary = false) {
            scope.launch {
                val f = withContext(Dispatchers.IO) {
                    val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(w.start))
                    Export.file(ctx, "openband-${w.sport}-$stamp.gpx").apply { writeText(Gpx.build(w, "${Sports.byId(w.sport).name} $stamp")) }
                }
                Export.share(ctx, f, "application/gpx+xml")
            }
        }
    }
}

/** Earlier and later workouts that went the same way, with this one highlighted. */
@Composable
fun SameRouteCard(w: Workout) {
    if (w.route.size < 6) return
    val look = LocalRace.current
    val p = look.prefs
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    var same by remember(w.id) { mutableStateOf<List<Workout>?>(null) }
    LaunchedEffect(w.id) {
        same = withContext(Dispatchers.Default) {
            val foot = com.openband.app.data.Insights.isFoot(w.sport)
            Db.get(ctx).workouts(300).filter { o ->
                o.id != w.id && o.distanceM > 100 && com.openband.app.data.Insights.isFoot(o.sport) == foot && RouteMath.sameRoute(w.route, o.route)
            }
        }
    }
    val others = same ?: return
    SectionCard("Same route") {
        if (others.isEmpty()) {
            Text("The first time on this route. Go this way again and the two will be compared here.", style = MaterialTheme.typography.bodySmall, color = look.dim)
            return@SectionCard
        }
        val unit = if (p.imperial) 1609.344 else 1000.0
        fun pace(x: Workout) = x.movingMs / 1000.0 / (x.distanceM / unit)
        fun fmt(sec: Double) = "%d:%02d".format((sec / 60).toInt(), (sec % 60).toInt())
        // the previous time on this route, or the next one if this was the first
        val ref = others.filter { it.start < w.start }.maxByOrNull { it.start } ?: others.minBy { it.start }
        val diff = pace(w) - pace(ref)
        val df = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        Text(
            if (Math.abs(diff) < 3) "Same pace as ${df.format(Date(ref.start))}."
            else "${Math.abs(diff).toInt()} s per ${if (p.imperial) "mi" else "km"} ${if (diff < 0) "faster" else "slower"} than ${df.format(Date(ref.start))}.",
            style = MaterialTheme.typography.titleMedium, color = if (Math.abs(diff) < 3) look.text else if (diff < 0) look.ok else look.warn,
        )
        Spacer(Modifier.height(8.dp))
        // side by side with that day
        val mine = Laps.detect(w.route); val theirs = Laps.detect(ref.route)
        @Composable
        fun line(name: String, a: String, b: String) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(name, Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall, color = look.dim)
            Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = look.text, textAlign = TextAlign.End)
            Text(b, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = look.dim, textAlign = TextAlign.End)
        }
        line("", "This one", df.format(Date(ref.start)).substringAfter(' '))
        line("Pace", fmt(pace(w)), fmt(pace(ref)))
        line("Time", fmtDuration(w.movingMs), fmtDuration(ref.movingMs))
        line("Distance", fmtDistance(w.distanceM, p.imperial).first, fmtDistance(ref.distanceM, p.imperial).first)
        if (w.avgHr > 0 && ref.avgHr > 0) line("Average heart", "${w.avgHr}", "${ref.avgHr}")
        if (mine != null && theirs != null) {
            line("Rounds", "${mine.count}", "${theirs.count}")
            line("Time per round", fmtDuration(mine.avgSeconds * 1000L), fmtDuration(theirs.avgSeconds * 1000L))
        }

        Spacer(Modifier.height(12.dp))
        Text("Every time on this route", style = MaterialTheme.typography.labelMedium, color = look.dim)
        val all = (others + w).sortedByDescending { it.start }
        val best = all.minOf { pace(it) }
        all.take(10).forEach { x ->
            val me = x.id == w.id
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !me) { open("page:workout:${x.id}") }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(df.format(Date(x.start)).substringAfter(' '), Modifier.width(58.dp), style = MaterialTheme.typography.bodySmall, color = if (me) look.text else look.dim, fontWeight = if (me) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(look.panelHi)) {
                    Box(Modifier.fillMaxWidth((best / pace(x)).toFloat().coerceIn(0.2f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(look.primary.copy(alpha = if (me) 1f else 0.45f)))
                }
                Text(fmt(pace(x)), Modifier.width(52.dp), style = MaterialTheme.typography.bodyMedium, color = look.text, textAlign = TextAlign.End)
                Text(if (pace(x) <= best + 0.01) "Best" else "", Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall, color = look.ok, textAlign = TextAlign.End)
            }
        }
    }
}

// ================================================================================================ replay

private fun pointAt(route: List<RoutePt>, t: Float): Pair<Int, RoutePt> {
    var lo = 0; var hi = route.size - 1
    while (lo < hi) { val mid = (lo + hi + 1) / 2; if (route[mid].t <= t) lo = mid else hi = mid - 1 }
    val a = route[lo]; val b = route.getOrNull(lo + 1) ?: return lo to a
    val span = (b.t - a.t).coerceAtLeast(1)
    val f = ((t - a.t) / span).coerceIn(0f, 1f)
    return lo to RoutePt(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, t.toInt(), a.alt + (b.alt - a.alt) * f)
}

/** The workout drawn again as it happened, with live figures. */
@Composable
fun ReplayScreen(w: Workout, modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val route = w.route
    if (route.size < 3) { LaunchedEffect(Unit) { onBack() }; return }
    val end = route.last().t.toFloat().coerceAtLeast(1f)
    val cum = remember(w.id) { RouteMath.cumulative(route) }
    // the stored distance is the filtered one; scale the replay so both agree at the finish
    val scale = if (cum.last() > 1) w.distanceM / cum.last() else 1.0
    val laps = remember(w.id) { Laps.detect(route) }
    var t by remember(w.id) { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableIntStateOf(1) }
    var follow by remember { mutableStateOf(false) }
    val seconds = listOf(60f, 30f, 12f)

    LaunchedEffect(playing, speed) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (t < end) {
            val now = withFrameNanos { it }
            t = (t + (now - last) / 1e9f * end / seconds[speed]).coerceAtMost(end)
            last = now
        }
        playing = false
    }

    val (index, here) = pointAt(route, t)
    val done = route.subList(0, index + 1) + here
    val meters = (cum[index] + Laps.straight(route[index], here)) * scale
    // pace over the last 30 seconds of the replayed moment
    val (backIndex, backPt) = pointAt(route, (t - 30f).coerceAtLeast(0f))
    val backMeters = (cum[backIndex] + Laps.straight(route[backIndex], backPt)) * scale
    val dt = here.t - backPt.t
    val pace = if (dt >= 5 && meters - backMeters > 5) dt / ((meters - backMeters) / (if (p.imperial) 1609.344 else 1000.0)) else null
    val hr = w.hr.lastOrNull { it.first <= t }?.second
    val round = laps?.let { r ->
        var edge = r.startAtSec.toFloat(); var n = 1
        for (lap in r.laps) { edge += lap.seconds; if (t >= edge) n++ }
        n.coerceAtMost(r.count)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PageHeader("Replay", SimpleDateFormat("EEEE, d MMM · HH:mm", Locale.getDefault()).format(Date(w.start)), onBack)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp)) {
            val h = maxHeight
            androidx.compose.runtime.key(follow) {
                OsmMap(
                    done, height = h, fit = route, flags = false, follow = follow,
                    me = if (follow) here.lat to here.lon else null, marker = here,
                    lines = listOf(MapLine(route, Color(0x99FFFFFF), 3f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            val d = fmtDistance(meters, p.imperial)
            FigureColumn("Time", fmtDuration((t * 1000).toLong()), "", Modifier.weight(1f))
            FigureColumn("Distance", d.first, d.second, Modifier.weight(1f))
            FigureColumn("Pace", pace?.takeIf { it < 3600 }?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) } ?: "--", "", Modifier.weight(0.8f))
            FigureColumn("Heart", hr?.toString() ?: "--", "", Modifier.weight(0.6f))
            if (laps != null) FigureColumn("Round", "$round/${laps.count}", "", Modifier.weight(0.7f))
        }
        Slider(value = t, onValueChange = { playing = false; t = it }, valueRange = 0f..end)
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RaceButton(if (playing) "Pause" else if (t >= end) "Again" else "Play", Modifier.weight(1f)) {
                if (t >= end) t = 0f
                playing = !playing
            }
            listOf("1x", "2x", "5x").forEachIndexed { i, label -> FilterChip(selected = speed == i, onClick = { speed = i }, label = { Text(label) }) }
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Follow") })
        }
    }
}

// ================================================================================================ share

@Composable
fun ShareScreen(w: Workout, modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasRoute = w.route.size >= 2
    var style by remember { mutableStateOf(if (hasRoute) CardStyle.SATELLITE else CardStyle.DARK) }
    var card by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(w.id, style) {
        card = null
        card = withContext(Dispatchers.IO) { runCatching { ShareCard.render(ctx, w, style) }.getOrNull() }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        PageHeader("Share workout", "A picture you can post or send", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            CardStyle.entries.filter { hasRoute || it != CardStyle.SATELLITE }.forEach { s -> FilterChip(selected = style == s, onClick = { style = s }, label = { Text(s.label) }) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1080f / 1350f).clip(RoundedCornerShape(16.dp)).background(look.panelHi), contentAlignment = Alignment.Center) {
            val b = card
            if (b != null) Image(remember(b) { b.asImageBitmap() }, "Workout card", Modifier.fillMaxSize())
            else Text("Drawing…", color = look.dim)
        }
        if (style == CardStyle.SATELLITE) Text(
            "The satellite card shows where you were. Choose Dark or Light to share only the shape of your route.",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp),
        )
        RaceButton("Share picture", Modifier.fillMaxWidth().padding(top = 12.dp), enabled = card != null) {
            val b = card ?: return@RaceButton
            scope.launch {
                val f: File = withContext(Dispatchers.IO) {
                    Export.file(ctx, "openband-${w.sport}-${w.start / 1000}.png").apply { outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                }
                Export.share(ctx, f, "image/png")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
