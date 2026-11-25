package com.openband.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Everything a gauge needs to draw one metric.
 * [fraction] 0..1 position of the needle (null = no data).
 * [dangerFrom] fraction where the red zone starts (or ends, if [dangerLow]).
 */
data class GaugeSpec(
    val label: String,
    val value: String,
    val unit: String,
    val fraction: Float?,
    val minLabel: String = "",
    val maxLabel: String = "",
    val dangerFrom: Float? = null,
    val dangerLow: Boolean = false,
    val sub: String? = null,
    val ticks: List<String> = emptyList(),
)

/** Animated needle position. Returned as State so it is read only while drawing (no recomposition per frame). */
@Composable
private fun animatedFractionState(f: Float?): State<Float> {
    val look = LocalRace.current
    val target = (f ?: 0f).coerceIn(0f, 1f)
    return animateFloatAsState(
        target,
        animationSpec = if (look.prefs.animate) spring(dampingRatio = 0.6f, stiffness = 140f) else snap(),
        label = "gauge",
    )
}

@Composable
private fun animatedFraction(f: Float?): Float = animatedFractionState(f).value

private fun inDanger(s: GaugeSpec): Boolean {
    val f = s.fraction ?: return false
    val d = s.dangerFrom ?: return false
    return if (s.dangerLow) f <= d else f >= d
}

@Composable
fun Gauge(spec: GaugeSpec, modifier: Modifier = Modifier, big: Boolean = false) {
    when (LocalRace.current.prefs.gaugeStyle) {
        GaugeStyle.ANALOG -> AnalogGauge(spec, modifier, big)
        GaugeStyle.SPORT -> SportGauge(spec, modifier, big)
        GaugeStyle.DIGITAL -> DigitalGauge(spec, modifier, big)
    }
}

// --------------------------------------------------------------------------------------------- analog

private const val A_START = 135f
private const val A_SWEEP = 270f

@Composable
fun AnalogGauge(spec: GaugeSpec, modifier: Modifier = Modifier, big: Boolean = false) {
    val look = LocalRace.current
    val measurer = rememberTextMeasurer()
    val frac = animatedFractionState(spec.fraction)
    val danger = inDanger(spec)
    val valueStyle = TextStyle(
        fontFamily = look.display, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
        fontSize = if (big) 28.sp else 19.sp, color = if (danger) look.danger else look.text, textAlign = TextAlign.Center,
    )
    val unitStyle = TextStyle(fontFamily = look.body, fontWeight = FontWeight.Bold, fontSize = if (big) 12.sp else 10.sp, color = look.dim)
    val labelStyle = TextStyle(fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = if (big) 10.sp else 8.sp, color = look.primary, letterSpacing = 1.sp)
    val tickStyle = TextStyle(fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = if (big) 10.sp else 7.sp, color = look.dim)

    Box(modifier.aspectRatio(1f)) {
        // ---- static layer: own render layer, only redrawn when the spec/theme changes
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2, size.height / 2)
            drawCircle(Brush.sweepGradient(listOf(Color(0xFF5A5F68), Color(0xFF1B1D22), Color(0xFF6B7079), Color(0xFF1B1D22), Color(0xFF5A5F68)), c), r, c)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF1A1C21), Color(0xFF060708)), c, r), r * 0.93f, c)
            val arcR = r * 0.80f
            val w = r * 0.05f
            spec.dangerFrom?.let { d ->
                val (a, s) = if (spec.dangerLow) A_START to A_SWEEP * d else (A_START + A_SWEEP * d) to A_SWEEP * (1 - d)
                drawArc(look.danger.copy(alpha = 0.85f), a, s, false, Offset(c.x - arcR, c.y - arcR), Size(arcR * 2, arcR * 2), style = Stroke(w * 1.6f))
            }
            if (look.prefs.showTicks) {
                val steps = 50
                for (i in 0..steps) {
                    val major = i % 5 == 0
                    val ang = Math.toRadians((A_START + A_SWEEP * i / steps).toDouble())
                    val outer = r * 0.90f
                    val inner = if (major) r * 0.76f else r * 0.84f
                    val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
                    drawLine(
                        if (major) look.text else look.dim,
                        Offset(c.x + ca * inner, c.y + sa * inner), Offset(c.x + ca * outer, c.y + sa * outer),
                        strokeWidth = if (major) r * 0.018f else r * 0.008f,
                    )
                }
                // inner tick labels only: the two end labels would collide with the digital readout
                if (spec.ticks.size >= 3) for (i in 1 until spec.ticks.size - 1) {
                    val ang = Math.toRadians((A_START + A_SWEEP * i / (spec.ticks.size - 1f)).toDouble())
                    val tr = r * 0.63f
                    val layout = measurer.measure(spec.ticks[i], tickStyle)
                    drawText(layout, topLeft = Offset(c.x + cos(ang).toFloat() * tr - layout.size.width / 2, c.y + sin(ang).toFloat() * tr - layout.size.height / 2))
                }
            }
            val lab = measurer.measure(spec.label.uppercase(), labelStyle)
            drawText(lab, topLeft = Offset(c.x - lab.size.width / 2, c.y - r * 0.36f))
            // readout window
            val v = measurer.measure(spec.value, valueStyle)
            val u = measurer.measure(spec.unit.uppercase(), unitStyle)
            val top = c.y + r * 0.30f
            drawText(v, topLeft = Offset(c.x - v.size.width / 2, top))
            drawText(u, topLeft = Offset(c.x - u.size.width / 2, top + v.size.height - 2))
        }
        // ---- dynamic layer: progress arc + needle, reads the animated value only here
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2, size.height / 2)
            val f = frac.value
            val arcR = r * 0.80f
            val w = r * 0.05f
            if (spec.fraction != null) {
                val tl = Offset(c.x - arcR, c.y - arcR); val sz = Size(arcR * 2, arcR * 2)
                drawArc(look.primary.copy(alpha = 0.25f), A_START, A_SWEEP * f, false, tl, sz, style = Stroke(w * 3.2f, cap = StrokeCap.Butt))
                drawArc(look.primary, A_START, A_SWEEP * f, false, tl, sz, style = Stroke(w * 0.8f, cap = StrokeCap.Butt))
                rotate(A_START + A_SWEEP * f, c) {
                    val nl = r * 0.86f
                    drawLine(look.danger.copy(alpha = 0.35f), c, Offset(c.x + nl, c.y), strokeWidth = r * 0.06f, cap = StrokeCap.Round)
                    drawLine(Color(0xFFFF3B1F), Offset(c.x - r * 0.12f, c.y), Offset(c.x + nl, c.y), strokeWidth = r * 0.025f, cap = StrokeCap.Round)
                }
            }
            drawCircle(Brush.radialGradient(listOf(Color(0xFF6B7079), Color(0xFF111215)), c, r * 0.09f), r * 0.09f, c)
        }
    }
}

// --------------------------------------------------------------------------------------------- sport

@Composable
fun SportGauge(spec: GaugeSpec, modifier: Modifier = Modifier, big: Boolean = false) {
    val look = LocalRace.current
    val measurer = rememberTextMeasurer()
    val frac = animatedFractionState(spec.fraction)
    val danger = inDanger(spec)
    val valueStyle = TextStyle(
        fontFamily = look.display, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
        fontSize = if (big) 34.sp else 21.sp, color = if (danger) look.danger else look.text,
    )
    val unitStyle = TextStyle(fontFamily = look.body, fontWeight = FontWeight.Bold, fontSize = if (big) 13.sp else 10.sp, color = look.dim)
    val labelStyle = TextStyle(fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = if (big) 11.sp else 8.sp, color = look.primary, letterSpacing = 1.sp)

    Box(modifier.aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val c = Offset(size.width / 2, size.height / 2)
            val lab = measurer.measure(spec.label.uppercase(), labelStyle)
            val v = measurer.measure(spec.value, valueStyle)
            val u = measurer.measure(spec.unit.uppercase(), unitStyle)
            val total = lab.size.height + v.size.height + u.size.height
            var y = c.y - total / 2
            drawText(lab, topLeft = Offset(c.x - lab.size.width / 2, y)); y += lab.size.height
            drawText(v, topLeft = Offset(c.x - v.size.width / 2, y)); y += v.size.height
            drawText(u, topLeft = Offset(c.x - u.size.width / 2, y))
        }
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2, size.height / 2)
            val w = r * 0.16f
            val arcR = r - w / 2
            val tl = Offset(c.x - arcR, c.y - arcR)
            val sz = Size(arcR * 2, arcR * 2)
            val f = frac.value
            val segs = 30
            for (i in 0 until segs) {
                val a = A_START + A_SWEEP * i / segs
                val segFrac = (i + 0.5f) / segs
                val lit = spec.fraction != null && segFrac <= f
                val dz = spec.dangerFrom?.let { if (spec.dangerLow) segFrac <= it else segFrac >= it } == true
                val col = when {
                    lit && dz -> look.danger
                    lit -> lerp(look.primary, look.secondary, segFrac)
                    dz -> look.danger.copy(alpha = 0.22f)
                    else -> Color(0xFF23262C)
                }
                drawArc(col, a, A_SWEEP / segs - 2.2f, false, tl, sz, style = Stroke(w))
            }
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f
)

// --------------------------------------------------------------------------------------------- digital

@Composable
fun DigitalGauge(spec: GaugeSpec, modifier: Modifier = Modifier, big: Boolean = false) {
    val look = LocalRace.current
    val frac = animatedFractionState(spec.fraction)
    val danger = inDanger(spec)
    val shape = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)
    Column(
        modifier.clip(shape).background(if (look.prefs.oled) Color.Black else Color(0xFF07130F))
            .border(1.5.dp, look.primary.copy(alpha = 0.6f), shape).padding(if (big) 14.dp else 10.dp)
    ) {
        Text(spec.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = look.primary, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                spec.value, fontFamily = look.display, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                fontSize = if (big) 34.sp else 22.sp, color = if (danger) look.danger else look.ok, maxLines = 1,
            )
            Text(" " + spec.unit.uppercase(), style = MaterialTheme.typography.labelMedium, color = look.dim, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().height(if (big) 14.dp else 9.dp)) {
            val f = frac.value
            val n = 20
            val g = size.width * 0.012f
            val sw = (size.width - g * (n - 1)) / n
            for (i in 0 until n) {
                val p = (i + 0.5f) / n
                val lit = spec.fraction != null && p <= f
                val col = when {
                    p > 0.85f -> look.danger
                    p > 0.65f -> look.warn
                    else -> look.ok
                }
                drawRect(if (lit) col else col.copy(alpha = 0.12f), Offset(i * (sw + g), 0f), Size(sw, size.height))
            }
        }
        if (spec.sub != null) Text(spec.sub, style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1)
    }
}

// --------------------------------------------------------------------------------------------- shift lights

/** F1-style rev lights: 5 green, 5 red, 5 blue. Flash when fully lit. */
@Composable
fun ShiftLights(fraction: Float?, modifier: Modifier = Modifier) {
    val f = animatedFraction(fraction)
    val n = 15
    val lit = (f * n).roundToInt()
    val flashing = (fraction ?: 0f) >= 0.97f && LocalRace.current.prefs.animate
    // the blink animation only exists while flashing, so the screen is not redrawn constantly
    val blink = if (flashing) {
        val inf = rememberInfiniteTransition(label = "shift")
        val b by inf.animateFloat(1f, 0.15f, infiniteRepeatable(tween(160, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "blink")
        b
    } else 1f
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val gap = size.width * 0.015f
        val d = ((size.width - gap * (n - 1)) / n).coerceAtMost(size.height)
        val totalW = d * n + gap * (n - 1)
        val x0 = (size.width - totalW) / 2
        for (i in 0 until n) {
            val base = when {
                i < 5 -> Color(0xFF00E676)
                i < 10 -> Color(0xFFFF1E1E)
                else -> Color(0xFF2B8CFF)
            }
            val on = i < lit
            val alpha = if (flashing) blink else 1f
            val cx = x0 + i * (d + gap) + d / 2
            val cy = size.height / 2
            if (on) drawCircle(base.copy(alpha = 0.35f * alpha), d * 0.75f, Offset(cx, cy))
            drawCircle(if (on) base.copy(alpha = alpha) else base.copy(alpha = 0.10f), d / 2.2f, Offset(cx, cy))
        }
    }
}

/** Horizontal fuel-style bar (used for battery and goals). */
@Composable
fun FuelBar(label: String, fraction: Float?, text: String, modifier: Modifier = Modifier, lowIsBad: Boolean = true) {
    val look = LocalRace.current
    val f = animatedFraction(fraction)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = look.dim)
            Text(text, style = MaterialTheme.typography.labelLarge, color = look.text)
        }
        Canvas(Modifier.fillMaxWidth().height(12.dp).padding(top = 3.dp)) {
            drawRect(Color(0xFF23262C), size = size)
            val col = if (lowIsBad && (fraction ?: 1f) < 0.2f) look.danger else look.primary
            drawRect(Brush.horizontalGradient(listOf(col.copy(alpha = 0.6f), col)), size = Size(size.width * f, size.height))
            for (i in 1 until 8) {
                val x = size.width * i / 8
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            }
        }
    }
}

/** Sleep shown as a tyre-stint bar. Deep = hard (white), light = medium (yellow), REM = soft (red), awake = grey. */
@Composable
fun StintBar(deep: Double, light: Double, rem: Double, other: Double, awake: Double, modifier: Modifier = Modifier) {
    val total = deep + light + rem + other + awake
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        if (total <= 0) return@Canvas
        var x = 0f
        listOf(
            deep to Color(0xFFEDEDED), light to Color(0xFFFFD12E), rem to Color(0xFFFF2D2D),
            other to Color(0xFF39B54A), awake to Color(0xFF5A5F68),
        ).forEach { (v, c) ->
            val w = (v / total * size.width).toFloat()
            if (w > 0) drawRect(c, Offset(x, 0f), Size(w - 2f, size.height))
            x += w
        }
    }
}

@Composable
fun TyreDot(color: Color, letter: String) {
    Box(Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF111111)).border(3.dp, color, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, fontSize = 8.sp, fontWeight = FontWeight.Black, color = color)
    }
}

// --------------------------------------------------------------------------------------------- icons

private fun strokeIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }.build()

object RaceIcons {
    val Bolt = strokeIcon("bolt") {
        moveTo(13.5f, 2.5f); lineTo(5f, 13.5f); lineTo(11.5f, 13.5f); lineTo(10.5f, 21.5f); lineTo(19f, 10f); lineTo(12.5f, 10f); close()
    }
    val Gauge = strokeIcon("gauge") {
        moveTo(4f, 17f); arcTo(8.5f, 8.5f, 0f, true, true, 20f, 17f)
        moveTo(12f, 15f); lineTo(16f, 9.5f)
        moveTo(6.5f, 12f); lineTo(7.5f, 12.5f)
        moveTo(12f, 7f); lineTo(12f, 8.2f)
    }
    val Wheel = strokeIcon("wheel") {
        moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f)
        moveTo(3.5f, 11f); curveTo(7f, 10f, 17f, 10f, 20.5f, 11f)
        moveTo(12f, 14f); lineTo(12f, 20.8f)
        moveTo(14f, 13f); arcTo(2f, 2f, 0f, true, true, 10f, 13f); arcTo(2f, 2f, 0f, true, true, 14f, 13f)
    }
    val Flag = strokeIcon("flag") {
        moveTo(5f, 21f); lineTo(5f, 3.5f)
        moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 14f); lineTo(5f, 14f)
        moveTo(8.5f, 4f); lineTo(8.5f, 9f); lineTo(5f, 9f)
        moveTo(12f, 9f); lineTo(12f, 14f)
        moveTo(8.5f, 9f); lineTo(15.5f, 9f)
        moveTo(15.5f, 4f); lineTo(15.5f, 9f); lineTo(19f, 9f)
    }
    val Pulse = strokeIcon("pulse") {
        moveTo(2f, 12f); lineTo(7f, 12f); lineTo(9.5f, 6f); lineTo(13.5f, 18f); lineTo(16f, 12f); lineTo(22f, 12f)
    }
    val Helmet = strokeIcon("helmet") {
        moveTo(3f, 15f); curveTo(3f, 8f, 8f, 4f, 13f, 4f); curveTo(18.5f, 4f, 21.5f, 8f, 21.5f, 13f)
        lineTo(21.5f, 17f); lineTo(9f, 17f); lineTo(9f, 19.5f); lineTo(5f, 19.5f); curveTo(3.8f, 19.5f, 3f, 17.5f, 3f, 15f)
        moveTo(11f, 10f); lineTo(21f, 10f); lineTo(21f, 13.5f); lineTo(12.5f, 13.5f); close()
    }
    val Watch = strokeIcon("watch") {
        moveTo(9f, 3f); lineTo(15f, 3f); lineTo(15.5f, 6f)
        moveTo(9f, 21f); lineTo(15f, 21f); lineTo(15.5f, 18f)
        moveTo(9f, 3f); lineTo(8.5f, 6f)
        moveTo(9f, 21f); lineTo(8.5f, 18f)
        moveTo(8f, 6f); lineTo(16f, 6f); arcTo(1.5f, 1.5f, 0f, false, true, 17.5f, 7.5f); lineTo(17.5f, 16.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 16f, 18f); lineTo(8f, 18f); arcTo(1.5f, 1.5f, 0f, false, true, 6.5f, 16.5f)
        lineTo(6.5f, 7.5f); arcTo(1.5f, 1.5f, 0f, false, true, 8f, 6f)
        moveTo(9f, 12f); lineTo(10.5f, 12f); lineTo(11.5f, 10f); lineTo(12.8f, 14f); lineTo(13.8f, 12f); lineTo(15f, 12f)
    }
    val Wrench = strokeIcon("wrench") {
        moveTo(14.5f, 6f); arcTo(4f, 4f, 0f, false, false, 19.5f, 11f); lineTo(21f, 9.5f)
        moveTo(14.5f, 6f); lineTo(16f, 4.5f); arcTo(4f, 4f, 0f, false, false, 10.8f, 10.2f)
        lineTo(3.5f, 17.5f); lineTo(6.5f, 20.5f); lineTo(13.8f, 13.2f); arcTo(4f, 4f, 0f, false, false, 19.5f, 11f)
    }
}
