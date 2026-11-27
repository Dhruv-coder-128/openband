package com.openband.app.workout

import android.content.Context
import android.speech.tts.TextToSpeech
import com.openband.app.data.RoutePt
import com.openband.app.data.Workout
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Race against an earlier walk (or a target pace). The ghost covers ground exactly as it was covered then; the lead is
 * how much sooner (or later) you reach the same distance.
 */
object Ghost {

    class Choice(
        val label: String,
        /** The earlier track, or null for a target-pace ghost. */
        val track: List<RoutePt>?,
        /** Distance of the earlier workout as it was saved. */
        val meters: Double,
        val paceSecPerKm: Double?,
        val workoutId: Long?,
    ) {
        private val cum: DoubleArray by lazy {
            val t = track ?: return@lazy DoubleArray(0)
            val c = RouteMath.cumulative(t)
            val scale = if (c.isNotEmpty() && c.last() > 1 && meters > 1) meters / c.last() else 1.0
            DoubleArray(c.size) { c[it] * scale }
        }
        val totalMeters: Double get() = if (track == null) Double.MAX_VALUE else cum.lastOrNull() ?: 0.0

        /** Seconds the ghost needed to cover [d] metres; null if it never got that far. */
        fun timeAt(d: Double): Double? {
            val t = track ?: return paceSecPerKm?.let { d / 1000.0 * it }
            if (t.size < 2 || d > cum.last()) return null
            var lo = 0; var hi = cum.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (cum[m] < d) lo = m + 1 else hi = m }
            if (lo == 0) return t[0].t.toDouble()
            val seg = cum[lo] - cum[lo - 1]
            val f = if (seg > 0) (d - cum[lo - 1]) / seg else 0.0
            return t[lo - 1].t + (t[lo].t - t[lo - 1].t) * f
        }

        /** Where the ghost is [sec] seconds in: distance covered and, for a recorded track, the position. */
        fun at(sec: Double): Pair<Double, RoutePt?> {
            val t = track ?: return (paceSecPerKm?.let { sec / it * 1000.0 } ?: 0.0) to null
            if (t.size < 2) return 0.0 to null
            if (sec >= t.last().t) return cum.last() to t.last()
            var lo = 0; var hi = t.size - 1
            while (lo < hi) { val m = (lo + hi + 1) / 2; if (t[m].t <= sec) lo = m else hi = m - 1 }
            val a = t[lo]; val b = t[(lo + 1).coerceAtMost(t.size - 1)]
            val f = ((sec - a.t) / (b.t - a.t).coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val d = cum[lo] + (cum[(lo + 1).coerceAtMost(cum.size - 1)] - cum[lo]) * f
            return d to RoutePt(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
        }
    }

    fun of(label: String, w: Workout) = Choice(label, w.route, w.distanceM, null, w.id)
    fun target(secPerKm: Double) = Choice("Target %d:%02d /km".format((secPerKm / 60).toInt(), (secPerKm % 60).toInt()), null, 0.0, secPerKm, null)

    data class Live(
        val label: String,
        /** Seconds ahead (+) or behind (-); null until there is something to compare. */
        val aheadSec: Double?,
        val aheadM: Double?,
        val position: RoutePt?,
        val ghostFinished: Boolean,
        val youFinished: Boolean,
    )

    /** Picked on the start screen; used by the next workout. */
    val choice = MutableStateFlow<Choice?>(null)
    val live = MutableStateFlow<Live?>(null)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var racing: Choice? = null
    private var nextMark = 500.0
    private var leadSign = 0
    private var lastSpoken = 0L
    private var saidFinish = false

    private fun prefs(c: Context) = c.getSharedPreferences("ghost", Context.MODE_PRIVATE)
    fun voice(c: Context) = prefs(c).getBoolean("voice", true)
    fun setVoice(c: Context, on: Boolean) = prefs(c).edit().putBoolean("voice", on).apply()

    fun begin(ctx: Context) {
        end()
        val c = choice.value ?: return
        racing = c
        nextMark = 500.0; leadSign = 0; lastSpoken = 0L; saidFinish = false
        live.value = Live(c.label, null, null, c.at(0.0).second, false, false)
        if (voice(ctx)) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching { tts?.language = Locale.US }
                    ttsReady = true
                    say("Ghost race started. ${c.label.replace("/km", "per kilometre")}.")
                }
            }
        }
    }

    private fun say(text: String) {
        if (ttsReady) runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ghost-${System.nanoTime()}") }
        lastSpoken = System.currentTimeMillis()
    }

    private fun lead(sec: Double): String {
        val s = abs(sec).roundToInt()
        val time = if (s >= 60) "${s / 60} minute ${s % 60} seconds" else "$s seconds"
        return if (s < 3) "Level with your ghost" else if (sec > 0) "$time ahead" else "$time behind"
    }

    fun update(meters: Double, elapsedMs: Long) {
        val c = racing ?: return
        val sec = elapsedMs / 1000.0
        val (gd, pos) = c.at(sec)
        val youFinished = c.track != null && meters >= c.totalMeters - 5
        val gTime = c.timeAt(if (youFinished) c.totalMeters else meters)
        // once you have covered the ghost's whole distance the result is fixed
        val previous = live.value
        val ahead = if (youFinished && saidFinish) previous?.aheadSec else if (meters > 30 && gTime != null) gTime - sec else null
        live.value = Live(c.label, ahead, if (meters > 30) meters - gd else null, pos, c.track != null && gd >= c.totalMeters - 1, youFinished)
        if (ahead == null) return
        if (youFinished) {
            if (!saidFinish) {
                saidFinish = true
                say(if (ahead >= 0) "You beat your ghost by ${abs(ahead).roundToInt()} seconds." else "Your ghost won by ${abs(ahead).roundToInt()} seconds.")
            }
            return
        }
        val sign = if (ahead > 6) 1 else if (ahead < -6) -1 else leadSign
        if (meters >= nextMark) {
            say("${if (nextMark >= 1000) "%.1f kilometres".format(Locale.US, nextMark / 1000) else "${nextMark.toInt()} metres"}. ${lead(ahead)}.")
            nextMark += 500.0
            leadSign = sign
        } else if (sign != leadSign && System.currentTimeMillis() - lastSpoken > 30_000) {
            leadSign = sign
            say(if (sign > 0) "You have taken the lead." else "Your ghost has passed you.")
        }
    }

    fun end() {
        racing = null
        live.value = null
        val t = tts
        tts = null; ttsReady = false
        // let a final sentence finish before the engine goes away
        if (t != null) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { t.shutdown() } }, 6000)
    }
}
