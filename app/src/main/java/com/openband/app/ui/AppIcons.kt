package com.openband.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openband.app.workout.SportDef

/**
 * One consistent icon family: 24-pt grid, 1.75 pt round strokes, no fills. Every symbol in the app comes from here,
 * so nothing looks pasted in (no emoji).
 */
object AppIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy); arcTo(r, r, 0f, true, true, cx - r, cy); arcTo(r, r, 0f, true, true, cx + r, cy); close()
    }
    private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
        moveTo(cx + rx, cy); arcTo(rx, ry, 0f, true, true, cx - rx, cy); arcTo(rx, ry, 0f, true, true, cx + rx, cy); close()
    }

    val Home = icon("home") {
        moveTo(3.5f, 11f); lineTo(12f, 3.8f); lineTo(20.5f, 11f)
        moveTo(5.5f, 9.6f); lineTo(5.5f, 20f); lineTo(10f, 20f); lineTo(10f, 14.5f); lineTo(14f, 14.5f); lineTo(14f, 20f); lineTo(18.5f, 20f); lineTo(18.5f, 9.6f)
    }
    val Activity = icon("activity") { moveTo(13.5f, 2.8f); lineTo(5.5f, 13.4f); lineTo(11.5f, 13.4f); lineTo(10.5f, 21.2f); lineTo(18.5f, 10.6f); lineTo(12.5f, 10.6f); close() }
    val Trends = icon("trends") {
        moveTo(5f, 20f); lineTo(5f, 12f); moveTo(12f, 20f); lineTo(12f, 5f); moveTo(19f, 20f); lineTo(19f, 14f)
    }
    val Profile = icon("profile") {
        circle(12f, 8.2f, 3.6f)
        moveTo(4.8f, 20.2f); curveTo(4.8f, 16.4f, 8f, 14.2f, 12f, 14.2f); curveTo(16f, 14.2f, 19.2f, 16.4f, 19.2f, 20.2f)
    }
    val Settings = icon("settings") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        circle(9f, 7f, 2.2f); circle(15.5f, 12f, 2.2f); circle(8f, 17f, 2.2f)
    }
    val Heart = icon("heart") {
        moveTo(12f, 20.4f)
        curveTo(5.2f, 15.2f, 3.2f, 11.8f, 3.2f, 8.9f); curveTo(3.2f, 6.4f, 5.1f, 4.6f, 7.3f, 4.6f); curveTo(9.1f, 4.6f, 10.9f, 5.6f, 12f, 7.4f)
        curveTo(13.1f, 5.6f, 14.9f, 4.6f, 16.7f, 4.6f); curveTo(18.9f, 4.6f, 20.8f, 6.4f, 20.8f, 8.9f); curveTo(20.8f, 11.8f, 18.8f, 15.2f, 12f, 20.4f); close()
    }
    val Steps = icon("steps") {
        ellipse(8.4f, 14.6f, 2.5f, 4.2f); circle(6.6f, 8.2f, 0.9f); circle(8.6f, 7.6f, 0.9f); circle(10.5f, 8.2f, 0.9f)
        ellipse(16.2f, 9.4f, 2.5f, 4.2f); circle(14.4f, 3.4f, 0.9f); circle(16.4f, 2.9f, 0.9f); circle(18.3f, 3.4f, 0.9f)
    }
    val Flame = icon("flame") {
        moveTo(12f, 3f); curveTo(12f, 3f, 6.2f, 8.4f, 6.2f, 14f); curveTo(6.2f, 17.5f, 8.8f, 20.2f, 12f, 20.2f); curveTo(15.2f, 20.2f, 17.8f, 17.5f, 17.8f, 14f)
        curveTo(17.8f, 11.6f, 16.4f, 9.6f, 15f, 8.2f); curveTo(15f, 10.2f, 14f, 11.2f, 13f, 11.2f); curveTo(13f, 8f, 12f, 3f, 12f, 3f); close()
    }
    val Moon = icon("moon") {
        moveTo(19.8f, 14.2f); arcTo(8f, 8f, 0f, true, true, 9.8f, 4.2f); arcTo(6.2f, 6.2f, 0f, false, false, 19.8f, 14.2f); close()
    }
    val Drop = icon("drop") {
        moveTo(12f, 3.4f); curveTo(12f, 3.4f, 6f, 10.2f, 6f, 14.4f); arcTo(6f, 6f, 0f, false, false, 18f, 14.4f); curveTo(18f, 10.2f, 12f, 3.4f, 12f, 3.4f); close()
    }
    val Wave = icon("wave") {
        moveTo(2.5f, 12f); curveTo(4.2f, 5.5f, 6.2f, 5.5f, 8f, 12f); curveTo(9.8f, 18.5f, 11.8f, 18.5f, 13.5f, 12f); curveTo(15.2f, 5.5f, 17.2f, 5.5f, 19f, 12f); lineTo(21.5f, 12f)
    }
    val Gauge = icon("gauge") {
        moveTo(4.2f, 17f); arcTo(8.6f, 8.6f, 0f, true, true, 19.8f, 17f)
        moveTo(12f, 14.2f); lineTo(15.8f, 9.6f)
    }
    val Thermometer = icon("thermo") {
        moveTo(10f, 14.2f); lineTo(10f, 5.4f); arcTo(2f, 2f, 0f, false, true, 14f, 5.4f); lineTo(14f, 14.2f); arcTo(4f, 4f, 0f, true, true, 10f, 14.2f); close()
        moveTo(12f, 9f); lineTo(12f, 15.6f)
    }
    val Battery = icon("battery") {
        moveTo(4f, 8f); lineTo(18f, 8f); lineTo(18f, 16f); lineTo(4f, 16f); close(); moveTo(20.5f, 10.5f); lineTo(20.5f, 13.5f); moveTo(7f, 11f); lineTo(7f, 13f)
    }
    val Watch = icon("watch") {
        moveTo(8f, 7f); lineTo(16f, 7f); lineTo(16f, 17f); lineTo(8f, 17f); close()
        moveTo(9.2f, 7f); lineTo(9.8f, 3.2f); lineTo(14.2f, 3.2f); lineTo(14.8f, 7f)
        moveTo(9.2f, 17f); lineTo(9.8f, 20.8f); lineTo(14.2f, 20.8f); lineTo(14.8f, 17f)
        moveTo(12f, 10.2f); lineTo(12f, 12f); lineTo(13.4f, 12.8f)
    }
    val Chevron = icon("chevron") { moveTo(9f, 5.5f); lineTo(15.5f, 12f); lineTo(9f, 18.5f) }
    val Back = icon("back") { moveTo(15f, 5.5f); lineTo(8.5f, 12f); lineTo(15f, 18.5f) }
    val Plus = icon("plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
    val Check = icon("check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }
    val Close = icon("close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    val Trash = icon("trash") {
        moveTo(4.5f, 7f); lineTo(19.5f, 7f); moveTo(9f, 7f); lineTo(9f, 4.5f); lineTo(15f, 4.5f); lineTo(15f, 7f)
        moveTo(6.6f, 7f); lineTo(7.8f, 20f); lineTo(16.2f, 20f); lineTo(17.4f, 7f)
    }
    val Palette = icon("palette") {
        moveTo(12f, 3.5f); arcTo(8.5f, 8.5f, 0f, true, false, 12f, 20.5f); curveTo(13.6f, 20.5f, 14f, 19f, 13.2f, 17.8f); curveTo(12.4f, 16.6f, 13.2f, 15.4f, 14.6f, 15.4f); lineTo(17f, 15.4f); curveTo(19f, 15.4f, 20.5f, 14.2f, 20.5f, 12f); arcTo(8.5f, 8.5f, 0f, false, false, 12f, 3.5f)
        circle(8.2f, 11f, 0.8f); circle(11.2f, 7.8f, 0.8f); circle(15.4f, 8.6f, 0.8f)
    }
    val Layout = icon("layout") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 11f); lineTo(4f, 11f); close(); moveTo(4f, 14f); lineTo(11f, 14f); lineTo(11f, 19f); lineTo(4f, 19f); close(); moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 19f); lineTo(14f, 19f); close()
    }
    val Widgets = icon("widgets") {
        moveTo(4f, 4f); lineTo(10.5f, 4f); lineTo(10.5f, 10.5f); lineTo(4f, 10.5f); close(); moveTo(13.5f, 4f); lineTo(20f, 4f); lineTo(20f, 10.5f); lineTo(13.5f, 10.5f); close()
        moveTo(4f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20f); lineTo(4f, 20f); close(); moveTo(13.5f, 16.8f); lineTo(20f, 16.8f); moveTo(16.75f, 13.5f); lineTo(16.75f, 20f)
    }
    val Target = icon("target") { circle(12f, 12f, 8.5f); circle(12f, 12f, 4.5f); circle(12f, 12f, 0.9f) }
    val Folder = icon("folder") {
        moveTo(3.5f, 7f); lineTo(3.5f, 18.5f); lineTo(20.5f, 18.5f); lineTo(20.5f, 9f); lineTo(11f, 9f); lineTo(9.2f, 6.2f); lineTo(3.5f, 6.2f); close()
    }
    val Bell = icon("bell") {
        moveTo(6f, 16.5f); lineTo(6f, 11f); arcTo(6f, 6f, 0f, false, true, 18f, 11f); lineTo(18f, 16.5f); lineTo(19.5f, 18f); lineTo(4.5f, 18f); close()
        moveTo(10f, 20.8f); lineTo(14f, 20.8f)
    }
    val Info = icon("info") { circle(12f, 12f, 8.5f); moveTo(12f, 11f); lineTo(12f, 16.2f); moveTo(12f, 7.8f); lineTo(12f, 8f) }
    val Timer = icon("timer") { circle(12f, 13.4f, 7.6f); moveTo(12f, 9.4f); lineTo(12f, 13.6f); lineTo(14.8f, 15.4f); moveTo(9.5f, 2.8f); lineTo(14.5f, 2.8f) }

    // sport pictograms
    val Run = icon("run") {
        circle(15.2f, 4.6f, 1.9f)
        moveTo(14.2f, 8.2f); lineTo(11f, 13.2f); lineTo(14f, 16f); lineTo(13.2f, 20.8f)
        moveTo(11f, 13.2f); lineTo(7.8f, 15f); lineTo(6f, 13.8f)
        moveTo(14.2f, 8.2f); lineTo(17.6f, 10.4f); lineTo(19.4f, 9.4f)
        moveTo(13.4f, 9.2f); lineTo(10.4f, 9.4f); lineTo(8.8f, 11.4f)
    }
    val Bike = icon("bike") {
        circle(6f, 16f, 3.6f); circle(18f, 16f, 3.6f)
        moveTo(6f, 16f); lineTo(10f, 8.8f); lineTo(15f, 8.8f); lineTo(18f, 16f); moveTo(10f, 8.8f); lineTo(12.4f, 16f); lineTo(15f, 8.8f)
        moveTo(14.2f, 6.2f); lineTo(16.6f, 6.2f); moveTo(8.6f, 7f); lineTo(11.4f, 7f)
    }
    val Swim = icon("swim") {
        circle(16.4f, 7f, 1.8f)
        moveTo(7f, 12.6f); lineTo(12.4f, 9.4f); lineTo(14.8f, 12.4f)
        moveTo(2.8f, 16.2f); curveTo(4.6f, 14.4f, 6.4f, 14.4f, 8.2f, 16.2f); curveTo(10f, 18f, 11.8f, 18f, 13.6f, 16.2f); curveTo(15.4f, 14.4f, 17.2f, 14.4f, 19f, 16.2f); curveTo(19.8f, 17f, 20.6f, 17.4f, 21.4f, 17.4f)
        moveTo(2.8f, 20.4f); curveTo(4.6f, 18.6f, 6.4f, 18.6f, 8.2f, 20.4f); curveTo(10f, 22.2f, 11.8f, 22.2f, 13.6f, 20.4f)
    }
    val Dumbbell = icon("dumbbell") {
        moveTo(3.2f, 9.4f); lineTo(3.2f, 14.6f); moveTo(6.4f, 6.8f); lineTo(6.4f, 17.2f); moveTo(17.6f, 6.8f); lineTo(17.6f, 17.2f); moveTo(20.8f, 9.4f); lineTo(20.8f, 14.6f); moveTo(6.4f, 12f); lineTo(17.6f, 12f)
    }
    val Yoga = icon("yoga") {
        circle(12f, 5.2f, 1.9f)
        moveTo(12f, 8.2f); lineTo(12f, 13f); moveTo(6.6f, 11.4f); lineTo(12f, 9.2f); lineTo(17.4f, 11.4f)
        moveTo(5.4f, 18.4f); lineTo(12f, 14.6f); lineTo(18.6f, 18.4f); moveTo(8.4f, 18.4f); lineTo(15.6f, 18.4f)
    }
    val Ball = icon("ball") {
        circle(12f, 12f, 8.6f)
        moveTo(12f, 3.4f); curveTo(8.6f, 7.4f, 8.6f, 16.6f, 12f, 20.6f); moveTo(12f, 3.4f); curveTo(15.4f, 7.4f, 15.4f, 16.6f, 12f, 20.6f)
        moveTo(3.6f, 9.4f); lineTo(20.4f, 9.4f); moveTo(3.6f, 14.6f); lineTo(20.4f, 14.6f)
    }
    val Racket = icon("racket") {
        ellipse(14.4f, 9.2f, 4.6f, 5.8f)
        moveTo(11.4f, 13.4f); lineTo(5.2f, 20.4f); moveTo(11.6f, 6.4f); lineTo(17.2f, 12f)
    }
    val Mountain = icon("mountain") { moveTo(2.5f, 19.5f); lineTo(9f, 7.5f); lineTo(13f, 13.5f); lineTo(15.5f, 10f); lineTo(21.5f, 19.5f); close() }

    /** Which pictogram represents a sport. */
    fun forSport(id: String): ImageVector = when (id) {
        "run", "trail", "treadmill", "walk" -> Run
        "hike", "climb" -> Mountain
        "bike", "mtb", "spin" -> Bike
        "swim", "row" -> Swim
        "strength", "hiit", "crossfit", "boxing", "martial", "skip" -> Dumbbell
        "yoga", "pilates", "stretch", "meditate" -> Yoga
        "football", "cricket", "basketball", "volleyball", "kabaddi", "golf" -> Ball
        "badminton", "tennis", "tabletennis" -> Racket
        "stairs", "elliptical", "aerobics", "dance", "skate" -> Activity
        else -> Timer
    }
}

/** A sport pictogram in a soft rounded tile. */
@Composable
fun SportIcon(sp: SportDef, size: Dp = 44.dp) {
    val look = LocalRace.current
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(look.panelHi), contentAlignment = Alignment.Center) {
        Icon(AppIcons.forSport(sp.id), sp.name, Modifier.size(size * 0.62f), tint = look.text)
    }
}

/** Icon in a metric's own colour, on a faint tile of the same colour. */
@Composable
fun MetricIcon(icon: ImageVector, color: Color, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.56f), tint = color)
    }
}
