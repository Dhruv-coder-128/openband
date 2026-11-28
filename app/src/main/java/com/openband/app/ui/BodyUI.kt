package com.openband.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.data.BodyAge
import com.openband.app.data.BodyEnergy
import com.openband.app.data.Db
import com.openband.app.detect.ActivitySegments
import com.openband.app.detect.Detected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** null inside = not enough data yet; the outer null = still working it out. */
private class Loaded<T>(val value: T?, val day: java.time.LocalDate = java.time.LocalDate.now())

// shown at once when the screen comes back, while a fresh figure is worked out
private var lastEnergy: Loaded<BodyEnergy.Result>? = null
private var lastAge: Loaded<BodyAge.Result>? = null

@Composable
private fun rememberEnergy(): Loaded<BodyEnergy.Result>? {
    val ctx = LocalContext.current.applicationContext
    val p = LocalRace.current.prefs
    val ver by Db.changes.collectAsState()
    var out by remember { mutableStateOf(lastEnergy?.takeIf { it.day == java.time.LocalDate.now() }) }
    LaunchedEffect(ver, p.sleepGoalMin, p.age) {
        out = Loaded(withContext(Dispatchers.IO) { runCatching { BodyEnergy.today(Db.get(ctx), p.sleepGoalMin, p.maxHr) }.getOrNull() }).also { lastEnergy = it }
    }
    return out
}

@Composable
private fun rememberBodyAge(): Loaded<BodyAge.Result>? {
    val ctx = LocalContext.current.applicationContext
    val p = LocalRace.current.prefs
    val day = java.time.LocalDate.now()
    var out by remember { mutableStateOf(lastAge?.takeIf { it.day == day }) }
    // looks only at finished days, so once per day (or profile change) is enough
    LaunchedEffect(day, p.age, p.heightCm, p.weightKg) {
        out = Loaded(withContext(Dispatchers.IO) { runCatching { BodyAge.fromHistory(Db.get(ctx), p.age, p.heightCm, p.weightKg) }.getOrNull() }).also { lastAge = it }
    }
    return out
}

/** Two Home tiles: energy left today, and body age. */
@Composable
fun BodyCards() {
    val look = LocalRace.current
    val open = LocalOpenMetric.current
    val energy = rememberEnergy()
    val age = rememberBodyAge()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard(null, Modifier.weight(1f).fillMaxHeight().clickable { open("page:energy") }) {
            val e = energy?.value
            Text("Body energy", style = MaterialTheme.typography.labelMedium, color = look.dim, maxLines = 1)
            Text(e?.now?.toString() ?: "--", style = MaterialTheme.typography.headlineMedium, color = e?.let { toneColor(it.tone) } ?: look.text, modifier = Modifier.padding(top = 4.dp))
            Text(e?.label ?: if (energy == null) "…" else "Needs last night's sleep", style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 2)
        }
        SectionCard(null, Modifier.weight(1f).fillMaxHeight().clickable { open("page:bodyage") }) {
            val a = age?.value
            Text("Body age", style = MaterialTheme.typography.labelMedium, color = look.dim, maxLines = 1)
            Text(a?.bodyAge?.toString() ?: "--", style = MaterialTheme.typography.headlineMedium, color = a?.let { if (it.difference <= 0) look.ok else look.warn } ?: look.text, modifier = Modifier.padding(top = 4.dp))
            Text(
                a?.let { if (it.difference == 0) "Same as your age" else "${abs(it.difference)} yr ${if (it.difference < 0) "younger" else "older"}" }
                    ?: if (age == null) "…" else "Collecting data",
                style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 2,
            )
        }
    }
}

@Composable
private fun Page(modifier: Modifier, title: String, sub: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        PageHeader(title, sub, onBack)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun EnergyScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val loaded = rememberEnergy()
    Page(modifier, "Body energy", "How much you have left today", onBack) {
        val e = loaded?.value
        if (e == null) {
            SectionCard(null) {
                Text(
                    if (loaded == null) "Working it out…" else "Body energy starts from last night's sleep. Wear the band tonight and look again in the morning.",
                    style = MaterialTheme.typography.bodyMedium, color = look.dim,
                )
            }
        } else {
            SectionCard(null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StudioRing(e.now / 100f, toneColor(e.tone), 104.dp, 11.dp) { Text("${e.now}", style = MaterialTheme.typography.headlineMedium, color = look.text) }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(e.label, style = MaterialTheme.typography.titleLarge, color = look.text)
                        Text(e.advice, style = MaterialTheme.typography.bodySmall, color = look.dim)
                    }
                }
            }
            if (e.points.size > 2) SectionCard("Since you woke up") {
                LineChart(e.points.map { it.at to it.level.toDouble() }, e.points.first().at, e.points.last().at, color = toneColor(e.tone), height = 150.dp, unit = "")
            }
            SectionCard("What changed it") {
                Row(Modifier.fillMaxWidth()) {
                    FigureColumn("On waking", "${e.atWake}", "", Modifier.weight(1f))
                    FigureColumn("Activity", "−${e.usedByActivity}", "", Modifier.weight(1f))
                    FigureColumn("Stress", "−${e.usedByStress}", "", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    FigureColumn("Being awake", "−${e.usedByBeingAwake}", "", Modifier.weight(1f))
                    FigureColumn("Calm rest", "+${e.gainedByRest}", "", Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(
            "OpenBand's own estimate from your sleep, recovery, heart rate, steps and stress. It is a guide, not a medical measurement, and not the formula of any other band.",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
fun BodyAgeScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val loaded = rememberBodyAge()
    Page(modifier, "Body age", "From your last ${BodyAge.DAYS} days", onBack) {
        val a = loaded?.value
        if (a == null) {
            SectionCard(null) {
                Text(
                    if (loaded == null) "Working it out…" else "Body age needs at least three days of band data for ${BodyAge.MIN_FACTORS} things such as sleep, steps and resting heart rate. Keep wearing the band.",
                    style = MaterialTheme.typography.bodyMedium, color = look.dim,
                )
            }
        } else {
            SectionCard(null) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${a.bodyAge}", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = if (a.difference <= 0) look.ok else look.warn)
                    Text(a.headline, style = MaterialTheme.typography.titleMedium, color = look.text)
                    Text("Your age: ${a.age}" + if (a.days < 14) " · early estimate from ${a.days} days" else "", style = MaterialTheme.typography.bodySmall, color = look.dim)
                }
            }
            a.focus?.let { f ->
                SectionCard("Best thing to work on") {
                    Text("${f.name}: ${f.value}", style = MaterialTheme.typography.bodyMedium, color = look.text, fontWeight = FontWeight.SemiBold)
                    Text(f.tip, style = MaterialTheme.typography.bodySmall, color = look.dim)
                }
            }
            SectionCard("What counts") {
                a.factors.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, color = look.text)
                            Text(f.value, style = MaterialTheme.typography.bodySmall, color = look.dim)
                        }
                        val y = Math.round(f.years * 10) / 10.0
                        Text(
                            if (y == 0.0) "0 yr" else "%+.1f yr".format(y),
                            style = MaterialTheme.typography.titleMedium, color = if (y < 0) look.ok else if (y > 0) look.warn else look.dim,
                        )
                    }
                }
            }
        }
        Text(
            "Age ${p.age}, ${p.heightCm} cm, ${p.weightKg} kg are used. Change them in Settings → Goals & units. This is OpenBand's own estimate for motivation, not a medical test and not Pebble's formula.",
            style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Home: activities the phone noticed that were not recorded. */
@Composable
fun DetectedCard() {
    val look = LocalRace.current
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val ver by Detected.changes.collectAsState()
    var list by remember { mutableStateOf<List<ActivitySegments.Segment>>(emptyList()) }
    LaunchedEffect(ver) { list = withContext(Dispatchers.IO) { runCatching { Detected.check(ctx); Detected.pending(ctx) }.getOrDefault(emptyList()) } }
    list.take(2).forEach { s ->
        var choice by remember(s.start) { mutableStateOf(s.sport) }
        SectionCard(null) {
            Text("Did you do this activity?", style = MaterialTheme.typography.titleMedium, color = look.text)
            Text(Detected.describe(s) + " · noticed by your phone", style = MaterialTheme.typography.bodySmall, color = look.dim)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ActivitySegments.WALK to "Walk", ActivitySegments.RUN to "Run", ActivitySegments.BIKE to "Ride").forEach { (id, label) ->
                    FilterChip(selected = choice == id, onClick = { choice = id }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RaceButton("Yes, save it", Modifier.weight(1f)) { scope.launch(Dispatchers.IO) { Detected.save(ctx, s, choice) } }
                RaceButton("No", Modifier.weight(1f), primary = false) { scope.launch(Dispatchers.IO) { Detected.dismiss(ctx, s) } }
            }
        }
    }
}
