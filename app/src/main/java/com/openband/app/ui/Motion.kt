package com.openband.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/*
 * What makes each look feel like itself on every page: how content arrives, how headings are written, and the shapes of
 * controls and charts. Everything here leaves the Bold look exactly as it is.
 */

/** Cards that appear together arrive one after another. */
private object Stagger {
    private var last = 0L
    private var n = 0
    fun next(): Int {
        val now = System.currentTimeMillis()
        if (now - last > 150) n = 0
        last = now
        return (n++).coerceAtMost(9)
    }
}

/** The way a block enters the screen in the current look. */
fun Modifier.reveal(): Modifier = composed {
    val look = LocalRace.current
    val style = look.style
    if (style == UiStyle.BOLD || !look.prefs.animate) return@composed this
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val slot = Stagger.next()
        val (gap, length) = when (style) {
            UiStyle.TERMINAL -> 130 to 420
            UiStyle.HACKER -> 80 to 520
            UiStyle.RACING -> 55 to 260
            UiStyle.CYBER -> 60 to 380
            UiStyle.RINGS -> 60 to 380
            UiStyle.MONO -> 45 to 200
            UiStyle.MIDNIGHT -> 70 to 520
            UiStyle.PAPER -> 80 to 480
            else -> 50 to 320
        }
        delay((slot * gap).toLong())
        val linear = style == UiStyle.TERMINAL || style == UiStyle.HACKER || style == UiStyle.CYBER
        t.animateTo(1f, tween(length, easing = if (linear) LinearEasing else FastOutSlowInEasing))
    }
    when (style) {
        // printed line by line, with a cursor where the next line will come
        UiStyle.TERMINAL -> drawWithContent {
            val f = t.value
            if (f >= 1f) drawContent() else {
                val line = 21.dp.toPx()
                val shown = floor(f * ceil(size.height / line)) * line
                clipRect(bottom = shown) { this@drawWithContent.drawContent() }
                if (f > 0f) drawRect(look.text, Offset(14.dp.toPx(), shown.coerceAtMost(size.height - line)), Size(9.dp.toPx(), 16.dp.toPx()))
            }
        }
        // a scan line draws the block, flickering at first
        UiStyle.HACKER -> graphicsLayer { alpha = if (t.value < 0.25f && (t.value * 28).toInt() % 2 == 0) 0.35f else if (t.value == 0f) 0f else 1f }.drawWithContent {
            val f = t.value
            if (f >= 1f) drawContent() else {
                val y = size.height * (f * 1.15f).coerceAtMost(1f)
                clipRect(bottom = y) { this@drawWithContent.drawContent() }
                drawRect(look.primary, Offset(0f, y - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
            }
        }
        UiStyle.RACING -> graphicsLayer { alpha = t.value; translationX = (1f - t.value) * 110.dp.toPx() }
        UiStyle.CYBER -> graphicsLayer {
            val f = t.value
            alpha = if (f == 0f) 0f else 1f
            translationX = if (f < 1f) (sin(f * 40f) * (1f - f) * 22.dp.toPx()) else 0f
        }
        UiStyle.RINGS -> graphicsLayer { alpha = t.value; scaleX = 0.88f + 0.12f * t.value; scaleY = scaleX }
        UiStyle.MONO -> graphicsLayer { alpha = if (t.value == 0f) 0f else 1f; translationX = (t.value - 1f) * 36.dp.toPx() }
        UiStyle.MIDNIGHT -> graphicsLayer { alpha = t.value }
        else -> graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 18.dp.toPx() }
    }
}


private const val NOISE = "#%&*<>/\\|01ｱｳｴｵｶｷｸ"

/** A heading in the look's own manner: typed out in Terminal, decoded from noise in Hacker, plain elsewhere. */
@Composable
fun themedText(text: String): String {
    val look = LocalRace.current
    val style = look.style
    if ((style != UiStyle.TERMINAL && style != UiStyle.HACKER) || !look.prefs.animate) return text
    var shown by remember(text) { mutableStateOf(0) }
    var tick by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        while (shown < text.length) { delay(if (style == UiStyle.TERMINAL) 26 else 30); shown++; tick++ }
    }
    if (shown >= text.length) return text
    return if (style == UiStyle.TERMINAL) text.take(shown) + "▌"
    else text.take(shown) + buildString { for (i in shown until text.length) append(if (text[i] == ' ') ' ' else NOISE[(i * 7 + tick * 3) % NOISE.length]) }
}

val RaceLook.isConsole: Boolean get() = style == UiStyle.HACKER || style == UiStyle.TERMINAL

/** Rounded pill everywhere except the console looks, which use their own button shape. */
fun RaceLook.pill(): Shape = if (isConsole) buttonShape else RoundedCornerShape(50)

fun RaceLook.tile(radius: Int): Shape = if (isConsole) panelShape else RoundedCornerShape(radius.dp)

/** A chart bar: stacked blocks in the console looks, a rounded bar otherwise. */
fun DrawScope.themedBar(look: RaceLook, color: Color, x: Float, top: Float, width: Float, bottom: Float, radius: Float) {
    if (!look.isConsole) {
        drawRoundRect(color, Offset(x, top), Size(width, bottom - top), CornerRadius(radius))
        return
    }
    val block = 5.dp.toPx(); val gap = 2.dp.toPx()
    var y = bottom - block
    do {
        drawRect(color, Offset(x, y), Size(width, block))
        y -= block + gap
    } while (y >= top - 1f)
}

/** A page title in the current look: prompt and typing effect in the console looks, plain elsewhere. */
@Composable
fun heading(title: String): String {
    val look = LocalRace.current
    return if (look.isConsole) look.style.prompt + themedText(title.lowercase()) else title
}

/** Screen heading for the console looks. */
@Composable
fun ConsoleTitle(title: String, sub: String?, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    val path = title.lowercase().replace(' ', '-')
    val user = look.prefs.displayName.ifEmpty { "user" }.lowercase().replace(' ', '_')
    if (look.style == UiStyle.TERMINAL) Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).background(look.panelHi).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(Color(0xFFE06C75), Color(0xFFE5C07B), Color(0xFF98C379)).forEach { Box(Modifier.padding(end = 6.dp).size(9.dp).clip(CircleShape).background(it)) }
            Text("$user@qore2: ~/$path", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().background(look.panel).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("$ " + themedText("openband $path"), style = MaterialTheme.typography.titleLarge, color = look.primary, maxLines = 1)
            if (sub != null) Text("# $sub", style = MaterialTheme.typography.bodySmall, color = look.dim)
        }
    } else Column(modifier.fillMaxWidth().border(1.dp, look.border, look.panelShape).background(look.panel.copy(alpha = 0.85f), look.panelShape).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("[OPENBAND::${path.uppercase()}]", style = MaterialTheme.typography.labelSmall, color = look.dim, maxLines = 1)
        Text(themedText("> " + title.uppercase()), style = MaterialTheme.typography.headlineMedium, color = look.primary, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = look.dim)
    }
}
