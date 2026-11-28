package com.openband.app.ui

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ai.AiClient
import com.openband.app.ai.AiSettings
import com.openband.app.ai.Verify
import com.openband.app.data.BodyAge
import com.openband.app.data.Db
import com.openband.app.data.Insights
import com.openband.app.data.Story
import com.openband.app.data.WeeklyRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Weeks between today's week and the one the Sunday recap is about. */
fun recapWeeksBack(): Int {
    val monday = WeeklyRecap.shownWeek(LocalDateTime.now()).minusDays(6)
    return ChronoUnit.WEEKS.between(monday, Insights.range(false, 0).first).toInt().coerceAtLeast(0)
}

/** Choose which week or month to watch. */
@Composable
fun StoryHubScreen(modifier: Modifier, onBack: () -> Unit) {
    val look = LocalRace.current
    val open = LocalOpenMetric.current
    val short = DateTimeFormatter.ofPattern("d MMM")
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        PageHeader("Your stories", "A week or a month, card by card", onBack)
        SectionCard("Weeks") {
            (0..3).forEach { back ->
                val (from, to) = Insights.range(false, back)
                val name = when (back) { 0 -> "This week so far"; 1 -> "Last week"; else -> "$back weeks ago" }
                LinkRow(AppIcons.Trends, look.primary, name, "${short.format(from)} – ${short.format(to)}", null) { open("page:story-week:$back") }
            }
        }
        SectionCard("Months") {
            (0..2).forEach { back ->
                val from = Insights.range(true, back).first
                val name = DateTimeFormatter.ofPattern("MMMM yyyy").format(from)
                LinkRow(AppIcons.Target, look.primary, name, if (back == 0) "This month so far" else null, null) { open("page:story-month:$back") }
            }
        }
        SectionCard(null) {
            LinkRow(AppIcons.Gauge, look.primary, "All numbers", "The detailed table, for any week or month", null) { open("page:numbers") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private class StoryLoad(val deck: Story.Deck?)

private val INK = Color(0xFF0B0D10)

/** One colour per subject; deep enough for white text. */
private fun tint(kind: Story.Kind) = when (kind) {
    Story.Kind.COVER -> Color(0xFFD9480F)
    Story.Kind.STEPS -> Color(0xFFC2571B)
    Story.Kind.BEST -> Color(0xFFB07A0F)
    Story.Kind.ENERGY -> Color(0xFFA8341E)
    Story.Kind.SLEEP -> Color(0xFF3B4C9B)
    Story.Kind.HEART -> Color(0xFFA92458)
    Story.Kind.BODY -> Color(0xFF6A3FA0)
    Story.Kind.WORKOUTS -> Color(0xFF14684A)
    Story.Kind.SCORE -> Color(0xFF0F6E7A)
    Story.Kind.REVIEW -> Color(0xFF25406B)
    Story.Kind.NEXT -> Color(0xFF1F6B3A)
}

private const val SLIDE_SECONDS = 7f

/**
 * The story itself, like a phone status: bars along the top, tap right for the next card and left for the previous one,
 * hold to pause.
 */
@Composable
fun StoryScreen(monthly: Boolean, back: Int, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val view = LocalView.current
    val base = LocalRace.current
    val p = base.prefs
    var loaded by remember(monthly, back) { mutableStateOf<StoryLoad?>(null) }
    var index by rememberSaveable(monthly, back) { mutableStateOf(0) }
    var held by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }

    DisposableEffect(Unit) {
        Immersive.dark = true
        view.keepScreenOn = true
        onDispose { Immersive.dark = false; view.keepScreenOn = false }
    }
    LaunchedEffect(monthly, back, p.stepGoal, p.sleepGoalMin) {
        loaded = StoryLoad(
            withContext(Dispatchers.IO) {
                runCatching {
                    val db = Db.get(ctx)
                    Story.load(db, monthly, back, Story.Goals(p.stepGoal, p.sleepGoalMin, p.imperial), { BodyAge.fromHistory(db, p.age, p.heightCm, p.weightKg)?.bodyAge })
                }.getOrNull()
            },
        )
    }

    val dark = base.copy(text = Color.White, dim = Color.White.copy(alpha = 0.68f), panel = Color.White.copy(alpha = 0.10f), panelHi = Color.White.copy(alpha = 0.16f), border = Color.White.copy(alpha = 0.22f), primary = Color.White, light = false, inkButtons = false)
    val deck = loaded?.deck
    val slides = deck?.slides.orEmpty()
    val at = index.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
    val slide = slides.getOrNull(at)
    val background = slide?.let { tint(it.kind) } ?: Color(0xFF25406B)
    // the written review is read at the reader's pace, and the last card stays
    val auto = slide != null && slide.kind != Story.Kind.REVIEW && at < slides.lastIndex
    val running by rememberUpdatedState(auto && !held)
    LaunchedEffect(at, slides.size) {
        progress = 0f
        var last = withFrameNanos { it }
        while (progress < 1f) withFrameNanos { now ->
            if (running) progress = (progress + (now - last) / 1e9f / SLIDE_SECONDS).coerceAtMost(1f)
            last = now
        }
        if (at < slides.lastIndex) index = at + 1
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalRace provides dark) {
        Column(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerp(background, INK, 0.25f), lerp(background, INK, 0.72f))))) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(slides.size) { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.28f))) {
                        val filled = if (i < at) 1f else if (i > at) 0f else if (auto) progress else 1f
                        Box(Modifier.fillMaxWidth(filled).fillMaxHeight().background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (monthly) "Monthly story" else "Weekly story", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White)
                Box(Modifier.size(44.dp).clip(CircleShape).pressable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Close, "Close", Modifier.size(22.dp), tint = Color.White)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().pointerInput(slides.size) {
                    detectTapGestures(
                        onPress = { held = true; tryAwaitRelease(); held = false },
                        // a long hold only pauses; letting go does not turn the page
                        onLongPress = {},
                        onTap = { pos -> index = if (pos.x < size.width * 0.32f) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost((slides.size - 1).coerceAtLeast(0)) },
                    )
                },
            ) {
                when {
                    loaded == null -> Centered("Making your story…")
                    slide == null -> Centered("Nothing was recorded in this ${if (monthly) "month" else "week"}.")
                    else -> androidx.compose.runtime.key(at) { SlideView(slide, deck!!) }
                }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (slide != null && at == slides.lastIndex) {
                    RaceButton("Watch again", Modifier.weight(1f), primary = false) { index = 0 }
                    RaceButton("Done", Modifier.weight(1f), primary = false, onClick = onBack)
                } else Text(
                    if (slide == null) "" else "Tap for the next card · hold to pause",
                    Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.55f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Centered(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun SlideView(s: Story.Slide, deck: Story.Deck) {
    // everything on a card arrives together, bars growing as it does
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
    val t = enter.value
    BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 8.dp)
            .graphicsLayer { alpha = t; translationY = (1f - t) * 40.dp.toPx() },
        verticalArrangement = Arrangement.Center,
    ) {
        Text(s.kicker.uppercase(), style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp), color = Color.White.copy(alpha = 0.7f))
        Text(s.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        s.big?.let { big ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                val size = when { big.length >= 8 -> 48.sp; big.length >= 6 -> 58.sp; else -> 76.sp }
                Text(big, fontSize = size, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, softWrap = false)
                if (s.unit.isNotEmpty()) Text(" ${s.unit}", Modifier.padding(bottom = if (big.length >= 6) 10.dp else 14.dp), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, softWrap = false)
            }
        }
        if (s.kind == Story.Kind.REVIEW) Review(s, deck)
        else if (s.note.isNotEmpty()) Text(s.note, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.82f))
        if (s.bars.count { it.value != null } >= 2) Bars(s.bars, s.goal, t)
        if (s.lines.isNotEmpty()) Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.lines.forEach { line ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.11f)).padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (line.tone >= 0) Box(Modifier.padding(end = 10.dp).size(8.dp).clip(CircleShape).background(if (line.tone == 0) Color(0xFF7BE0A3) else Color(0xFFFFC46B)))
                        Text(line.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f))
                        Text(line.value, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                    if (line.note.isNotEmpty()) Text(line.note, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
                    // report card: each score also as a bar
                    if (s.kind == Story.Kind.SCORE) Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
                        Box(Modifier.fillMaxWidth((line.value.toIntOrNull() ?: 0) / 100f * t).fillMaxHeight().background(Color.White))
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Bars(bars: List<Story.Bar>, goal: Double?, grow: Float) {
    val max = (listOfNotNull(bars.mapNotNull { it.value }.maxOrNull(), goal).maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val slot = size.width / bars.size
            val width = slot * 0.62f
            val best = bars.mapNotNull { it.value }.maxOrNull()
            bars.forEachIndexed { i, b ->
                val x = i * slot + (slot - width) / 2
                val h = b.value?.let { (it / max * size.height * grow).toFloat().coerceAtLeast(3.dp.toPx()) } ?: 3.dp.toPx()
                val color = Color.White.copy(alpha = if (b.value == null) 0.18f else if (b.value == best) 1f else 0.58f)
                drawRoundRect(color, Offset(x, size.height - h), Size(width, h), CornerRadius(width / 4))
            }
            goal?.let {
                val y = size.height - (it / max * size.height).toFloat()
                drawLine(Color.White.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            bars.forEach { Text(it.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f), maxLines = 1, softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

private class Written(val text: String, val note: String)

/** The AI's evaluation when a service is connected and allowed; otherwise (or meanwhile) the phone's own. */
@Composable
private fun Review(s: Story.Slide, deck: Story.Deck) {
    val ctx = LocalContext.current.applicationContext
    val open = LocalOpenMetric.current
    val ai = remember { AiSettings(ctx) }
    val use = remember { ai.ready && ai.consent }
    var written by remember { mutableStateOf(if (use) null else Written(s.note, "Worked out on your phone")) }
    LaunchedEffect(deck.facts) { if (use) written = write(ctx, ai, deck, s.note) }
    val w = written
    Column(Modifier.padding(top = 16.dp)) {
        if (w == null) Text("The AI is reading your ${if (deck.monthly) "month" else "week"}…", style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.75f))
        else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.11f)).padding(18.dp)) {
                Text(w.text, style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
            Text(w.note, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            if (!use) RaceButton("Connect AI for a written review", Modifier.fillMaxWidth().padding(top = 12.dp), primary = false) { open("page:coach") }
        }
    }
}

/** Asks once per story and keeps the answer; a running week is asked again when its figures change, at most every 6 hours. */
private suspend fun write(ctx: Context, ai: AiSettings, deck: Story.Deck, fallback: String): Written {
    val sp = ctx.getSharedPreferences("story", Context.MODE_PRIVATE)
    val key = "${if (deck.monthly) "m" else "w"}:${deck.from}:${ai.language}"
    val old = sp.getString(key, null)
    val fresh = sp.getInt("$key.facts", 0) == deck.facts.hashCode() || System.currentTimeMillis() - sp.getLong("$key.at", 0) < 6 * 3_600_000L
    if (old != null && fresh) return Written(old, "Written by AI")
    val period = if (deck.monthly) "month" else "week"
    val language = if (ai.language == "Gujarati") "simple everyday Gujarati (Gujarati script), numbers in digits 0-9" else "${ai.language}, numbers in digits 0-9"
    val system = "You are a warm, practical fitness coach writing the review card of a user's $period in their band app. " +
        "Write three short paragraphs: how the $period went overall; what was good; the one or two most useful changes for next $period. " +
        "Use only the figures given, copied exactly. No diagnosis, no greeting, no headings, no lists. Under 90 words. Reply in $language.\n\nDATA:\n${deck.facts}"
    return try {
        val reply = AiClient.chat(ai, listOf(AiClient.Turn("system", system), AiClient.Turn("user", "Review my $period."))).first
        // an answer quoting figures that are not in the data is not shown
        if (Verify.unsupported(reply, system).isNotEmpty()) Written(fallback, "The AI answer did not match your figures, so this was worked out on your phone")
        else {
            sp.edit().putString(key, reply).putInt("$key.facts", deck.facts.hashCode()).putLong("$key.at", System.currentTimeMillis()).apply()
            Written(reply, "Written by AI")
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        if (old != null) Written(old, "Written by AI earlier")
        else Written(fallback, "The AI could not answer (${(e.message ?: "no connection").lineSequence().first()}), so this was worked out on your phone")
    }
}
