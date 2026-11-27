package com.openband.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colour for a status tone: 0 good, 1 worth a note, 2 needs attention, anything else neutral. */
@Composable
fun toneColor(tone: Int): Color {
    val look = LocalRace.current
    return when (tone) { 0 -> look.ok; 1 -> look.warn; 2 -> look.danger; else -> look.dim }
}

/** Flat progress ring: a quiet track and one solid arc. No glow, no gradient. */
@Composable
fun StudioRing(fraction: Float?, color: Color, size: Dp, stroke: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val look = LocalRace.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val f by animateFloatAsState(if (shown) (fraction ?: 0f).coerceIn(0f, 1f) else 0f, tween(900, easing = FastOutSlowInEasing), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val sz = Size(this.size.width - w, this.size.height - w)
            if (look.isConsole) {
                // segmented dial
                val n = 36
                for (k in 0 until n) drawArc(if (k < f * n) color else look.panelHi, -90f + 360f * k / n, 360f / n * 0.62f, false, Offset(inset, inset), sz, style = Stroke(w))
            } else {
                drawArc(look.panelHi, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
                if (f > 0.004f) drawArc(color, -90f, 360f * f, false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        content()
    }
}

/** Small section label with the app's consistent spacing above and below. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val look = LocalRace.current
    Row(modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(look.style.prompt + themedText(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = if (look.style.prompt.isEmpty()) look.text else look.primary)
        trailing?.invoke()
    }
}

/**
 * The last seven days as bars, the same on every card: today solid, earlier days lighter, a grey stub where nothing
 * was measured. Metrics that never go near zero (SpO2, temperature...) are scaled from just below their lowest day so
 * the differences stay visible.
 */
@Composable
fun WeekBars(values: List<Double?>, color: Color, fromZero: Boolean, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    val today = java.time.LocalDate.now()
    val list = if (values.size == 7) values else List(7) { values.getOrNull(values.size - 7 + it) }
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val present = list.filterNotNull()
            val mx = present.maxOrNull() ?: 1.0
            val mn = present.minOrNull() ?: 0.0
            val floor = if (fromZero) 0.0 else mn - maxOf((mx - mn) * 0.8, kotlin.math.abs(mx) * 0.02, 0.5)
            val span = (mx - floor).takeIf { it > 1e-9 } ?: 1.0
            val slot = size.width / 7f
            val bw = (slot * 0.52f).coerceAtMost(14.dp.toPx())
            list.forEachIndexed { i, v ->
                val x = slot * i + (slot - bw) / 2
                val h = if (v == null) 3.dp.toPx() else (((v - floor) / span).toFloat() * size.height).coerceIn(5.dp.toPx(), size.height)
                val c = if (v == null) look.panelHi else if (i == 6) color else color.copy(alpha = 0.32f)
                themedBar(look, c, x, size.height - h, bw, size.height, if (look.style == UiStyle.MONO) 0f else bw / 3f)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            for (i in 0..6) Text(
                today.minusDays((6 - i).toLong()).dayOfWeek.name.take(1),
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (i == 6) look.text else look.dim.copy(alpha = 0.7f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * One measurement: icon, name, big value, a plain-language reading and the last seven days.
 * Every card has exactly the same rows so the grid lines up and the eye learns where to look.
 */
@Composable
fun MetricCard(
    id: String, name: String, value: String, unit: String, status: Pair<String, Int>?, time: String?, trend: List<Double?>?,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val look = LocalRace.current
    val col = metricColor(id, look)
    val shape = look.panelShape
    Column(
        modifier.reveal().clip(shape).background(look.panel).border(1.dp, look.border, shape).pressable(onClick = onClick).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetricIcon(Palette.icon(id), col, 30.dp)
            Text(name, Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.labelMedium, color = look.dim, maxLines = 1)
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = look.text, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1)
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status != null) Box(Modifier.padding(end = 6.dp).size(7.dp).clip(CircleShape).background(toneColor(status.second)))
            Text(status?.first ?: time ?: " ", style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text(if (status != null) time ?: " " else " ", Modifier.padding(top = 1.dp), style = MaterialTheme.typography.labelSmall, color = look.dim.copy(alpha = 0.75f), maxLines = 1)
        WeekBars(trend ?: emptyList(), col, id == INST_SLEEP, Modifier.padding(top = 8.dp))
    }
}

/** A labelled figure used in the hero card footer. */
@Composable
fun FigureColumn(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = look.dim, maxLines = 1)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = look.text, maxLines = 1)
            if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 3.dp, bottom = 2.dp), style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1)
        }
    }
}
