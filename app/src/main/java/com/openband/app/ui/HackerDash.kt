package com.openband.app.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Recovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.sin

private const val GLYPHS = "01<>[]{}#$%&*+=/\\|ｱｲｳｴｵｶｷｸｹｺ"

/** Faint falling characters behind the Hacker look. Slow and dim on purpose, so text stays readable and battery is spared. */
@Composable
fun DigitalRain(color: Color, animate: Boolean) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(animate) { while (animate) { delay(140); tick++ } }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE } }
    Canvas(Modifier.fillMaxSize()) {
        val cell = 16.dp.toPx()
        val cols = (size.width / cell).toInt()
        val rows = (size.height / cell).toInt() + 1
        paint.textSize = 12.dp.toPx()
        for (c in 0 until cols) {
            if (c % 2 == 1) continue
            val speed = 1 + (c * 7) % 3
            val head = ((tick * speed / 2) + c * 13) % (rows + 14)
            for (k in 0 until 10) {
                val r = head - k
                if (r < 0 || r >= rows) continue
                paint.color = color.copy(alpha = if (k == 0) 0.30f else 0.15f * (1 - k / 10f)).toArgb()
                val g = GLYPHS[Math.floorMod(c * 31 + r * 17 + (if (k == 0) tick else 0), GLYPHS.length)]
                drawContext.canvas.nativeCanvas.drawText(g.toString(), c * cell + cell * 0.2f, (r + 1) * cell, paint)
            }
        }
    }
}

/** Corner brackets around a block, like a targeting frame. */
fun Modifier.brackets(color: Color) = drawBehind {
    val l = 16.dp.toPx(); val w = 2.dp.toPx()
    val x1 = size.width; val y1 = size.height
    listOf(
        Offset(0f, 0f) to listOf(Offset(l, 0f), Offset(0f, l)),
        Offset(x1, 0f) to listOf(Offset(x1 - l, 0f), Offset(x1, l)),
        Offset(0f, y1) to listOf(Offset(l, y1), Offset(0f, y1 - l)),
        Offset(x1, y1) to listOf(Offset(x1 - l, y1), Offset(x1, y1 - l)),
    ).forEach { (corner, ends) -> ends.forEach { drawLine(color, corner, it, w) } }
}

@Composable
private fun HudLabel(text: String) {
    val look = LocalRace.current
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▌$text", style = MaterialTheme.typography.titleMedium, color = look.primary)
        Box(Modifier.weight(1f).padding(start = 10.dp).height(1.dp).background(look.primary.copy(alpha = 0.35f)))
    }
}

/** The Hacker home screen: a system monitor for your body. */
@Composable
fun DashHacker(d: DashCtx) {
    val look = LocalRace.current
    val p = look.prefs
    val s = d.s
    val open = LocalOpenMetric.current
    val ctx = LocalContext.current
    var recovery by remember { mutableStateOf<Recovery.Result?>(null) }
    LaunchedEffect(s?.loadedAt) { recovery = withContext(Dispatchers.IO) { runCatching { Recovery.compute(Db.get(ctx), LocalDate.now(), p.sleepGoalMin) }.getOrNull() } }
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(600); blink = !blink } }

    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("[OPENBAND::QORE2]", style = MaterialTheme.typography.labelMedium, color = look.dim)
            Text("user:${p.displayName.ifEmpty { "root" }.lowercase()}${if (blink) "_" else " "}", style = MaterialTheme.typography.headlineMedium, color = look.primary, maxLines = 1)
        }
        Column(Modifier.clip(CutCornerShape(8.dp)).border(1.dp, (if (d.band.ready) look.ok else look.warn).copy(alpha = 0.8f), CutCornerShape(8.dp)).clickable(onClick = d.openBand).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(if (d.band.ready) "LINK:UP" else "LINK:WAIT", style = MaterialTheme.typography.labelMedium, color = if (d.band.ready) look.ok else look.warn)
            Text("PWR:${d.battery?.let { "$it%" } ?: "--"}", style = MaterialTheme.typography.labelSmall, color = look.dim)
        }
    }
    if (s == null) { Text("> loading…", color = look.dim, modifier = Modifier.padding(top = 20.dp)); return }

    // ---- power core: a segmented ring for steps
    val steps = s.totals[M.STEPS] ?: 0.0
    val frac = (steps / p.stepGoal).toFloat()
    Box(Modifier.fillMaxWidth().padding(top = 16.dp).reveal().brackets(look.primary).clickable { open(M.STEPS) }.padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(250.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val outer = size.width / 2
            val n = 48
            for (i in 0 until n) {
                val a = Math.toRadians(135.0 + 270.0 * i / (n - 1))
                val on = i.toFloat() / n < frac
                val r0 = outer * 0.80f; val r1 = outer * (if (on) 0.98f else 0.92f)
                drawLine(
                    if (on) look.primary else look.primary.copy(alpha = 0.18f),
                    Offset(c.x + (r0 * cos(a)).toFloat(), c.y + (r0 * sin(a)).toFloat()),
                    Offset(c.x + (r1 * cos(a)).toFloat(), c.y + (r1 * sin(a)).toFloat()), 5.dp.toPx(),
                )
            }
            drawCircle(look.primary.copy(alpha = 0.25f), outer * 0.70f, c, style = Stroke(1.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("STEPS", style = MaterialTheme.typography.labelMedium, color = look.dim)
            Text("%,d".format(steps.toLong()), style = MaterialTheme.typography.displayMedium, color = look.text)
            Text("${(frac * 100).toInt()}% / ${"%,d".format(p.stepGoal)}", style = MaterialTheme.typography.bodySmall, color = look.primary)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val dist = fmtDistance(s.totals[M.DISTANCE], p.imperial)
        val kcal = if (p.calTotal) s.totals[M.TOTAL_CAL] else s.totals[M.CALORIES]
        listOf("KCAL" to (kcal?.toInt()?.toString() ?: "--"), dist.second.uppercase() to dist.first, "ACT.H" to "${s.hourlySteps.count { it >= 60 }}").forEach { (k, v) ->
            Column(Modifier.weight(1f).reveal().clip(CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).background(look.panel.copy(alpha = 0.92f)).border(1.dp, look.border, CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp)).padding(10.dp)) {
                Text(k, style = MaterialTheme.typography.labelSmall, color = look.dim)
                Text(v, style = MaterialTheme.typography.titleLarge, color = look.text, maxLines = 1)
            }
        }
    }

    // ---- recovery as a charge bar
    recovery?.let { r ->
        HudLabel("RECOVERY")
        Column(Modifier.fillMaxWidth().clickable { open("page:coach") }) {
            Row(Modifier.fillMaxWidth()) {
                Text("${r.score}/100 ${r.label.uppercase()}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = toneColor(r.tone))
                Text("> run coach", style = MaterialTheme.typography.labelMedium, color = look.primary)
            }
            Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(16.dp)) {
                val n = 30; val gap = 2.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
                for (i in 0 until n) drawRect(if (i < r.score * n / 100) look.primary else look.primary.copy(alpha = 0.16f), Offset(i * (w + gap), 0f), Size(w, size.height))
            }
        }
    }

    // ---- heart signal on an oscilloscope grid
    if (s.hrPoints.size > 1) {
        HudLabel("SIGNAL // HEART")
        Box(Modifier.fillMaxWidth().background(look.panel.copy(alpha = 0.92f)).border(1.dp, look.border).clickable { open(M.HR) }.padding(8.dp)) {
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                for (i in 0..12) drawLine(look.primary.copy(alpha = 0.10f), Offset(size.width * i / 12, 0f), Offset(size.width * i / 12, size.height), 1f)
                for (i in 0..4) drawLine(look.primary.copy(alpha = 0.10f), Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1f)
                val mn = s.hrPoints.minOf { it.second } - 4; val mx = s.hrPoints.maxOf { it.second } + 4
                val span = (s.to - s.from).toFloat()
                val path = Path()
                var last = Offset.Zero
                s.hrPoints.forEachIndexed { i, (t, v) ->
                    val o = Offset(size.width * ((t - s.from) / span), size.height - ((v - mn) / (mx - mn)).toFloat() * size.height)
                    if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    last = o
                }
                drawPath(path, look.primary, style = Stroke(2.dp.toPx()))
                if (blink) drawRect(look.text, Offset(last.x - 4.dp.toPx(), last.y - 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()))
            }
        }
        s.hr?.let { Text("min ${it.min.toInt()}  avg ${it.avg.toInt()}  max ${it.max.toInt()} bpm", Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = look.dim, textAlign = TextAlign.End) }
    }

    // ---- sensor nodes
    HudLabel("SENSOR NODES")
    class Node(val id: String, val name: String, val value: String, val unit: String, val tone: Int, val at: Long?)
    val nodes = ArrayList<Node>()
    fun node(id: String, name: String, v: Double?, unit: String, text: String? = null, at: Long? = null) {
        if (v != null) nodes += Node(id, name, text ?: "${v.toInt()}", unit, Palette.status(id, v, s.baseline[id])?.second ?: -1, at)
    }
    node(M.HR, "HEART", d.band.liveHr?.toDouble() ?: s.latest[M.HR]?.first, "bpm", at = s.latest[M.HR]?.second)
    node(M.SPO2, "SPO2", s.latest[M.SPO2]?.first, "%", at = s.latest[M.SPO2]?.second)
    node(M.HRV, "HRV", s.latest[M.HRV]?.first, "ms", at = s.latest[M.HRV]?.second)
    node(M.STRESS, "STRESS", s.latest[M.STRESS]?.first, "", at = s.latest[M.STRESS]?.second)
    s.latest[M.TEMP]?.let { (v, at) -> val (txt, u) = fmtTemp(v, p.fahrenheit); node(M.TEMP, "TEMP", v, u, txt, at) }
    s.sleep?.let { nodes += Node(INST_SLEEP, "SLEEP", com.openband.app.data.Stats.fmtMinutes(it.total), "", if (it.total >= p.sleepGoalMin * 0.85) 0 else 1, null) }
    nodes.chunked(2).forEachIndexed { row, pair ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEachIndexed { i, n ->
                val shape = CutCornerShape(topEnd = 14.dp)
                val state = when (n.tone) { 0 -> "OK"; 1 -> "CHK"; 2 -> "ALRT"; else -> "--" }
                Column(Modifier.weight(1f).reveal().clip(shape).background(look.panel.copy(alpha = 0.92f)).border(1.dp, look.border, shape).clickable { open(n.id) }.padding(12.dp)) {
                    Row {
                        Text("0x%02X %s".format(row * 2 + i + 1, n.name), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1)
                        Text("[$state]", style = MaterialTheme.typography.labelSmall, color = toneColor(n.tone))
                    }
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
                        Text(n.value, style = MaterialTheme.typography.headlineMedium, color = look.text, maxLines = 1)
                        Text(" ${n.unit}", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text(n.at?.let { "t-${ago(it).removeSuffix(" ago")}" } ?: "last night", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = look.dim)
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
