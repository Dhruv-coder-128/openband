package com.openband.app.workout

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** The extra things some sports record while they run: lifting sets, interval rounds, pool lengths. */
object Training {

    data class Intervals(val work: Int = 40, val rest: Int = 20, val rounds: Int = 8, val running: Boolean = false, val round: Int = 0, val working: Boolean = true, val left: Int = 0, val done: Int = 0)
    data class Pool(val poolM: Int = 25, val lengths: Int = 0, val lastLengthAt: Int = 0, val lastLengthSec: Int = 0)

    val sets = MutableStateFlow<List<Strength.SetEntry>>(emptyList())
    /** Wall-clock time when the current rest ends; 0 = not resting. */
    val restUntil = MutableStateFlow(0L)
    val restSeconds = MutableStateFlow(90)
    val intervals = MutableStateFlow(Intervals())
    val pool = MutableStateFlow(Pool())

    fun isLifting(sport: String) = sport == "strength" || sport == "compound" || sport == "crossfit"
    fun isInterval(sport: String) = sport == "hiit" || sport == "boxing" || sport == "skip" || sport == "crossfit"
    fun isSwim(sport: String) = sport == "swim"
    fun isCalm(sport: String) = sport == "yoga" || sport == "pilates" || sport == "stretch" || sport == "meditate"

    private var ctx: Context? = null

    fun reset(c: Context) {
        ctx = c.applicationContext
        sets.value = emptyList()
        restUntil.value = 0
        intervals.value = intervals.value.copy(running = false, round = 0, done = 0, left = 0, working = true)
        pool.value = pool.value.copy(lengths = 0, lastLengthAt = 0, lastLengthSec = 0)
    }

    fun addSet(s: Strength.SetEntry) {
        sets.value = sets.value + s
        restUntil.value = System.currentTimeMillis() + restSeconds.value * 1000L
    }

    fun removeSet(index: Int) { sets.value = sets.value.filterIndexed { i, _ -> i != index } }

    fun startIntervals() { intervals.value = intervals.value.let { it.copy(running = true, round = 1, working = true, left = it.work, done = 0) }; signal(2) }
    fun stopIntervals() { intervals.value = intervals.value.copy(running = false, left = 0) }

    fun addLength(atSec: Int, delta: Int) {
        val p = pool.value
        val n = (p.lengths + delta).coerceAtLeast(0)
        pool.value = if (delta > 0) p.copy(lengths = n, lastLengthSec = atSec - p.lastLengthAt, lastLengthAt = atSec) else p.copy(lengths = n)
    }

    /** Called once a second by the running workout (not while paused). */
    fun tick() {
        val rest = restUntil.value
        if (rest > 0 && System.currentTimeMillis() >= rest) { restUntil.value = 0; signal(2) }
        val i = intervals.value
        if (!i.running) return
        if (i.left > 1) {
            intervals.value = i.copy(left = i.left - 1)
            if (i.left - 1 <= 3) signal(0)
        } else if (i.working) {
            val done = i.done + 1
            if (done >= i.rounds) { intervals.value = i.copy(running = false, done = done, left = 0); signal(3) }
            else { intervals.value = i.copy(working = false, left = i.rest, done = done); signal(1) }
        } else {
            intervals.value = i.copy(working = true, round = i.round + 1, left = i.work); signal(2)
        }
    }

    /** 0 = countdown tick, 1 = rest begins, 2 = go, 3 = finished. Vibration plus a short tone on the media volume. */
    private fun signal(kind: Int) {
        val c = ctx ?: return
        runCatching {
            val v = c.getSystemService(Vibrator::class.java)
            val pattern = when (kind) { 0 -> longArrayOf(0, 40); 1 -> longArrayOf(0, 250); 2 -> longArrayOf(0, 150, 100, 150); else -> longArrayOf(0, 200, 100, 200, 100, 400) }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            val (t, ms) = when (kind) { 0 -> ToneGenerator.TONE_PROP_BEEP to 80; 1 -> ToneGenerator.TONE_PROP_BEEP2 to 250; 2 -> ToneGenerator.TONE_PROP_ACK to 250; else -> ToneGenerator.TONE_PROP_PROMPT to 500 }
            tone.startTone(t, ms)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, ms + 200L)
        }
    }

    /** What gets saved with the workout. */
    fun extra(sport: String, method: String): String {
        val o = JSONObject().put("kcalMethod", method)
        if (sets.value.isNotEmpty()) o.put("sets", Strength.toJson(sets.value))
        if (isSwim(sport) && pool.value.lengths > 0) o.put("poolM", pool.value.poolM).put("lengths", pool.value.lengths)
        val i = intervals.value
        if (isInterval(sport) && i.done > 0) o.put("rounds", i.done).put("work", i.work).put("rest", i.rest)
        return o.toString()
    }

    fun parse(extra: String): JSONObject = runCatching { JSONObject(extra) }.getOrDefault(JSONObject())

    fun setsOf(extra: String): List<Strength.SetEntry> = Strength.fromJson(parse(extra).optJSONArray("sets"))
}
