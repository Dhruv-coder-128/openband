package com.openband.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Planner
import com.openband.app.data.Stats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val GREEN = Color(0xFF98C379)
private val BLUE = Color(0xFF61AFEF)
private val YELLOW = Color(0xFFE5C07B)
private val RED = Color(0xFFE06C75)
private val PURPLE = Color(0xFFC678DD)

private class Line(val text: AnnotatedString, val open: String? = null)

private fun bar(f: Double, width: Int) = (f * width).toInt().coerceIn(0, width).let { "█".repeat(it) + "░".repeat(width - it) }

private fun spark(values: List<Double>): String {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: return "▁".repeat(values.size)
    val blocks = "▁▂▃▄▅▆▇█"
    return values.joinToString("") { blocks[((it / max) * 7).toInt().coerceIn(0, 7)].toString() }
}

/** The Terminal home screen: your day as the output of a command-line session. Tap a line to open it. */
@Composable
fun DashTerminal(d: DashCtx) {
    val look = LocalRace.current
    val p = look.prefs
    val s = d.s
    val open = LocalOpenMetric.current
    val ctx = LocalContext.current
    val user = p.displayName.ifEmpty { "user" }.lowercase().replace(' ', '_')
    var plan by remember { mutableStateOf<Planner.Plan?>(null) }
    LaunchedEffect(s?.loadedAt) { plan = withContext(Dispatchers.IO) { runCatching { Planner.plan(Db.get(ctx), p, false) }.getOrNull() } }
    var cursor by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(530); cursor = !cursor } }

    val lines = ArrayList<Line>()
    fun cmd(c: String) {
        lines += Line(buildAnnotatedString { append(" ") })
        lines += Line(buildAnnotatedString {
            withStyle(SpanStyle(color = GREEN, fontWeight = FontWeight.Bold)) { append("$user@qore2") }
            withStyle(SpanStyle(color = look.dim)) { append(":~$ ") }
            withStyle(SpanStyle(color = look.text)) { append(c) }
        })
    }
    fun out(key: String, value: String, color: Color = look.text, extra: String = "", extraColor: Color = look.dim, go: String? = null) {
        lines += Line(buildAnnotatedString {
            withStyle(SpanStyle(color = BLUE)) { append(key.padEnd(10)) }
            withStyle(SpanStyle(color = color)) { append(value) }
            if (extra.isNotEmpty()) withStyle(SpanStyle(color = extraColor)) { append("  $extra") }
        }, go)
    }
    fun raw(text: String, color: Color = look.dim, go: String? = null) { lines += Line(AnnotatedString(text, SpanStyle(color = color)), go) }

    if (s != null) {
        val steps = s.totals[M.STEPS] ?: 0.0
        val dist = fmtDistance(s.totals[M.DISTANCE], p.imperial)
        val kcal = if (p.calTotal) s.totals[M.TOTAL_CAL] else s.totals[M.CALORIES]
        cmd("openband today")
        out("steps", "%,d / %,d".format(steps.toLong(), p.stepGoal), YELLOW, go = M.STEPS)
        raw("[${bar(steps / p.stepGoal, 24)}] ${(steps * 100 / p.stepGoal).toInt()}%", GREEN, M.STEPS)
        out("calories", "${kcal?.toInt() ?: "--"} kcal", go = M.CALORIES)
        out("distance", "${dist.first} ${dist.second}", go = M.DISTANCE)
        out("active", "${s.hourlySteps.count { it >= 60 }} h")

        cmd("openband vitals")
        raw("METRIC    VALUE      STATE", look.dim)
        fun vital(name: String, id: String, v: Double?, shown: String) {
            if (v == null) return
            val st = Palette.status(id, v, s.baseline[id])
            val col = when (st?.second) { 0 -> GREEN; 1 -> YELLOW; 2 -> RED; else -> look.dim }
            lines += Line(buildAnnotatedString {
                withStyle(SpanStyle(color = BLUE)) { append(name.padEnd(10)) }
                withStyle(SpanStyle(color = look.text)) { append(shown.padEnd(11)) }
                withStyle(SpanStyle(color = col)) { append(st?.first?.lowercase()?.replace(" average", "")?.replace("usual for you", "usual") ?: "-") }
            }, id)
        }
        val hr = d.band.liveHr?.toDouble() ?: s.latest[M.HR]?.first
        vital("heart", M.HR, hr, "${hr?.toInt()} bpm")
        vital("spo2", M.SPO2, s.latest[M.SPO2]?.first, "${s.latest[M.SPO2]?.first?.toInt()} %")
        vital("hrv", M.HRV, s.latest[M.HRV]?.first, "${s.latest[M.HRV]?.first?.toInt()} ms")
        vital("stress", M.STRESS, s.latest[M.STRESS]?.first, "${s.latest[M.STRESS]?.first?.toInt()}/100")
        s.latest[M.TEMP]?.first?.let { val (v, u) = fmtTemp(it, p.fahrenheit); vital("temp", M.TEMP, it, "$v $u") }

        s.sleep?.let { sl ->
            cmd("openband sleep --last")
            out("total", Stats.fmtMinutes(sl.total), PURPLE, "goal ${Stats.fmtMinutes(p.sleepGoalMin.toDouble())}", go = INST_SLEEP)
            val all = sl.total.coerceAtLeast(1.0)
            listOf("deep" to sl.deep, "light" to sl.light + sl.other, "rem" to sl.rem, "awake" to sl.awake).forEach { (n, m) ->
                out(n, bar(m / all, 14), PURPLE, Stats.fmtMinutes(m), go = INST_SLEEP)
            }
        }

        cmd("openband steps --hourly")
        raw(spark(s.hourlySteps), GREEN, M.STEPS)
        raw("0     6     12    18   23", look.dim)

        cmd("openband week")
        val week = s.week[M.STEPS] ?: emptyList()
        val mx = week.filterNotNull().maxOrNull()?.coerceAtLeast(p.stepGoal.toDouble()) ?: p.stepGoal.toDouble()
        week.forEachIndexed { i, v ->
            val day = LocalDate.now().minusDays((week.size - 1 - i).toLong()).dayOfWeek.name.take(3).lowercase()
            out(day, bar((v ?: 0.0) / mx, 16), if ((v ?: 0.0) >= p.stepGoal) GREEN else look.dim, v?.let { "%,d".format(it.toLong()) } ?: "-", go = M.STEPS)
        }

        plan?.let { pl ->
            cmd("coach --today")
            raw("> ${pl.headline.lowercase()}" + (pl.score?.let { " (recovery $it)" } ?: ""), YELLOW, "page:coach")
            raw("> ${pl.move.lowercase()}", look.text, "page:coach")
            raw("> steps target %,d".format(pl.stepsTarget), look.text, "page:coach")
            pl.bedtime?.let { raw("> sleep at $it", look.text, "page:coach") }
        }
    }

    // lines appear one after another the first time the screen is opened
    var shown by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(lines.size) { while (shown < lines.size) { delay(if (p.animate) 22 else 0); shown += if (p.animate) 1 else lines.size } }

    val frame = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(frame).background(look.panel).border(1.dp, look.border, frame)) {
        Row(Modifier.fillMaxWidth().background(look.panelHi).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(RED, YELLOW, GREEN).forEach { Box(Modifier.padding(end = 6.dp).size(10.dp).clip(CircleShape).background(it)) }
            Text("$user — openband — ${if (d.band.ready) "band: up" else "band: idle"} ${d.battery?.let { "$it%" } ?: ""}", Modifier.weight(1f).padding(start = 6.dp).clickable(onClick = d.openBand), fontFamily = look.body, fontSize = 12.sp, color = look.dim, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            val style = TextStyle(fontFamily = look.body, fontSize = 14.sp, lineHeight = 21.sp)
            if (s == null) Text("loading…", style = style, color = look.dim)
            lines.take(shown).forEach { l ->
                Text(l.text, Modifier.fillMaxWidth().then(if (l.open != null) Modifier.clickable { open(l.open) } else Modifier), style = style, maxLines = 1, softWrap = false)
            }
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = GREEN, fontWeight = FontWeight.Bold)) { append("$user@qore2") }
                withStyle(SpanStyle(color = look.dim)) { append(":~$ ") }
                if (cursor) withStyle(SpanStyle(color = look.text)) { append("█") }
            }, Modifier.padding(top = 10.dp), style = style)
        }
    }
}
