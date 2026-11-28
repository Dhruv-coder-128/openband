package com.openband.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.sin

/**
 * Where the phone says the liquid should settle: how far it leans left or right (-1..1). The sensor only sets the target;
 * [Slosh] moves the surface there frame by frame. Listens only while the screen is showing.
 */
@Composable
private fun rememberTilt(enabled: Boolean): () -> Float {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val target = remember { floatArrayOf(0f) } // not Compose state: it is read once per frame by the animation
    DisposableEffect(enabled, owner) {
        val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // the gravity sensor already filters out hand shake; plain accelerometers are filtered here
        val gravity = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = gravity ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = (e.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                target[0] = if (gravity != null) x else target[0] + (x - target[0]) * 0.2f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        fun start() { if (enabled && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) }
        val watch = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) start()
            if (event == Lifecycle.Event.ON_PAUSE) manager.unregisterListener(listener)
        }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch); manager.unregisterListener(listener) }
    }
    return { target[0] }
}

/** A number that rolls up to its value when the screen opens. */
@Composable
fun countUp(value: Double?): Double? {
    val look = LocalRace.current
    val roll = look.style == UiStyle.BOLD && look.prefs.boldLiquid && look.prefs.animate
    val anim = remember { Animatable(0f) }
    LaunchedEffect(roll, value) { if (roll && value != null) anim.animateTo(value.toFloat(), tween(1100, easing = FastOutSlowInEasing)) }
    return if (roll && value != null) anim.value.toDouble() else value
}

/**
 * Bold's step card: the card is a glass that fills as you walk. The surface rolls gently, leans with the phone and
 * splashes when tapped; once the goal is reached bubbles rise through it.
 */
@Composable
fun LiquidCard(accent: Color, level: Float, content: @Composable () -> Unit) {
    val look = LocalRace.current
    val animate = look.prefs.animate
    val tilt = rememberTilt(animate)
    var lean by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    val splash = remember { Animatable(0f) }
    var taps by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it }
        var last = start
        val slosh = Slosh()
        while (true) withFrameNanos { now ->
            lean = slosh.step(tilt(), (now - last) / 1e9f)
            last = now
            time = (now - start) / 1e9f
        }
    }
    LaunchedEffect(taps) { if (taps > 0f) { splash.snapTo(1f); splash.animateTo(0f, tween(1600)) } }
    // the glass fills up from empty each time the screen opens
    val fill = remember { Animatable(0f) }
    LaunchedEffect(level) { fill.animateTo(level.coerceIn(0f, 1f), tween(if (animate) 1400 else 0, easing = FastOutSlowInEasing)) }

    // both shades are dark enough for the white (or black) text on top
    val darkText = isLightAccent(accent)
    val glass = lerp(accent, if (darkText) Color.White else Color.Black, 0.34f)
    val shape = look.panelShape
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).background(glass)
            .clickable(remember { MutableInteractionSource() }, indication = null) { taps += 1f },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            val base = h * (1f - (0.07f + 0.86f * fill.value))
            val slope = lean * h * 0.22f
            val rough = 5.dp.toPx() + 16.dp.toPx() * splash.value
            fun surface(x: Float, phase: Float, speed: Float): Float {
                val u = x / w
                return base + slope * (u - 0.5f) * 2f + rough * sin(u * 2f * PI.toFloat() * 1.3f + time * speed + phase) +
                    rough * 0.45f * sin(u * 2f * PI.toFloat() * 2.9f - time * speed * 1.4f + phase)
            }
            fun body(phase: Float, speed: Float, color: Color) {
                val path = Path()
                path.moveTo(0f, h)
                var x = 0f
                while (x <= w + 8f) { path.lineTo(x, surface(x.coerceAtMost(w), phase, speed)); x += 8f }
                path.lineTo(w, h); path.close()
                drawPath(path, color)
            }
            body(2.4f, 1.3f, accent.copy(alpha = 0.55f))     // back wave
            body(0f, 1.9f, accent)                            // front wave
            // bubbles: a few always, many once the goal is reached
            val bubbles = if (fill.value >= 0.999f) 16 else 6
            for (i in 0 until bubbles) {
                val u = ((i * 0.6180339f) % 1f)
                val rise = (time * (0.07f + (i % 4) * 0.025f) + i * 0.37f) % 1f
                val bx = w * u + sin(time * 1.7f + i) * 5.dp.toPx()
                val top = surface(bx.coerceIn(0f, w), 0f, 1.9f)
                val by = h - (h - top) * rise
                if (by > top + 6.dp.toPx()) drawCircle(Color.White.copy(alpha = 0.22f * (1f - rise * 0.6f)), (2 + i % 4).dp.toPx(), Offset(bx, by), style = Stroke(1.2.dp.toPx()))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) { content() }
    }
}

private fun isLightAccent(c: Color) = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue > 0.6f
