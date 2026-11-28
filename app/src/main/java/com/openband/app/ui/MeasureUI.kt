package com.openband.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ble.BandManager
import com.openband.app.ble.LiveKind
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.workout.WorkoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** What each on-demand measurement looks like and says. */
enum class MeasureKind(
    val page: String, val kind: LiveKind, val metric: String, val title: String, val unit: String, val color: Color,
    val hint: String, val seconds: Int,
) {
    HEART("measure-hr", LiveKind.HEART_RATE, M.HR, "Heart rate", "bpm", Color(0xFF4FD1B5), "Keep your hand still", 32),
    SPO2("measure-spo2", LiveKind.SPO2, M.SPO2, "Blood oxygen", "%", Color(0xFF5AA9FF), "Rest your arm and breathe normally", 35),
    HRV("measure-hrv", LiveKind.HRV, M.HRV, "Heart rate variability", "ms", Color(0xFF8BD65F), "Sit quietly and do not talk", 50),
    STRESS("measure-stress", LiveKind.STRESS, M.STRESS, "Stress", "/100", Color(0xFFB08CFF), "Relax your shoulders and breathe slowly", 50);

    companion object { fun of(page: String) = entries.firstOrNull { it.page == page } }
}

private sealed interface Phase {
    data object Connecting : Phase
    data object Measuring : Phase
    data class Done(val value: Int, val usual: Int?) : Phase
    data class Failed(val why: String) : Phase
}

private val INK = Color(0xFF070A0C)

/** True while a measurement page is showing: the window behind the status bar turns dark with it. */
object Immersive { var dark by mutableStateOf(false) }

/** Full-screen measurement: a living orb for the reading being taken, then the result. */
@Composable
fun MeasureScreen(what: MeasureKind, modifier: Modifier, onBack: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val view = LocalView.current
    val base = LocalRace.current
    // always dark, like a camera view, whatever the app's look is
    val dark = base.copy(text = Color.White, dim = Color.White.copy(alpha = 0.62f), panel = Color(0xFF12171B), panelHi = Color(0xFF1B2228), border = Color.White.copy(alpha = 0.16f), primary = what.color, light = false, inkButtons = false)
    var attempt by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<Phase>(Phase.Connecting) }
    var value by remember { mutableStateOf<Int?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        Immersive.dark = true
        view.keepScreenOn = true
        onDispose {
            Immersive.dark = false
            view.keepScreenOn = false
            if (BandManager.state.value.liveKind == what.kind) BandManager.stopLive()
        }
    }

    LaunchedEffect(attempt) {
        phase = Phase.Connecting; value = null; progress = 0f
        if (WorkoutManager.active.value != null) { phase = Phase.Failed("A workout is running. Finish it first, then measure."); return@LaunchedEffect }
        if (!BandManager.state.value.ready) BandManager.ensureConnected()
        // wait for the link, and let a running sync finish so the two do not talk over each other
        var waited = 0
        while (waited < 45_000 && (!BandManager.state.value.ready || BandManager.state.value.syncing)) { delay(250); waited += 250 }
        if (!BandManager.state.value.ready) {
            phase = Phase.Failed(
                if (BandManager.state.value.waitingOfficial) "Open the Pebble app once so it connects the band, then try again."
                else "The band is not connected. Check that Bluetooth is on and the band is near.",
            )
            return@LaunchedEffect
        }
        val begin = System.currentTimeMillis()
        BandManager.startLive(what.kind)
        phase = Phase.Measuring
        val got = ArrayList<Int>()
        var firstAt = 0L
        var lastAt = 0L
        // the heart rate settles over a few readings; the others arrive as one result
        val settle = if (what == MeasureKind.HEART) 7_000L else 2_000L
        while (true) {
            delay(200)
            val now = System.currentTimeMillis()
            val st = BandManager.state.value
            val r = st.reading
            if (r != null && r.kind == what.kind && r.at >= begin && r.at != lastAt) {
                lastAt = r.at
                if (firstAt == 0L) firstAt = now
                got += r.value.toInt()
                value = r.value.toInt()
            }
            val expected = what.seconds * 1000f
            progress = if (firstAt == 0L) 0.9f * (1f - exp(-(now - begin) / (expected * 0.6f))) else maxOf(progress, 0.9f + 0.1f * ((now - firstAt).toFloat() / settle))
            val finished = firstAt != 0L && now - firstAt >= settle
            val gaveUp = now - begin > 75_000
            if (finished || (gaveUp && got.isNotEmpty())) break
            if (got.isEmpty() && st.refusedAt >= begin) { BandManager.stopLive(); phase = Phase.Failed("The band could not measure. Wear it snugly, a finger above the wrist bone."); return@LaunchedEffect }
            if (gaveUp) { BandManager.stopLive(); phase = Phase.Failed("No reading arrived. Wear the band snugly, keep still and try again."); return@LaunchedEffect }
        }
        progress = 1f
        BandManager.stopLive()
        val final = got.sorted()[got.size / 2]
        val usual = withContext(Dispatchers.IO) { usualValue(Db.get(ctx).series(what.metric, begin - 14 * 86_400_000L, begin)) }
        value = final
        phase = Phase.Done(final, usual)
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalRace provides dark) {
        Column(
            modifier.fillMaxSize().background(Brush.verticalGradient(listOf(INK, lerp(INK, what.color, 0.14f), INK))).padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Text(what.title, Modifier.weight(1f).padding(end = 44.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            Spacer(Modifier.weight(0.5f))
            Box(Modifier.size(310.dp), contentAlignment = Alignment.Center) {
                Orb(what, value, progress, phase is Phase.Measuring || phase is Phase.Connecting)
                value?.let {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("$it", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                        Text(" ${what.unit}", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
            Spacer(Modifier.weight(0.4f))
            val (head, sub) = when (val p = phase) {
                Phase.Connecting -> "Connecting to your band" to "One moment"
                Phase.Measuring -> "Measuring ${what.title.lowercase()}" to what.hint
                is Phase.Done -> verdict(what, p.value, p.usual)
                is Phase.Failed -> "Could not measure" to p.why
            }
            Text(head, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Text(sub, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.66f), textAlign = TextAlign.Center)
            Spacer(Modifier.weight(0.5f))
            Row(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (phase is Phase.Done || phase is Phase.Failed) {
                    RaceButton("Measure again", Modifier.weight(1f), primary = false) { attempt++ }
                    RaceButton("Done", Modifier.weight(1f), onClick = onBack)
                } else RaceButton("Cancel", Modifier.fillMaxWidth(), primary = false, onClick = onBack)
            }
        }
    }
}

/** The middle one of the hourly averages: every hour counts once, however many readings it holds. */
fun usualValue(rows: List<com.openband.app.data.Sample>): Int? {
    val hours = rows.groupBy { it.ts / 3_600_000L }.values.map { h -> h.sumOf { it.value } / h.size }.sorted()
    return if (hours.size < 10) null else Math.round(hours[hours.size / 2]).toInt()
}

private fun verdict(what: MeasureKind, v: Int, usual: Int?): Pair<String, String> {
    val word = Palette.status(what.metric, v.toDouble(), usual?.toDouble())?.first
        ?: if (what == MeasureKind.HEART) when { v < 60 -> "Low and calm"; v <= 100 -> "In the resting range"; else -> "Raised" } else "Measured"
    val compare = usual?.let { "Your usual over two weeks is $it ${what.unit}. " } ?: ""
    val note = when (what) {
        MeasureKind.HEART -> "Resting adults are usually 60 to 100 bpm."
        MeasureKind.SPO2 -> "95 % and above is typical."
        MeasureKind.HRV -> "Higher than your usual often means better recovery."
        MeasureKind.STRESS -> "Below 30 is relaxed, above 80 is high."
    }
    return word to compare + note + " Saved."
}

/** The animated centre. Each reading moves in its own way. */
@Composable
private fun Orb(what: MeasureKind, value: Int?, progress: Float, working: Boolean) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = (it - start) / 1e9f }
    }
    val arc = remember { Animatable(0f) }
    LaunchedEffect(progress) { arc.animateTo(progress, tween(260, easing = LinearEasing)) }
    val appear = remember { Animatable(0.7f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension * 0.34f * appear.value
        val color = what.color
        // progress arc, and a short sweep that circles while waiting
        val ring = size.minDimension * 0.46f
        val box = Offset(c.x - ring, c.y - ring)
        val ringSize = Size(ring * 2, ring * 2)
        drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, box, ringSize, style = Stroke(3.dp.toPx()))
        drawArc(color, -90f, 360f * arc.value, false, box, ringSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        if (working) drawArc(Color.White.copy(alpha = 0.55f), time * 130f % 360f - 90f, 18f, false, box, ringSize, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        when (what) {
            MeasureKind.HEART -> heartOrb(c, r, color, time, value?.toFloat() ?: 66f)
            MeasureKind.SPO2 -> oxygenOrb(c, r, color, time, progress)
            MeasureKind.HRV -> variabilityOrb(c, r, color, time, below = value != null)
            MeasureKind.STRESS -> stressOrb(c, r, color, time, value)
        }
    }
}

private fun DrawScope.halo(c: Offset, r: Float, color: Color) =
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent), center = c, radius = r * 1.5f), r * 1.5f, c)

/** Beats at the measured rate: two quick pushes, then a ripple leaving the orb. */
private fun DrawScope.heartOrb(c: Offset, r: Float, color: Color, time: Float, bpm: Float) {
    val beat = (time * bpm / 60f) % 1f
    fun bump(at: Float) = exp(-((beat - at) * (beat - at)) / 0.0035f)
    val push = 1f + 0.09f * bump(0.12f) + 0.055f * bump(0.32f)
    halo(c, r * push, color)
    for (i in 0..1) {
        val age = (beat + i * 0.5f) % 1f
        drawCircle(color.copy(alpha = 0.35f * (1f - age)), r * (1.02f + age * 0.32f), c, style = Stroke(1.5.dp.toPx()))
    }
    drawCircle(Brush.radialGradient(listOf(lerp(color, Color.White, 0.25f).copy(alpha = 0.65f), color.copy(alpha = 0.28f)), center = c, radius = r * push), r * push, c)
    drawCircle(Brush.radialGradient(listOf(lerp(color, Color.White, 0.35f), color.copy(alpha = 0.75f), color.copy(alpha = 0.1f)), center = c, radius = r * 0.66f * push), r * 0.66f * push, c)
}

/** A round vessel filling with liquid as the measurement goes on, bubbles rising through it. */
private fun DrawScope.oxygenOrb(c: Offset, r: Float, color: Color, time: Float, progress: Float) {
    halo(c, r, color)
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
    drawCircle(color.copy(alpha = 0.14f), r, c)
    clipPath(circle) {
        val top = c.y + r - 2 * r * (0.18f + 0.64f * progress)
        fun wave(shift: Float, speed: Float, alpha: Float) {
            val p = Path()
            p.moveTo(c.x - r, c.y + r)
            var x = c.x - r
            while (x <= c.x + r + 8f) {
                val u = (x - c.x) / r
                p.lineTo(x, top + sin(u * 3.4f + time * speed + shift) * r * 0.055f)
                x += 8f
            }
            p.lineTo(c.x + r, c.y + r); p.close()
            drawPath(p, color.copy(alpha = alpha))
        }
        wave(2.2f, 1.7f, 0.35f)
        wave(0f, 2.5f, 0.62f)
        for (i in 0 until 14) {
            val u = (i * 0.618034f) % 1f
            val rise = (time * (0.1f + (i % 4) * 0.035f) + i * 0.29f) % 1f
            val y = c.y + r - (c.y + r - top) * rise
            val x = c.x - r * 0.8f + u * r * 1.6f + sin(time * 2f + i) * 4.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.4f * (1f - rise)), (2 + i % 4).dp.toPx(), Offset(x, y), style = Stroke(1.2.dp.toPx()))
        }
    }
    drawCircle(color.copy(alpha = 0.7f), r, c, style = Stroke(2.dp.toPx()))
}

/** A heartbeat trace with uneven gaps running across the orb: the unevenness is what is being measured. */
private fun DrawScope.variabilityOrb(c: Offset, r: Float, color: Color, time: Float, below: Boolean) {
    halo(c, r, color)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0.10f)), center = c, radius = r), r, c)
    drawCircle(color.copy(alpha = 0.55f), r, c, style = Stroke(1.5.dp.toPx()))
    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
    clipPath(circle) {
        val gaps = floatArrayOf(1.0f, 0.82f, 1.15f, 0.9f, 1.25f, 0.95f)
        val cycle = gaps.sum()
        val unit = r * 0.8f
        val p = Path()
        var first = true
        var x = -r
        while (x <= r) {
            var t = ((x / unit + time * 0.9f) % cycle + cycle) % cycle
            var i = 0
            while (t > gaps[i]) { t -= gaps[i]; i++ }
            fun spike(at: Float, w: Float) = exp(-((t - at) * (t - at)) / (w * w))
            val y = spike(0.30f, 0.025f) - 0.35f * spike(0.37f, 0.025f) - 0.2f * spike(0.23f, 0.02f) + 0.18f * spike(0.62f, 0.08f)
            // once there is a number, the trace moves under it and gets smaller
            val py = if (below) c.y + r * 0.62f - y * r * 0.22f else c.y + r * 0.46f - y * r * 0.42f
            if (first) { p.moveTo(c.x + x, py); first = false } else p.lineTo(c.x + x, py)
            x += 3f
        }
        drawPath(p, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Soft shapes that wobble and settle into calm circles. */
private fun DrawScope.stressOrb(c: Offset, r: Float, color: Color, time: Float, value: Int?) {
    halo(c, r, color)
    val restless = value?.let { 0.02f + 0.09f * min(1f, it / 100f) } ?: 0.075f
    for (layer in 0..2) {
        val p = Path()
        val scale = 1f - layer * 0.17f
        for (step in 0..120) {
            val a = step / 120f * 2f * PI.toFloat()
            val wob = 1f + restless * (sin(a * 3 + time * (1.3f + layer * 0.4f)) * 0.6f + sin(a * 5 - time * (0.9f + layer * 0.3f)) * 0.4f)
            val pt = Offset(c.x + cos(a) * r * scale * wob, c.y + sin(a) * r * scale * wob)
            if (step == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        p.close()
        drawPath(p, color.copy(alpha = 0.20f + layer * 0.14f))
        drawPath(p, color.copy(alpha = 0.5f), style = Stroke(1.2.dp.toPx()))
    }
}
