package com.openband.app.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.openband.app.R
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Stats
import com.openband.app.ui.INST_SLEEP
import com.openband.app.ui.Palette
import com.openband.app.ui.RaceLook
import com.openband.app.ui.RaceSettings
import com.openband.app.ui.TodaySummary
import com.openband.app.ui.UiStyle
import com.openband.app.ui.ago
import com.openband.app.ui.fmtDistance
import com.openband.app.ui.fmtTemp
import com.openband.app.ui.loadToday
import com.openband.app.ui.lookFor
import com.openband.app.ui.metricColor
import com.openband.app.ui.sleepColor
import com.openband.app.ui.studio
import com.openband.app.workout.WorkoutManager
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Everything a widget needs, loaded once per refresh and shared by all widgets. */
class WData(
    val s: TodaySummary?,
    val battery: Int?,
    val streak: Int,
    val weekActive: List<Boolean>,
    val hrNow: Double?,
    val hrAt: Long,
    val updatedAt: Long,
)

fun loadWidgetData(ctx: Context): WData {
    val p = RaceSettings.prefs.value
    val db = Db.get(ctx)
    val s = runCatching { loadToday(db, p.sleepGoalMin, p.maxHr) }.getOrNull()
    val battery = db.latest(M.BATTERY)?.value?.toInt()
    val streak = runCatching { WorkoutManager.streak(p.stepGoal) }.getOrDefault(0)
    val zone = ZoneId.systemDefault()
    val workoutDays = runCatching { db.workouts(60).map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.toSet() }.getOrDefault(emptySet())
    val today = LocalDate.now()
    val week = (6 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        d in workoutDays || (Stats.dayValue(db, M.STEPS, d) ?: 0.0) >= p.stepGoal
    }
    val lastHr = s?.hrPoints?.lastOrNull()
    val newest = listOfNotNull(db.latest(M.STEPS)?.ts, db.latest(M.HR)?.ts, db.latest(M.SPO2)?.ts, db.latest(M.HRV)?.ts, lastHr?.first).maxOrNull() ?: 0L
    return WData(s, battery, streak, week, lastHr?.second ?: db.latest(M.HR)?.value, lastHr?.first ?: db.latest(M.HR)?.ts ?: 0L, newest)
}

/** One reading, ready to be laid out at any size. */
private class Reading(
    val title: String,
    val value: String,
    val unit: String,
    val caption: String,
    val color: Int,
    /** Progress towards a goal (0..1+), or null when the reading has no goal. */
    val frac: Float? = null,
    val bars: List<Double?>? = null,
    val barsFromZero: Boolean = true,
    /** Today's curve as (position in the day 0..1, value). */
    val line: List<Pair<Float, Double>>? = null,
    val stats: List<Pair<String, String>> = emptyList(),
)

/**
 * Drawing surface in dp. Flat design (no glows or gradients), text that shrinks to fit, and layouts picked from the
 * widget's real size, so the same widget works as a 1x1 tile, a thin strip, a square or a large card.
 */
private class Pen(val ctx: Context, val c: Canvas, val w: Float, val h: Float, val look: RaceLook, val cfg: WidgetConfig) {
    private val bold = look.style == UiStyle.BOLD && cfg.opacity >= 50
    private val onBold = if (isLightColor(look.primary.toArgb())) 0xFF101113.toInt() else 0xFFFFFFFF.toInt()
    val text = if (bold) onBold else look.text.toArgb()
    val dim = if (bold) withAlpha(onBold, 0.80f) else look.dim.toArgb()
    val track = if (bold) withAlpha(onBold, 0.24f) else look.panelHi.toArgb()
    val panel = if (bold) look.primary.toArgb() else look.panel.toArgb()

    /** Type scale for this size. */
    val k = (min(w, h * 1.25f) / 150f).coerceIn(0.78f, 1.6f)
    val pad = (12f * k).coerceIn(8f, 20f)

    fun color(metric: String): Int = when {
        bold -> onBold
        cfg.accent != 0 -> cfg.accent
        look.style.studio && look.style != UiStyle.MONO -> Palette.metric(metric, !look.light).toArgb()
        else -> metricColor(metric, look).toArgb()
    }

    private fun font(res: Int, weight: Int): Typeface {
        val base = runCatching { ResourcesCompat.getFont(ctx, res) }.getOrNull() ?: Typeface.SANS_SERIF
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false) else base
    }

    private val numberFace: Typeface = when (look.style) {
        UiStyle.RACING, UiStyle.CYBER -> font(R.font.orbitron, 700)
        UiStyle.PAPER -> font(R.font.playfair, 700)
        UiStyle.BOLD -> font(R.font.space_grotesk, 700)
        UiStyle.MONO -> font(R.font.sharetech_mono, 400)
        else -> font(R.font.inter, 650)
    }
    private val textFace: Typeface = font(R.font.inter, 500)

    fun paint(color: Int, size: Float, number: Boolean = false, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color; textSize = size; typeface = if (number) numberFace else textFace; textAlign = align
            if (number) fontFeatureSettings = "tnum"
            if (cfg.opacity < 50) setShadowLayer(2.5f, 0f, 0.5f, if (look.light) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
        }

    /** Text shrunk (and finally cut) to fit [maxW]; returns the width used. */
    fun text(t: String, x: Float, y: Float, size: Float, color: Int = text, number: Boolean = false, align: Paint.Align = Paint.Align.LEFT, maxW: Float = w - 2 * pad): Float {
        if (maxW <= 4f) return 0f
        val p = paint(color, size, number, align)
        while (p.measureText(t) > maxW && p.textSize > size * 0.6f) p.textSize -= 0.5f
        var s = t
        if (p.measureText(s) > maxW) {
            while (s.length > 1 && p.measureText("$s…") > maxW) s = s.dropLast(1)
            s = "$s…"
        }
        c.drawText(s, x, y, p)
        return p.measureText(s)
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.STROKE; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    fun card() {
        if (cfg.opacity <= 0) return
        val r = if (look.style == UiStyle.MONO) 8f else min(24f, min(w, h) * 0.28f)
        val rect = RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f)
        val a = cfg.opacity / 100f
        c.drawRoundRect(rect, r, r, fill(withAlpha(panel, a)))
        if (!bold) c.drawRoundRect(rect, r, r, stroke(withAlpha(look.border.toArgb(), a), if (look.style == UiStyle.MONO) 1.5f else 1f))
    }

    fun dot(x: Float, y: Float, color: Int, r: Float = 3.5f * k) = c.drawCircle(x, y, r, fill(color))

    /** Dot + name at the top left; optional short note at the top right. */
    fun header(r: Reading, note: String = "") {
        val y = pad + 10f * k
        dot(pad + 3.5f * k, y - 4f * k, r.color)
        val noteW = if (note.isEmpty()) 0f else text(note, w - pad, y, 11f * k, dim, align = Paint.Align.RIGHT, maxW = w * 0.4f) + 6f
        text(r.title, pad + 12f * k, y, 11.5f * k, dim, maxW = w - pad * 2 - 12f * k - noteW)
    }

    fun ring(cx: Float, cy: Float, r: Float, sw: Float, frac: Float, color: Int) {
        c.drawCircle(cx, cy, r, stroke(track, sw))
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.004f) c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * f, false, stroke(color, sw))
    }

    /** Value with its unit on one baseline, centred on or starting at [x]. */
    fun value(v: String, unit: String, x: Float, y: Float, size: Float, maxW: Float, centered: Boolean = false) {
        val up = paint(text, size, true)
        val uu = paint(dim, size * 0.42f)
        var total = up.measureText(v) + if (unit.isEmpty()) 0f else uu.measureText(unit) + size * 0.12f
        if (total > maxW) {
            val f = (maxW / total).coerceAtLeast(0.45f)
            up.textSize *= f; uu.textSize *= f; total *= f
        }
        var left = if (centered) x - total / 2 else x
        c.drawText(v, left, y, up)
        left += up.measureText(v) + size * 0.10f
        if (unit.isNotEmpty()) c.drawText(unit, left, y, uu)
    }

    fun bars(values: List<Double?>, rect: RectF, color: Int, fromZero: Boolean, labels: Boolean, goal: Double? = null) {
        val labelH = if (labels && rect.height() > 34f) 13f * k else 0f
        val hh = rect.height() - labelH
        if (hh < 8f || rect.width() < 30f) return
        val present = values.filterNotNull()
        val mx = max(present.maxOrNull() ?: 1.0, goal ?: 0.0)
        val mn = present.minOrNull() ?: 0.0
        val floor = if (fromZero) 0.0 else mn - maxOf((mx - mn) * 0.8, kotlin.math.abs(mx) * 0.02, 0.5)
        val span = (mx - floor).takeIf { it > 1e-9 } ?: 1.0
        val slot = rect.width() / values.size
        val bw = min(slot * 0.56f, 22f)
        val today = LocalDate.now()
        values.forEachIndexed { i, v ->
            val x = rect.left + slot * i + (slot - bw) / 2
            val bh = if (v == null) 3f else (((v - floor) / span).toFloat() * hh).coerceIn(4f, hh)
            val col = if (v == null) track else if (i == values.lastIndex) color else withAlpha(color, 0.38f)
            val rr = if (look.style == UiStyle.MONO) 0f else bw / 3f
            c.drawRoundRect(RectF(x, rect.top + hh - bh, x + bw, rect.top + hh), rr, rr, fill(col))
            if (labelH > 0) text(
                today.minusDays((values.lastIndex - i).toLong()).dayOfWeek.name.take(1), x + bw / 2, rect.bottom, 9f * k,
                if (i == values.lastIndex) text else dim, align = Paint.Align.CENTER, maxW = slot,
            )
        }
        if (goal != null && goal > floor) {
            val gy = rect.top + hh - ((goal - floor) / span).toFloat() * hh
            c.drawLine(rect.left, gy, rect.right, gy, stroke(withAlpha(dim, 0.6f), 1f).apply { pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f) })
        }
    }

    /** Today's curve across a 24-hour axis. */
    fun curve(pts: List<Pair<Float, Double>>, rect: RectF, color: Int, labels: Boolean) {
        val labelH = if (labels && rect.height() > 40f) 12f * k else 0f
        val hh = rect.height() - labelH
        if (pts.size < 2 || hh < 10f) return
        val mn = pts.minOf { it.second } - 3; val mx = pts.maxOf { it.second } + 3
        val span = (mx - mn).takeIf { it > 1e-9 } ?: 1.0
        val line = Path(); val area = Path()
        var lastX = 0f; var lastY = 0f
        pts.forEachIndexed { i, (t, v) ->
            val x = rect.left + rect.width() * t.coerceIn(0f, 1f)
            val y = rect.top + hh - ((v - mn) / span).toFloat() * hh
            if (i == 0) { line.moveTo(x, y); area.moveTo(x, rect.top + hh); area.lineTo(x, y) } else { line.lineTo(x, y); area.lineTo(x, y) }
            lastX = x; lastY = y
        }
        area.lineTo(lastX, rect.top + hh); area.close()
        c.drawLine(rect.left, rect.top + hh, rect.right, rect.top + hh, stroke(track, 1f))
        c.drawPath(area, fill(withAlpha(color, 0.12f)))
        c.drawPath(line, stroke(color, 1.8f))
        c.drawCircle(lastX, lastY, 3f, fill(color))
        if (labelH > 0) listOf("00" to 0f, "06" to 0.25f, "12" to 0.5f, "18" to 0.75f, "24" to 1f).forEach { (l, f) ->
            text(l, rect.left + rect.width() * f, rect.bottom, 8.5f * k, dim, align = if (f == 0f) Paint.Align.LEFT else if (f == 1f) Paint.Align.RIGHT else Paint.Align.CENTER, maxW = 30f)
        }
    }

    fun chart(r: Reading, rect: RectF, labels: Boolean) {
        when {
            r.line != null && r.line.size >= 2 -> curve(r.line, rect, r.color, labels)
            r.bars != null && r.bars.any { it != null } -> bars(r.bars, rect, r.color, r.barsFromZero, labels)
        }
    }

    fun hasChart(r: Reading) = (r.line?.size ?: 0) >= 2 || r.bars?.any { it != null } == true
}

private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
private fun isLightColor(c: Int) = (0.299 * (c shr 16 and 0xFF) + 0.587 * (c shr 8 and 0xFF) + 0.114 * (c and 0xFF)) > 150

object WidgetRenderer {

    /** The app's look, or the OpenBand look forced to light / dark for this widget. */
    private fun lookOf(ctx: Context, cfg: WidgetConfig): RaceLook {
        val p = RaceSettings.prefs.value
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (cfg.theme == 0) return lookFor(p, night)
        val keep = p.uiStyle == UiStyle.STUDIO || p.uiStyle == UiStyle.BOLD || p.uiStyle == UiStyle.MONO
        val forced = if (keep) p.copy(appearance = cfg.theme)
        else p.copy(uiStyle = UiStyle.STUDIO, livery = "vermilion", customPrimary = null, customSecondary = null, appearance = cfg.theme)
        return lookFor(forced, night)
    }

    /** Draws one widget [wDp] x [hDp] into a bitmap of [wPx] x [hPx]. */
    fun render(ctx: Context, cfg: WidgetConfig, wDp: Float, hDp: Float, wPx: Int, hPx: Int, d: WData): Bitmap {
        val bmp = Bitmap.createBitmap(wPx.coerceAtLeast(40), hPx.coerceAtLeast(40), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(bmp.width / wDp, bmp.height / hDp)
        val pen = Pen(ctx, canvas, wDp, hDp, lookOf(ctx, cfg), cfg)
        pen.card()
        runCatching {
            when (cfg.kind) {
                "rings3" -> rings(pen, d)
                "vitals" -> vitals(pen, d)
                "weeksteps" -> week(pen, d)
                "hrgraph" -> heartGraph(pen, d)
                "sleepstages" -> sleepStages(pen, d)
                else -> {
                    val r = reading(pen, cfg.kind, d)
                    if (r == null) empty(pen, WidgetUpdater.label(cfg.kind)) else single(pen, r)
                }
            }
        }.onFailure { android.util.Log.w("Widget", "draw ${cfg.kind}", it) }
        return bmp
    }

    private fun empty(p: Pen, title: String) {
        p.text(title, p.w / 2, p.h / 2 - 3f, 12f * p.k, p.text, align = Paint.Align.CENTER)
        p.text("No data yet", p.w / 2, p.h / 2 + 14f * p.k, 11f * p.k, p.dim, align = Paint.Align.CENTER)
    }

    // ------------------------------------------------------------------------------------------------ readings

    private fun dayLine(d: WData): List<Pair<Float, Double>>? {
        val s = d.s ?: return null
        val span = (s.to - s.from).toFloat()
        return s.hrPoints.map { ((it.first - s.from) / span) to it.second }
    }

    private fun reading(p: Pen, kind: String, d: WData): Reading? {
        val pf = RaceSettings.prefs.value
        val s = d.s
        fun when_(t: Long?) = if (t == null || t <= 0) "" else ago(t)
        fun latest(m: String) = s?.latest?.get(m)
        fun week(m: String) = s?.week?.get(m)
        return when (kind) {
            "steps" -> {
                val v = s?.totals?.get(M.STEPS) ?: return null
                val dist = fmtDistance(s.totals[M.DISTANCE], pf.imperial)
                Reading(
                    "Steps", "%,d".format(v.toLong()), "", "of ${"%,d".format(pf.stepGoal)}", p.color(M.STEPS), (v / pf.stepGoal).toFloat(), week(M.STEPS),
                    stats = listOf("Distance" to "${dist.first} ${dist.second}", "Active" to "${s.hourlySteps.count { it >= 60 }} h"),
                )
            }
            "calories" -> {
                val m = if (pf.calTotal) M.TOTAL_CAL else M.CALORIES
                val v = s?.totals?.get(m) ?: return null
                Reading(
                    "Calories", "%,d".format(v.toLong()), "kcal", if (pf.calTotal) "total today" else "of ${pf.calGoal} active", p.color(M.CALORIES),
                    if (pf.calTotal) null else (v / pf.calGoal).toFloat(), week(m),
                )
            }
            "sleep" -> {
                val sl = s?.sleep ?: return null
                Reading(
                    "Sleep", Stats.fmtMinutes(sl.total), "", "of ${pf.sleepGoalMin / 60}h goal", p.color(INST_SLEEP), (sl.total / pf.sleepGoalMin).toFloat(), week(INST_SLEEP),
                    stats = listOf("Deep" to Stats.fmtMinutes(sl.deep), "REM" to Stats.fmtMinutes(sl.rem)),
                )
            }
            "hr" -> {
                val v = d.hrNow ?: return null
                Reading(
                    "Heart rate", "${v.toInt()}", "bpm", when_(d.hrAt), p.color(M.HR), line = dayLine(d), bars = week(M.HR), barsFromZero = false,
                    stats = s?.hr?.let { listOf("Low" to "${it.min.toInt()}", "Avg" to "${it.avg.toInt()}", "High" to "${it.max.toInt()}") } ?: emptyList(),
                )
            }
            "spo2" -> {
                val l = latest(M.SPO2) ?: return null
                Reading("Blood oxygen", "${l.first.toInt()}", "%", when_(l.second), p.color(M.SPO2), (l.first / 100).toFloat(), week(M.SPO2), false)
            }
            "hrv" -> {
                val l = latest(M.HRV) ?: return null
                val status = Palette.status(M.HRV, l.first, s?.baseline?.get(M.HRV))?.first
                Reading("HRV", "${l.first.toInt()}", "ms", status ?: when_(l.second), p.color(M.HRV), bars = week(M.HRV), barsFromZero = false)
            }
            "stress" -> {
                val l = latest(M.STRESS) ?: return null
                Reading("Stress", "${l.first.toInt()}", "", Palette.status(M.STRESS, l.first)?.first ?: "", p.color(M.STRESS), (l.first / 100).toFloat(), week(M.STRESS), false)
            }
            "temp" -> {
                val l = latest(M.TEMP) ?: return null
                val (t, u) = fmtTemp(l.first, pf.fahrenheit)
                Reading("Skin temp", t, u, when_(l.second), p.color(M.TEMP), bars = week(M.TEMP), barsFromZero = false)
            }
            "battery" -> {
                val b = d.battery ?: return null
                Reading("Band battery", "$b", "%", Palette.status(M.BATTERY, b.toDouble())?.first ?: "", p.color(M.BATTERY), b / 100f)
            }
            "streak" -> Reading(
                "Streak", "${d.streak}", if (d.streak == 1) "day" else "days", "active days in a row", p.color(M.STEPS),
                bars = d.weekActive.map { if (it) 1.0 else null },
            )
            else -> null
        }
    }

    // ------------------------------------------------------------------------------------------------ one reading, any size

    private fun single(p: Pen, r: Reading) {
        val w = p.w; val h = p.h; val k = p.k; val pad = p.pad
        val percent = r.frac?.let { "${(it * 100).toInt().coerceAtMost(999)}%" } ?: ""
        when {
            // one cell
            w < 110f && h < 110f -> {
                val m = min(w, h)
                if (r.frac != null) {
                    val rr = m / 2 - max(5f, m * 0.09f)
                    p.ring(w / 2, h / 2, rr, rr * 0.17f, r.frac, r.color)
                    p.value(r.value, if (r.unit == "%") "%" else "", w / 2, h / 2 + rr * 0.10f, rr * 0.54f, rr * 1.5f, true)
                    p.text(r.title.substringAfter("Band ").substringBefore(' '), w / 2, h / 2 + rr * 0.50f, rr * 0.25f, p.dim, align = Paint.Align.CENTER, maxW = rr * 1.35f)
                } else {
                    p.text(r.title, w / 2, h * 0.27f, m * 0.14f, p.dim, align = Paint.Align.CENTER, maxW = w - 8f)
                    p.value(r.value, "", w / 2, h * 0.62f, m * 0.34f, w - 10f, true)
                    p.text(r.unit.ifEmpty { r.caption }, w / 2, h * 0.84f, m * 0.13f, r.color, align = Paint.Align.CENTER, maxW = w - 8f)
                }
            }
            // thin strip
            h < 100f -> {
                var x = pad
                if (r.frac != null) {
                    val rr = (h - 2 * pad) / 2
                    p.ring(x + rr, h / 2, rr * 0.9f, rr * 0.2f, r.frac, r.color)
                    if (rr >= 20f) p.text(percent, x + rr, h / 2 + rr * 0.16f, rr * 0.44f, p.text, true, Paint.Align.CENTER, rr * 1.3f)
                    x += rr * 2 + pad * 0.8f
                } else {
                    p.dot(x + 3f, h / 2, r.color, 4f); x += 14f
                }
                val chartW = if (w >= 250f && p.hasChart(r)) w * 0.38f else 0f
                val tw = w - x - pad - chartW
                val vs = min(h * 0.36f, 30f)
                p.text(if (r.caption.isNotEmpty() && tw > 130f) "${r.title} · ${r.caption}" else r.title, x, h / 2 - vs * 0.32f, min(h * 0.17f, 12f), p.dim, maxW = tw)
                p.value(r.value, r.unit, x, h / 2 + vs * 0.72f, vs, tw)
                if (chartW > 0) p.chart(r, RectF(w - pad - chartW + 8f, pad * 0.9f, w - pad, h - pad * 0.9f), false)
            }
            // wide card
            w >= h * 1.55f -> {
                p.header(r)
                val hasChart = p.hasChart(r)
                val left = if (hasChart) w * 0.46f else w * 0.6f
                val vs = min(h * 0.27f, left * 0.30f)
                val vy = pad + 20f * k + vs
                p.value(r.value, r.unit, pad, vy, vs, left - pad)
                p.text(r.caption, pad, vy + 16f * k, 11f * k, p.dim, maxW = left - pad)
                if (r.frac != null && hasChart) {
                    val by = h - pad - 6f
                    p.c.drawRoundRect(RectF(pad, by, left - pad * 0.4f, by + 6f), 3f, 3f, p.fill(p.track))
                    p.c.drawRoundRect(RectF(pad, by, pad + (left - pad * 1.4f) * r.frac.coerceIn(0.02f, 1f), by + 6f), 3f, 3f, p.fill(r.color))
                    if (by - (vy + 22f * k) > 14f) p.text(percent, pad, by - 6f, 12f * k, r.color, true, maxW = left)
                }
                if (hasChart) p.chart(r, RectF(left + pad * 0.4f, pad + 4f, w - pad, h - pad), true)
                else if (r.frac != null) {
                    val rr = min((h - 2 * pad) / 2, (w - left - pad) / 2) * 0.9f
                    p.ring(w - pad - rr, h / 2, rr, rr * 0.18f, r.frac, r.color)
                }
            }
            // tall / large card
            h >= 195f -> {
                p.header(r, percent)
                val statsH = if (r.stats.isNotEmpty() && h >= 210f) 36f * k else 0f
                var top: Float
                if (r.frac != null && w >= 130f) {
                    val alone = !p.hasChart(r)
                    val rr = min(w / 2 - pad * 1.2f, (h - statsH) * if (alone) 0.36f else 0.205f)
                    val cy = if (alone) (h + pad + 14f * k) / 2 else pad + 24f * k + rr
                    p.ring(w / 2, cy, rr, rr * 0.16f, r.frac, r.color)
                    p.value(r.value, if (r.unit == "%") "%" else "", w / 2, cy + rr * 0.08f, rr * 0.52f, rr * 1.55f, true)
                    p.text(r.caption, w / 2, cy + rr * 0.45f, rr * 0.21f, p.dim, align = Paint.Align.CENTER, maxW = rr * 1.45f)
                    top = cy + rr + pad
                } else {
                    val vs = min(w * 0.24f, h * 0.17f)
                    val vy = pad + 20f * k + vs
                    p.value(r.value, r.unit, pad, vy, vs, w - 2 * pad)
                    p.text(r.caption, pad, vy + 17f * k, 12f * k, p.dim)
                    top = vy + 30f * k
                }
                val bottom = h - pad - statsH
                if (bottom - top > 24f) p.chart(r, RectF(pad, top, w - pad, bottom), true)
                if (statsH > 0) {
                    val cw = (w - 2 * pad) / r.stats.size
                    r.stats.forEachIndexed { i, (n, v) ->
                        p.text(n, pad + cw * i, h - pad - 17f * k, 10f * k, p.dim, maxW = cw - 4f)
                        p.text(v, pad + cw * i, h - pad - 1f, 13.5f * k, p.text, true, maxW = cw - 4f)
                    }
                }
            }
            // square
            else -> {
                p.header(r, if (r.frac != null) percent else "")
                val top = pad + 18f * k
                if (r.frac != null) {
                    val rr = min(w / 2 - pad, (h - top - pad * 0.7f) / 2)
                    val cy = top + (h - top - pad * 0.7f) / 2
                    p.ring(w / 2, cy, rr * 0.93f, rr * 0.16f, r.frac, r.color)
                    p.value(r.value, if (r.unit == "%") "%" else "", w / 2, cy + rr * 0.10f, rr * 0.50f, rr * 1.4f, true)
                    p.text(r.caption, w / 2, cy + rr * 0.46f, rr * 0.20f, p.dim, align = Paint.Align.CENTER, maxW = rr * 1.3f)
                } else {
                    val vs = min(w * 0.27f, h * 0.24f)
                    val vy = top + vs
                    p.value(r.value, r.unit, pad, vy, vs, w - 2 * pad)
                    p.text(r.caption, pad, vy + 15f * k, 11f * k, p.dim)
                    val chartTop = vy + 24f * k
                    if (h - pad - chartTop > 20f) p.chart(r, RectF(pad, chartTop, w - pad, h - pad), h - chartTop > 55f)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ combined widgets

    private fun rings(p: Pen, d: WData) {
        val pf = RaceSettings.prefs.value
        val s = d.s
        class It(val name: String, val v: String, val f: Float, val col: Int)
        val cal = s?.totals?.get(M.CALORIES)
        val items = listOf(
            It("Sleep", s?.sleep?.let { Stats.fmtMinutes(it.total).replace(" ", "") } ?: "--", s?.sleep?.let { (it.total / pf.sleepGoalMin).toFloat() } ?: 0f, p.color(INST_SLEEP)),
            It("Steps", s?.totals?.get(M.STEPS)?.let { "%,d".format(it.toLong()) } ?: "--", s?.totals?.get(M.STEPS)?.let { (it / pf.stepGoal).toFloat() } ?: 0f, p.color(M.STEPS)),
            It("Active kcal", cal?.let { "${it.toInt()}" } ?: "--", cal?.let { (it / pf.calGoal).toFloat() } ?: 0f, p.color(M.CALORIES)),
        )
        val w = p.w; val h = p.h; val pad = p.pad; val k = p.k
        when {
            w >= h * 1.5f -> {
                val cw = (w - pad) / 3f
                val labels = if (h >= 118f) 32f * k else if (h >= 80f) 13f * k else 0f
                val rr = min(cw / 2 - 5f, (h - pad * 1.4f - labels) / 2)
                items.forEachIndexed { i, t ->
                    val cx = pad / 2 + cw * (i + 0.5f)
                    val cy = (h - labels) / 2
                    p.ring(cx, cy, rr * 0.92f, rr * 0.19f, t.f, t.col)
                    p.text(t.v, cx, cy + rr * 0.15f, rr * 0.42f, p.text, true, Paint.Align.CENTER, rr * 1.35f)
                    if (labels > 20f) {
                        p.text(t.name, cx, h - pad - 14f * k, 10.5f * k, p.dim, align = Paint.Align.CENTER, maxW = cw - 4f)
                        p.text("${(t.f * 100).toInt()}%", cx, h - pad + 1f, 12f * k, t.col, true, Paint.Align.CENTER, cw - 4f)
                    } else if (labels > 0f) p.text(t.name, cx, h - 7f, 9.5f * k, p.dim, align = Paint.Align.CENTER, maxW = cw - 4f)
                }
            }
            h >= w * 1.4f -> {
                val rh = (h - pad) / 3f
                items.forEachIndexed { i, t ->
                    val cy = pad / 2 + rh * (i + 0.5f)
                    if (w >= 120f) {
                        val rr = min(rh / 2 - 5f, w * 0.2f)
                        p.ring(pad + rr, cy, rr * 0.9f, rr * 0.22f, t.f, t.col)
                        val x = pad * 1.6f + rr * 2
                        p.text(t.name, x, cy - 4f, 11f * k, p.dim, maxW = w - x - pad)
                        p.text(t.v, x, cy + 15f * k, 17f * k, p.text, true, maxW = w - x - pad)
                    } else {
                        val rr = min(rh / 2 - 4f, w / 2 - 6f)
                        p.ring(w / 2, cy, rr * 0.92f, rr * 0.18f, t.f, t.col)
                        p.text(t.v, w / 2, cy + rr * 0.15f, rr * 0.4f, p.text, true, Paint.Align.CENTER, rr * 1.35f)
                    }
                }
            }
            else -> {
                val legend = if (h >= 175f) 52f * k else 0f
                val rr = min(w / 2 - pad * 0.8f, (h - legend - pad * 1.4f) / 2)
                val cx = w / 2; val cy = (h - legend) / 2
                val sw = rr * 0.16f
                items.forEachIndexed { i, t -> p.ring(cx, cy, rr - sw / 2 - i * sw * 1.35f, sw, t.f, t.col) }
                if (rr > 44f) p.text("${(items[1].f * 100).toInt()}%", cx, cy + rr * 0.08f, rr * 0.22f, p.text, true, Paint.Align.CENTER, rr * 0.6f)
                if (legend > 0) {
                    val cw = (w - 2 * pad) / 3
                    items.forEachIndexed { i, t ->
                        val x = pad + cw * (i + 0.5f)
                        p.text(t.name.substringAfter("Active "), x, h - pad - 18f * k, 10f * k, t.col, align = Paint.Align.CENTER, maxW = cw - 2f)
                        p.text(t.v, x, h - pad, 13f * k, p.text, true, Paint.Align.CENTER, cw - 2f)
                    }
                }
            }
        }
    }

    private fun vitals(p: Pen, d: WData) {
        val s = d.s
        val pf = RaceSettings.prefs.value
        class Cell(val name: String, val v: String, val unit: String, val col: Int)
        val all = mapOf(
            "hr" to Cell("Heart", d.hrNow?.let { "${it.toInt()}" } ?: "--", "bpm", p.color(M.HR)),
            "spo2" to Cell("SpO2", s?.latest?.get(M.SPO2)?.let { "${it.first.toInt()}" } ?: "--", "%", p.color(M.SPO2)),
            "hrv" to Cell("HRV", s?.latest?.get(M.HRV)?.let { "${it.first.toInt()}" } ?: "--", "ms", p.color(M.HRV)),
            "stress" to Cell("Stress", s?.latest?.get(M.STRESS)?.let { "${it.first.toInt()}" } ?: "--", "", p.color(M.STRESS)),
            "temp" to Cell("Temp", s?.latest?.get(M.TEMP)?.let { fmtTemp(it.first, pf.fahrenheit).first } ?: "--", if (pf.fahrenheit) "°F" else "°C", p.color(M.TEMP)),
            "battery" to Cell("Band", d.battery?.let { "$it" } ?: "--", "%", p.color(M.BATTERY)),
        )
        val cells = p.cfg.tiles.mapNotNull { all[it] }
        val pad = p.pad * 0.7f; val gap = 6f
        // the grid that shows the most readings with the roomiest cells for this shape
        var cols = 1; var rows = 1; var shown = 1; var score = 0f
        for (c in 1..cells.size) for (r in 1..cells.size) {
            val n = min(c * r, cells.size)
            if (c * r - n >= c) continue
            val cw = (p.w - 2 * pad - (c - 1) * gap) / c
            val ch = (p.h - 2 * pad - (r - 1) * gap) / r
            if (cw < 46f || ch < 34f) continue
            val sc = min(cw / 1.4f, ch)
            if (n > shown || (n == shown && sc > score)) { cols = c; rows = r; shown = n; score = sc }
        }
        val cw = (p.w - 2 * pad - (cols - 1) * gap) / cols
        val ch = (p.h - 2 * pad - (rows - 1) * gap) / rows
        cells.take(shown).forEachIndexed { i, t ->
            val x = pad + (i % cols) * (cw + gap); val y = pad + (i / cols) * (ch + gap)
            val rr = min(12f, ch * 0.2f)
            p.c.drawRoundRect(RectF(x, y, x + cw, y + ch), rr, rr, p.fill(withAlpha(p.track, if (p.cfg.opacity < 50) 0.5f else 0.75f)))
            val ls = min(ch * 0.22f, 11.5f)
            val vs = min(ch * 0.38f, cw * 0.32f)
            val mid = y + ch / 2
            p.dot(x + 10f, mid - vs * 0.38f - ls * 0.35f, t.col, 3.2f)
            p.text(t.name, x + 17f, mid - vs * 0.38f, ls, p.dim, maxW = cw - 21f)
            p.value(t.v, t.unit, x + 8f, mid + vs * 0.78f, vs, cw - 12f)
        }
    }

    private fun week(p: Pen, d: WData) {
        val pf = RaceSettings.prefs.value
        val week = d.s?.week?.get(M.STEPS)
        if (week == null || week.all { it == null }) { empty(p, "Week of steps"); return }
        val avg = week.filterNotNull().average()
        val r = Reading("Steps · 7 days", "", "", "", p.color(M.STEPS))
        val compact = p.h < 90f
        if (!compact) p.header(r, if (p.w >= 180f) "avg ${"%,d".format(avg.toLong())}" else "")
        val top = if (compact) p.pad * 0.7f else p.pad + 22f * p.k
        p.bars(week, RectF(p.pad, top, p.w - p.pad, p.h - p.pad * 0.8f), r.color, true, true, pf.stepGoal.toDouble())
    }

    private fun heartGraph(p: Pen, d: WData) {
        val s = d.s
        val v = d.hrNow
        if (v == null || s == null || s.hrPoints.size < 2) { empty(p, "Heart rate"); return }
        val r = reading(p, "hr", d) ?: return
        if (p.h < 100f || p.w < 130f) { single(p, r); return }
        p.header(r, if (p.w >= 200f) r.caption else "")
        val vs = min(p.h * 0.2f, 34f * p.k)
        val vy = p.pad + 18f * p.k + vs
        p.value(r.value, r.unit, p.pad, vy, vs, p.w * 0.45f)
        val chips = p.h >= 180f && r.stats.isNotEmpty()
        if (!chips && p.w >= 220f) s.hr?.let { p.text("${it.min.toInt()} – ${it.max.toInt()} bpm today", p.w - p.pad, vy, 11f * p.k, p.dim, align = Paint.Align.RIGHT, maxW = p.w * 0.5f) }
        val bottom = p.h - p.pad - if (chips) 36f * p.k else 0f
        p.curve(r.line!!, RectF(p.pad, vy + 8f, p.w - p.pad, bottom), r.color, true)
        if (chips) {
            val cw = (p.w - 2 * p.pad) / r.stats.size
            r.stats.forEachIndexed { i, (n, value) ->
                val cx = p.pad + cw * (i + 0.5f)
                p.text(n, cx, p.h - p.pad - 17f * p.k, 10f * p.k, p.dim, align = Paint.Align.CENTER, maxW = cw)
                p.text(value, cx, p.h - p.pad, 14f * p.k, p.text, true, Paint.Align.CENTER, cw)
            }
        }
    }

    private fun sleepStages(p: Pen, d: WData) {
        val sl = d.s?.sleep
        if (sl == null) { empty(p, "Sleep stages"); return }
        val r = reading(p, "sleep", d) ?: return
        if (p.h < 100f || p.w < 130f) { single(p, r); return }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        p.header(r, if (p.w >= 200f) "${fmt.format(Date(sl.start))} – ${fmt.format(Date(sl.end))}" else "")
        val vs = min(p.h * 0.19f, 32f * p.k)
        val vy = p.pad + 18f * p.k + vs
        p.value(r.value, "", p.pad, vy, vs, p.w * 0.55f)
        if (p.w >= 200f) p.text("${sl.efficiency.toInt()}% asleep", p.w - p.pad, vy, 11f * p.k, p.dim, align = Paint.Align.RIGHT, maxW = p.w * 0.4f)
        val items = listOf(
            Triple("Deep", sl.deep, M.SLEEP_DEEP), Triple("Light", sl.light + sl.other, M.SLEEP_LIGHT),
            Triple("REM", sl.rem, M.SLEEP_REM), Triple("Awake", sl.awake, M.SLEEP_AWAKE),
        )
        val perRow = if (p.w >= 220f) 4 else 2
        val legendRows = if (p.h >= 140f) 4 / perRow else 0
        val slim = p.h < 185f && perRow == 4
        val legendH = if (legendRows == 2 && p.h < 185f) 0f else if (slim && p.h >= 125f) 16f * p.k else legendRows * 30f * p.k
        val top = vy + 10f
        val bottom = p.h - p.pad - legendH - if (legendH > 0) 4f else 0f
        val levels = listOf(M.SLEEP_AWAKE, M.SLEEP_REM, M.SLEEP_LIGHT, M.SLEEP_DEEP)
        val rowH = (bottom - top) / 4
        val span = (sl.end - sl.start).coerceAtLeast(1).toFloat()
        val ww = p.w - 2 * p.pad
        sl.segments.forEach { seg ->
            val lvl = levels.indexOf(if (seg.type == M.SLEEP_ASLEEP) M.SLEEP_LIGHT else seg.type).coerceAtLeast(0)
            val x0 = p.pad + ww * (seg.start - sl.start) / span; val x1 = max(x0 + 1.5f, p.pad + ww * (seg.end - sl.start) / span)
            p.c.drawRoundRect(RectF(x0, top + rowH * lvl + rowH * 0.12f, x1, top + rowH * (lvl + 1) - rowH * 0.12f), 2f, 2f, p.fill(sleepColor(seg.type, p.look).toArgb()))
        }
        if (legendH > 0 && slim) {
            val cw = ww / 4
            items.forEachIndexed { i, (n, mins, type) ->
                val x = p.pad + cw * i
                p.dot(x + 3.5f, p.h - p.pad - 4f, sleepColor(type, p.look).toArgb(), 3.2f)
                p.text(if (p.w >= 300f) "$n ${Stats.fmtMinutes(mins).replace(" ", "")}" else n, x + 11f, p.h - p.pad, 10f * p.k, p.dim, maxW = cw - 13f)
            }
        } else if (legendH > 0) {
            val cw = ww / perRow
            items.forEachIndexed { i, (n, min, type) ->
                val x = p.pad + cw * (i % perRow)
                val y = p.h - p.pad - legendH + 30f * p.k * (i / perRow)
                p.dot(x + 3.5f, y + 8f * p.k, sleepColor(type, p.look).toArgb(), 3.2f)
                p.text(n, x + 11f, y + 12f * p.k, 10f * p.k, p.dim, maxW = cw - 14f)
                p.text(Stats.fmtMinutes(min), x, y + 27f * p.k, 12.5f * p.k, p.text, true, maxW = cw - 4f)
            }
        }
    }
}
