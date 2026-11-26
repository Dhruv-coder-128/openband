package com.openband.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.res.ResourcesCompat
import com.openband.app.R
import com.openband.app.data.RoutePt
import com.openband.app.data.Workout
import com.openband.app.workout.Laps
import com.openband.app.workout.Sports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

enum class CardStyle(val label: String) { SATELLITE("Satellite"), DARK("Dark"), LIGHT("Light") }

/** Draws a 1080 x 1350 picture of a workout, ready to post or send. */
object ShareCard {
    private const val W = 1080
    private const val H = 1350
    private const val MAP_H = 790

    suspend fun render(ctx: Context, w: Workout, style: CardStyle): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val light = style == CardStyle.LIGHT
        val bg = if (light) 0xFFF4F3EF.toInt() else 0xFF121315.toInt()
        val ink = if (light) 0xFF17181A.toInt() else 0xFFF2F2EF.toInt()
        val dim = if (light) 0xFF5D6066.toInt() else 0xFFA2A6AD.toInt()
        val accent = 0xFFE8541F.toInt()
        c.drawColor(bg)

        val base = runCatching { ResourcesCompat.getFont(ctx, R.font.inter) }.getOrNull() ?: Typeface.SANS_SERIF
        fun face(weight: Int) = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false) else base
        fun paint(color: Int, size: Float, weight: Int = 500, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = face(weight); textAlign = align; fontFeatureSettings = "tnum" }

        val hasRoute = w.route.size >= 2
        if (hasRoute) drawMap(ctx, c, w.route, style, light, accent)

        val p = RaceSettings.prefs.value
        val sport = Sports.byId(w.sport)
        var y = if (hasRoute) MAP_H + 86f else 220f
        c.drawText(sport.name, 64f, y, paint(ink, 54f, 700))
        c.drawText(SimpleDateFormat("EEE d MMM yyyy · h:mm a", Locale.getDefault()).format(Date(w.start)), W - 64f, y, paint(dim, 32f, align = Paint.Align.RIGHT))

        val dist = fmtDistance(w.distanceM, p.imperial)
        val big = if (w.distanceM >= 50) dist.first else com.openband.app.ui.fmtDuration(w.movingMs)
        y += if (hasRoute) 146f else 330f
        val bigPaint = paint(ink, if (hasRoute) 150f else 250f, 750)
        c.drawText(big, 58f, y, bigPaint)
        if (w.distanceM >= 50) c.drawText(dist.second, 58f + bigPaint.measureText(big) + 18f, y, paint(dim, 54f, 600))

        val stats = ArrayList<Pair<String, String>>()
        if (w.distanceM >= 50) stats += "Time" to com.openband.app.ui.fmtDuration(w.movingMs)
        if (w.distanceM >= 50 && sport.paceMode) {
            val pace = w.movingMs / 1000.0 / (w.distanceM / (if (p.imperial) 1609.344 else 1000.0))
            stats += "Pace" to "%d:%02d".format((pace / 60).toInt(), (pace % 60).toInt())
        }
        stats += "Calories" to "${w.kcal.toInt()}"
        if (w.avgHr > 0) stats += "Avg heart" to "${w.avgHr}"
        val laps = Laps.detect(w.route)
        if (laps != null) stats += "Rounds" to "${laps.count}" else if (w.steps > 0) stats += "Steps" to "%,d".format(w.steps)
        val shown = stats.take(5)
        y += if (hasRoute) 96f else 220f
        val cw = (W - 128f) / shown.size
        shown.forEachIndexed { i, (name, value) ->
            val x = 64f + cw * i
            c.drawText(name, x, y, paint(dim, 28f))
            val vp = paint(ink, 50f, 700)
            while (vp.measureText(value) > cw - 16f && vp.textSize > 28f) vp.textSize -= 2f
            c.drawText(value, x, y + 62f, vp)
        }

        // wordmark: the open ring and dot from the app icon
        val ly = H - 40f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; this.style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
        c.drawArc(RectF(64f, ly - 34f, 100f, ly + 2f), -10f, 285f, false, ring)
        c.drawCircle(95f, ly - 29f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        c.drawText("OpenBand", 118f, ly - 4f, paint(ink, 36f, 700))
        if (hasRoute && style == CardStyle.SATELLITE) c.drawText("Imagery © Esri, Maxar, Earthstar Geographics", W - 64f, ly - 6f, paint(dim, 22f, align = Paint.Align.RIGHT))
        return bmp
    }

    private suspend fun drawMap(ctx: Context, c: Canvas, route: List<RoutePt>, style: CardStyle, light: Boolean, accent: Int) {
        val scale = 2.0
        val vw = W / scale; val vh = MAP_H / scale
        val minLat = route.minOf { it.lat }; val maxLat = route.maxOf { it.lat }
        val minLon = route.minOf { it.lon }; val maxLon = route.maxOf { it.lon }
        var z = 18
        while (z > 3 && (lonToX(maxLon, z) - lonToX(minLon, z) > vw * 0.8 || latToY(minLat, z) - latToY(maxLat, z) > vh * 0.8)) z--
        val ox = lonToX((minLon + maxLon) / 2, z) - vw / 2
        val oy = latToY((minLat + maxLat) / 2, z) - vh / 2
        c.save()
        c.clipRect(0, 0, W, MAP_H)
        if (style == CardStyle.SATELLITE) {
            val n = 2.0.pow(z).toInt()
            for (ty in floor(oy / TILE).toInt()..floor((oy + vh) / TILE).toInt()) {
                if (ty !in 0 until n) continue
                for (tx in floor(ox / TILE).toInt()..floor((ox + vw) / TILE).toInt()) {
                    val tile = TileCache.load(ctx, true, z, ((tx % n) + n) % n, ty) ?: continue
                    val left = ((tx * TILE - ox) * scale).toInt(); val top = ((ty * TILE - oy) * scale).toInt()
                    c.drawBitmap(tile.asAndroidBitmap(), null, Rect(left, top, left + (TILE * scale).toInt() + 1, top + (TILE * scale).toInt() + 1), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
        } else c.drawColor(if (light) 0xFFE9E7E0.toInt() else 0xFF1A1C20.toInt())
        fun x(p: RoutePt) = ((lonToX(p.lon, z) - ox) * scale).toFloat()
        fun y(p: RoutePt) = ((latToY(p.lat, z) - oy) * scale).toFloat()
        val path = Path()
        route.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p), y(p)) else path.lineTo(x(p), y(p)) }
        fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; this.style = Paint.Style.STROKE; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        if (style == CardStyle.SATELLITE) c.drawPath(path, stroke(0xFFFFFFFF.toInt(), 17f))
        c.drawPath(path, stroke(accent, 10f))
        fun dot(p: RoutePt, color: Int) {
            c.drawCircle(x(p), y(p), 19f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() })
            c.drawCircle(x(p), y(p), 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        if (Laps.straight(route.first(), route.last()) > 40) dot(route.last(), 0xFFD63B3B.toInt())
        dot(route.first(), 0xFF1F9D58.toInt())
        c.restore()
    }
}
