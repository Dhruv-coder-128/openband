package com.openband.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Workout
import com.openband.app.workout.ActiveState
import com.openband.app.workout.Strength
import com.openband.app.workout.Training
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun kg(v: Double) = if (v % 1.0 == 0.0) "${v.toInt()}" else "%.1f".format(v)
private fun mmss(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

/** A value with minus / plus buttons. */
@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = look.dim)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun key(t: String, on: () -> Unit) = Box(Modifier.size(44.dp).clip(CircleShape).background(look.panelHi).pressable(onClick = on), contentAlignment = Alignment.Center) {
                Text(t, style = MaterialTheme.typography.titleLarge, color = look.text)
            }
            key("−", onMinus)
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = look.text, textAlign = TextAlign.Center, maxLines = 1)
            key("+", onPlus)
        }
    }
}

/** The sport-specific tools shown while a workout runs. */
@Composable
fun TrainingPanel(a: ActiveState) {
    val id = a.sport.id
    if (Training.isInterval(id)) IntervalPanel()
    if (Training.isLifting(id)) LiftingPanel(a)
    if (Training.isSwim(id)) SwimPanel(a)
    if (Training.isCalm(id)) BreathingPanel()
}

// ------------------------------------------------------------------------------------------------ lifting

@Composable
private fun LiftingPanel(a: ActiveState) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val sets by Training.sets.collectAsState()
    val restUntil by Training.restUntil.collectAsState()
    val restSeconds by Training.restSeconds.collectAsState()
    val list = remember(a.sport.id) { if (a.sport.id == "compound") Strength.all.filter { it.compound } else Strength.all }
    var exercise by rememberSaveable(a.sport.id) { mutableStateOf(list.first().id) }
    var weight by rememberSaveable { mutableDoubleStateOf(20.0) }
    var reps by rememberSaveable { mutableIntStateOf(8) }
    var previous by remember { mutableStateOf<List<Strength.SetEntry>>(emptyList()) }
    val body = remember { Db.get(ctx).latest(M.WEIGHT)?.value ?: RaceSettings.prefs.value.weightKg.toDouble() }

    // the numbers used last time are the starting point
    LaunchedEffect(exercise) {
        previous = withContext(Dispatchers.IO) {
            Db.get(ctx).workouts(60).asSequence().map { Training.setsOf(it.extra).filter { s -> s.exercise == exercise } }.firstOrNull { it.isNotEmpty() } ?: emptyList()
        }
        val start = Training.sets.value.lastOrNull { it.exercise == exercise } ?: previous.maxByOrNull { it.volume }
        val e = Strength.byId(exercise)
        weight = start?.weight ?: if (e.bodyShare > 0) 0.0 else 20.0
        reps = start?.reps ?: 8
    }
    val e = Strength.byId(exercise)

    SectionCard("Log a set") {
        AppDropdown(list.map { it.name }, list.indexOfFirst { it.id == exercise }.coerceAtLeast(0), { exercise = list[it].id })
        Text(
            (e.primary + e.secondary).joinToString(", ") + (if (previous.isNotEmpty()) " · last time ${previous.joinToString { "${kg(it.weight)}×${it.reps}" }}" else ""),
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Stepper(if (e.bodyShare > 0) "Added kg" else "Weight kg", kg(weight), { weight = (weight - 2.5).coerceAtLeast(0.0) }, { weight += 2.5 }, Modifier.weight(1f))
            Stepper("Reps", "$reps", { reps = (reps - 1).coerceAtLeast(1) }, { reps = (reps + 1).coerceAtMost(100) }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = false, onClick = { weight = (weight - 1.0).coerceAtLeast(0.0) }, label = { Text("−1 kg") })
            FilterChip(selected = false, onClick = { weight += 1.0 }, label = { Text("+1 kg") })
            FilterChip(selected = false, onClick = { weight += 10.0 }, label = { Text("+10 kg") })
        }
        RaceButton("Add set", Modifier.fillMaxWidth().padding(top = 10.dp), enabled = weight > 0 || e.bodyShare > 0) {
            Training.addSet(Strength.SetEntry(exercise, weight, reps, (a.elapsedMs / 1000).toInt(), body))
        }

        val now by produceState(System.currentTimeMillis(), restUntil) { while (true) { value = System.currentTimeMillis(); delay(500) } }
        val left = ((restUntil - now) / 1000.0).roundToInt()
        if (restUntil > 0 && left > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Rest ${mmss(left)}", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, color = look.primary)
                FilterChip(selected = false, onClick = { Training.restUntil.value = restUntil + 15_000 }, label = { Text("+15 s") })
                FilterChip(selected = false, onClick = { Training.restUntil.value = 0 }, label = { Text("Skip") }, modifier = Modifier.padding(start = 8.dp))
            }
        } else FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rest", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.align(Alignment.CenterVertically))
            listOf(60, 90, 120, 180).forEach { s -> FilterChip(selected = restSeconds == s, onClick = { Training.restSeconds.value = s }, label = { Text(mmss(s)) }) }
        }
    }
    if (sets.isNotEmpty()) SectionCard("This session · ${sets.size} ${if (sets.size == 1) "set" else "sets"} · ${"%,d".format(Strength.volume(sets).roundToInt())} kg") {
        sets.indices.reversed().forEach { i ->
            val s = sets[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(26.dp), style = MaterialTheme.typography.bodySmall, color = look.dim)
                Text(Strength.byId(s.exercise).name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = look.text, maxLines = 1)
                Text("${if (s.weight > 0) "${kg(s.weight)} kg" else "body"} × ${s.reps}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = look.text)
                Text("✕", Modifier.clip(CircleShape).clickable { Training.removeSet(i) }.padding(horizontal = 12.dp, vertical = 4.dp), color = look.dim)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ intervals

@Composable
private fun IntervalPanel() {
    val look = LocalRace.current
    val i by Training.intervals.collectAsState()
    fun set(f: (Training.Intervals) -> Training.Intervals) { Training.intervals.value = f(Training.intervals.value) }
    SectionCard("Interval timer") {
        if (i.running) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (i.working) "WORK" else "REST", style = MaterialTheme.typography.titleMedium, color = if (i.working) look.primary else look.ok)
                Text(mmss(i.left), style = MaterialTheme.typography.displayLarge, color = look.text)
                Text("Round ${i.round} of ${i.rounds}", style = MaterialTheme.typography.bodyMedium, color = look.dim)
            }
            RaceButton("Stop intervals", Modifier.fillMaxWidth().padding(top = 10.dp), primary = false) { Training.stopIntervals() }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Stepper("Work s", "${i.work}", { set { it.copy(work = (it.work - 5).coerceAtLeast(5)) } }, { set { it.copy(work = it.work + 5) } }, Modifier.weight(1f))
                Stepper("Rest s", "${i.rest}", { set { it.copy(rest = (it.rest - 5).coerceAtLeast(5)) } }, { set { it.copy(rest = it.rest + 5) } }, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) { Stepper("Rounds", "${i.rounds}", { set { it.copy(rounds = (it.rounds - 1).coerceAtLeast(1)) } }, { set { it.copy(rounds = (it.rounds + 1).coerceAtMost(50)) } }, Modifier.fillMaxWidth(0.55f)) }
            Text("Total ${mmss(i.work * i.rounds + i.rest * (i.rounds - 1))}" + if (i.done > 0) " · done ${i.done} rounds" else "", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp))
            RaceButton("Start intervals", Modifier.fillMaxWidth().padding(top = 10.dp)) { Training.startIntervals() }
        }
    }
}

// ------------------------------------------------------------------------------------------------ swimming

@Composable
private fun SwimPanel(a: ActiveState) {
    val look = LocalRace.current
    val p by Training.pool.collectAsState()
    val seconds = (a.elapsedMs / 1000).toInt()
    SectionCard("Pool lengths") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(25, 33, 50).forEach { m -> FilterChip(selected = p.poolM == m, onClick = { Training.pool.value = p.copy(poolM = m) }, label = { Text("$m m") }) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            FigureColumn("Lengths", "${p.lengths}", "", Modifier.weight(1f))
            FigureColumn("Distance", "${p.lengths * p.poolM}", "m", Modifier.weight(1f))
            FigureColumn("Last length", if (p.lastLengthSec > 0) mmss(p.lastLengthSec) else "--", "", Modifier.weight(1f))
        }
        RaceButton("+ 1 length", Modifier.fillMaxWidth().padding(top = 12.dp).height(72.dp)) { Training.addLength(seconds, 1) }
        if (p.lengths > 0) RaceButton("Remove one", Modifier.fillMaxWidth().padding(top = 8.dp), primary = false) { Training.addLength(seconds, -1) }
        Text("Tap at the wall after each length (or add them when you get out). Keep the phone dry.", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp))
    }
}

// ------------------------------------------------------------------------------------------------ breathing

@Composable
private fun BreathingPanel() {
    val look = LocalRace.current
    var on by rememberSaveable { mutableStateOf(false) }
    SectionCard("Breathing guide") {
        if (on) {
            val size = remember { Animatable(0.35f) }
            var phase by remember { mutableStateOf("Breathe in") }
            LaunchedEffect(Unit) {
                while (true) {
                    phase = "Breathe in"; size.animateTo(1f, tween(4000, easing = LinearEasing))
                    phase = "Hold"; delay(2000)
                    phase = "Breathe out"; size.animateTo(0.35f, tween(6000, easing = LinearEasing))
                }
            }
            Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(180.dp)) {
                    drawCircle(look.panelHi, this.size.minDimension / 2)
                    drawCircle(look.primary.copy(alpha = 0.55f), this.size.minDimension / 2 * size.value)
                }
                Text(phase, style = MaterialTheme.typography.titleLarge, color = look.text)
            }
        } else Text("4 seconds in, 2 hold, 6 out. Slow breathing helps the heart rate settle.", style = MaterialTheme.typography.bodySmall, color = look.dim)
        RaceButton(if (on) "Stop guide" else "Start guide", Modifier.fillMaxWidth().padding(top = 8.dp), primary = false) { on = !on }
    }
}

// ------------------------------------------------------------------------------------------------ summary

/** Sets, lengths or rounds on a finished workout. */
@Composable
fun TrainingSummary(w: Workout) {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val x = remember(w.id) { Training.parse(w.extra) }
    val sets = remember(w.id) { Training.setsOf(w.extra) }
    if (sets.isNotEmpty()) {
        var earlier by remember(w.id) { mutableStateOf<List<Strength.SetEntry>>(emptyList()) }
        LaunchedEffect(w.id) {
            earlier = withContext(Dispatchers.IO) { Db.get(ctx).workouts(500).filter { it.start < w.start }.flatMap { Training.setsOf(it.extra) } }
        }
        SectionCard("Lifting") {
            Row(Modifier.fillMaxWidth()) {
                FigureColumn("Sets", "${sets.size}", "", Modifier.weight(1f))
                FigureColumn("Reps", "${sets.sumOf { it.reps }}", "", Modifier.weight(1f))
                FigureColumn("Volume", "%,d".format(Strength.volume(sets).roundToInt()), "kg", Modifier.weight(1.4f))
            }
            sets.groupBy { it.exercise }.forEach { (id, list) ->
                val best = Strength.best(list, id)
                val before = Strength.best(earlier, id)
                Column(Modifier.padding(top = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Strength.byId(id).name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = look.text)
                        if (best != null && before != null && best > before + 0.05) Text("New best", style = MaterialTheme.typography.labelMedium, color = look.ok)
                    }
                    Text(list.joinToString("  ·  ") { "${if (it.weight > 0) "${kg(it.weight)} kg" else "body"} × ${it.reps}" }, style = MaterialTheme.typography.bodySmall, color = look.text)
                    Text(
                        listOfNotNull(
                            "volume ${"%,d".format(Strength.volume(list).roundToInt())} kg",
                            best?.let { "estimated 1-rep max ${it.roundToInt()} kg" },
                            before?.let { "earlier best ${it.roundToInt()} kg" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = look.dim,
                    )
                }
            }
            Text(
                "Volume = weight × reps. For bodyweight moves your body's share is included. 1-rep max uses the Epley formula (sets of 12 reps or fewer).",
                style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(top = 12.dp),
            )
        }
        val muscles = Strength.muscleSets(sets)
        if (muscles.isNotEmpty()) SectionCard("Muscles worked (sets)") {
            val max = muscles.first().second
            muscles.forEach { (m, n) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m, Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall, color = look.text)
                    Box(Modifier.weight(1f).height(9.dp).clip(RoundedCornerShape(5.dp)).background(look.panelHi)) {
                        Box(Modifier.fillMaxWidth((n / max).toFloat()).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(look.primary))
                    }
                    Text(kg(n), Modifier.width(36.dp), style = MaterialTheme.typography.bodySmall, color = look.dim, textAlign = TextAlign.End)
                }
            }
        }
    }
    val lengths = x.optInt("lengths")
    if (lengths > 0) SectionCard("Swim") {
        val pool = x.optInt("poolM", 25)
        val meters = lengths * pool
        Row(Modifier.fillMaxWidth()) {
            FigureColumn("Lengths", "$lengths × $pool m", "", Modifier.weight(1.3f))
            FigureColumn("Distance", "$meters", "m", Modifier.weight(1f))
            FigureColumn("Per 100 m", mmss((w.movingMs / 1000.0 / meters * 100).roundToInt()), "", Modifier.weight(1f))
        }
    }
    val rounds = x.optInt("rounds")
    if (rounds > 0) SectionCard("Intervals") {
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth()) {
            FigureColumn("Rounds", "$rounds", "", Modifier.weight(1f))
            FigureColumn("Work", "${x.optInt("work")}", "s", Modifier.weight(1f))
            FigureColumn("Rest", "${x.optInt("rest")}", "s", Modifier.weight(1f))
        }
    }
}
