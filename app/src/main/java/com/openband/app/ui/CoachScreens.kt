package com.openband.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openband.app.ai.AiClient
import com.openband.app.ai.AiProvider
import com.openband.app.ai.AiSettings
import com.openband.app.ai.Verify
import com.openband.app.ble.BandManager
import com.openband.app.data.Coach
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Workout
import com.openband.app.workout.Ghost
import com.openband.app.workout.HeartTest
import com.openband.app.workout.Laps
import com.openband.app.workout.SportDef
import com.openband.app.workout.WorkoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ================================================================================================ heart recovery test

@Composable
fun HeartTestScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val st by HeartTest.state.collectAsState()
    val band by BandManager.state.collectAsState()
    val ver by Db.changes.collectAsState()
    var history by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }
    LaunchedEffect(ver, st.phase) {
        history = withContext(Dispatchers.IO) { Db.get(ctx).series(M.HRR1, 0, Long.MAX_VALUE).takeLast(10).map { it.ts to it.value.roundToInt() } }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            // an unfinished wait is dropped; a running measurement carries on; a result is cleared for next time
            if (HeartTest.state.value.phase != HeartTest.Phase.MEASURING) HeartTest.cancel()
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        PageHeader("Heart recovery test", "How fast your heart calms down", onBack)
        SectionCard(null) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when (st.phase) {
                    HeartTest.Phase.IDLE -> {
                        Text(
                            "1. Walk briskly or run for at least 3 minutes, until you breathe harder.\n2. Tap Start while still moving.\n3. When your heart rate appears, stop and tap \"I have stopped\".\n4. Stand still for 2 minutes.",
                            style = MaterialTheme.typography.bodyMedium, color = look.text,
                        )
                        RaceButton("Start", Modifier.fillMaxWidth().padding(top = 14.dp), enabled = WorkoutManager.active.value == null) { HeartTest.begin(ctx) }
                        if (WorkoutManager.active.value != null) Text("Finish the running workout first.", style = MaterialTheme.typography.bodySmall, color = look.warn)
                        else if (!band.ready) Text("The band is not connected yet; it will be tried when you start.", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 6.dp))
                    }
                    HeartTest.Phase.WAITING, HeartTest.Phase.READY -> {
                        StudioRing(null, look.primary, 160.dp, 14.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(st.bpm?.toString() ?: "--", style = MaterialTheme.typography.displayMedium, color = look.text)
                                Text("bpm", style = MaterialTheme.typography.bodySmall, color = look.dim)
                            }
                        }
                        Text(
                            if (st.phase == HeartTest.Phase.WAITING) "Finding your heart rate… keep moving (about 30 seconds)." else "Now stop, stand still and tap the button.",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = look.text, textAlign = TextAlign.Center,
                        )
                        RaceButton("I have stopped", Modifier.fillMaxWidth().padding(top = 12.dp), enabled = st.phase == HeartTest.Phase.READY) { HeartTest.stopped() }
                        RaceButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp), primary = false) { HeartTest.cancel() }
                    }
                    HeartTest.Phase.MEASURING -> {
                        StudioRing((120 - st.secondsLeft) / 120f, look.primary, 180.dp, 14.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (st.secondsLeft > 0) "%d:%02d".format(st.secondsLeft / 60, st.secondsLeft % 60) else "…", style = MaterialTheme.typography.displayMedium, color = look.text)
                                Text(st.bpm?.let { "$it bpm" } ?: "waiting for the band", style = MaterialTheme.typography.bodyMedium, color = look.dim)
                            }
                        }
                        Text(
                            "Stand still and breathe normally." + (st.startBpm?.let { " Started at $it bpm." } ?: "") + (st.drop1?.let { " First minute: down $it." } ?: ""),
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = look.text, textAlign = TextAlign.Center,
                        )
                        RaceButton("Cancel test", Modifier.fillMaxWidth().padding(top = 12.dp), primary = false) { HeartTest.cancel() }
                    }
                    HeartTest.Phase.DONE -> {
                        val d1 = st.drop1 ?: 0
                        val (word, tone) = HeartTest.rating(d1)
                        Text("$d1", style = MaterialTheme.typography.displayLarge, color = toneColor(tone))
                        Text("beats lower after 1 minute · $word", style = MaterialTheme.typography.titleMedium, color = look.text)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            FigureColumn("Started at", "${st.startBpm ?: "--"}", "bpm", Modifier.weight(1f))
                            FigureColumn("After 1 min", st.startBpm?.let { "${it - d1}" } ?: "--", "bpm", Modifier.weight(1f))
                            FigureColumn("Drop in 2 min", st.drop2?.toString() ?: "--", "bpm", Modifier.weight(1f))
                        }
                        if ((st.startBpm ?: 0) < 100) Text(
                            "Your heart rate was under 100 when you stopped, so this result says little. Try after a harder effort.",
                            Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = look.warn,
                        )
                        RaceButton("Done", Modifier.fillMaxWidth().padding(top = 14.dp)) { HeartTest.cancel(); onBack() }
                    }
                    HeartTest.Phase.FAILED -> {
                        Text(st.message, style = MaterialTheme.typography.bodyMedium, color = look.danger, textAlign = TextAlign.Center)
                        RaceButton("Try again", Modifier.fillMaxWidth().padding(top = 12.dp)) { HeartTest.cancel() }
                    }
                }
            }
        }
        if (history.isNotEmpty()) SectionCard("Your tests") {
            val f = SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault())
            history.reversed().forEach { (t, v) -> KeyValue(f.format(Date(t)), "−$v bpm · ${HeartTest.rating(v).first}") }
        }
        Text(
            "Compare tests done after a similar effort. Guide values after 1 minute: 30+ excellent, 20–29 good, 12–19 fair, under 12 slow. " +
                "This is not a medical test; if it is often slow or you feel unwell, talk to a doctor.",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp, start = 4.dp, bottom = 24.dp),
        )
    }
}

/** Shown on a workout page when a heart test was taken right after it. */
@Composable
fun WorkoutHeartCard(w: Workout) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val open = LocalOpenMetric.current
    val ver by Db.changes.collectAsState()
    var drop by remember(w.id) { mutableStateOf<Int?>(null) }
    LaunchedEffect(w.id, ver) {
        drop = withContext(Dispatchers.IO) { Db.get(ctx).series(M.HRR1, w.end - 60_000, w.end + 10 * 60_000).firstOrNull()?.value?.roundToInt() }
    }
    val d = drop ?: return
    SectionCard(null, Modifier.clickable { open("page:hrr") }) {
        LinkRow(AppIcons.Heart, toneColor(HeartTest.rating(d).second), "Heart recovery · ${HeartTest.rating(d).first}", "$d bpm lower one minute after this workout", null, null)
    }
}

// ================================================================================================ ghost race

/** Start-screen card: pick what to race against. */
@Composable
fun GhostPicker(sp: SportDef, lat: Double?, lon: Double?) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val choice by Ghost.choice.collectAsState()
    var past by remember(sp.id) { mutableStateOf<List<Workout>?>(null) }
    var voice by remember { mutableStateOf(Ghost.voice(ctx)) }
    var target by remember { mutableIntStateOf(660) } // seconds per km
    LaunchedEffect(sp.id) {
        past = withContext(Dispatchers.IO) {
            val foot = com.openband.app.data.Insights.isFoot(sp.id)
            Db.get(ctx).workouts(300).filter { it.route.size > 20 && it.distanceM > 300 && com.openband.app.data.Insights.isFoot(it.sport) == foot }
        }
    }
    val all = past ?: return
    // tracks that start where you are standing come first; without a position, every track is offered
    val here = if (lat != null && lon != null) all.filter { Laps.straight(it.route.first(), com.openband.app.data.RoutePt(lat, lon)) < 250 } else all
    val last = here.maxByOrNull { it.start }
    val best = here.minByOrNull { it.movingMs / it.distanceM }
    // forget a choice made for another sport
    LaunchedEffect(sp.id, all) {
        val c = Ghost.choice.value
        if (c?.workoutId != null && all.none { it.id == c.workoutId }) Ghost.choice.value = null
    }
    val df = SimpleDateFormat("d MMM", Locale.getDefault())
    fun pace(w: Workout) = (w.movingMs / 1000.0 / (w.distanceM / 1000.0))
    fun mmss(s: Double) = "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

    SectionCard("Ghost race") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = choice == null, onClick = { Ghost.choice.value = null }, label = { Text("Off") })
            if (last != null) FilterChip(selected = choice?.label?.startsWith("Last") == true, onClick = { Ghost.choice.value = Ghost.of("Last time (${df.format(Date(last.start))})", last) }, label = { Text("Last time") })
            if (best != null && best.id != last?.id) FilterChip(selected = choice?.label?.startsWith("Best") == true, onClick = { Ghost.choice.value = Ghost.of("Best (${df.format(Date(best.start))})", best) }, label = { Text("Best") })
            FilterChip(selected = choice != null && choice?.track == null, onClick = { Ghost.choice.value = Ghost.target(target.toDouble()) }, label = { Text("Target pace") })
        }
        val c = choice
        when {
            c == null -> Text(
                if (here.isEmpty() && all.isNotEmpty()) "No earlier track starts here. Go to your usual start point, or set a target pace."
                else if (all.isEmpty()) "Record one GPS workout and you can race it next time. A target pace works right away."
                else "Race your own earlier self.",
                style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp),
            )
            c.track == null -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${mmss(target.toDouble())} per km", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = look.text)
                listOf("Faster" to -15, "Slower" to 15).forEach { (label, d) ->
                    FilterChip(selected = false, onClick = { target = (target + d).coerceIn(180, 1200); Ghost.choice.value = Ghost.target(target.toDouble()) }, label = { Text(label) }, modifier = Modifier.padding(start = 8.dp))
                }
            }
            else -> all.firstOrNull { it.id == c.workoutId }?.let { w ->
                Text(
                    "${c.label}: ${fmtDistance(w.distanceM, look.prefs.imperial).let { "${it.first} ${it.second}" }} in ${fmtDuration(w.movingMs)} (${mmss(pace(w))} /km)",
                    style = MaterialTheme.typography.bodySmall, color = look.text, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (choice != null) Toggle("Voice updates every 500 m", voice) { voice = it; Ghost.setVoice(ctx, it) }
    }
}

/** During the workout: how far ahead or behind. */
@Composable
fun GhostLiveCard() {
    val look = LocalRace.current
    val live by Ghost.live.collectAsState()
    val g = live ?: return
    val sec = g.aheadSec
    val col = if (sec == null || abs(sec) < 3) look.text else if (sec > 0) look.ok else look.danger
    SectionCard(null) {
        Text(g.label, style = MaterialTheme.typography.labelMedium, color = look.dim)
        Text(
            when {
                sec == null -> "Start moving…"
                abs(sec) < 3 -> "Level with your ghost"
                else -> "${abs(sec).roundToInt()} s ${if (sec > 0) "ahead" else "behind"}"
            },
            style = MaterialTheme.typography.headlineMedium, color = col,
        )
        val m = g.aheadM
        Text(
            when {
                g.youFinished -> "You have covered the ghost's whole distance."
                g.ghostFinished -> "The ghost has finished."
                m != null -> "${abs(m).roundToInt()} m ${if (m >= 0) "in front" else "to catch up"}"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall, color = look.dim,
        )
    }
}
