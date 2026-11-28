package com.openband.app.data

import kotlin.math.roundToInt

/**
 * Body energy, 0..100: how much is left in the tank right now. Our own estimate (not Pebble's or Garmin's formula and
 * not medical). The day starts from last night's recovery and sleep, then every quarter hour awake costs a little,
 * effort (heart rate above resting, or steps when there is no heart rate) and stress cost more, and calm rest gives
 * some back.
 */
object BodyEnergy {
    const val SLOT_MS = 15 * 60_000L

    /** What was measured in one quarter hour. */
    data class Slot(val start: Long, val hr: Double?, val steps: Double, val stress: Double?, val asleep: Boolean = false)

    data class Point(val at: Long, val level: Int)

    data class Result(
        val now: Int, val atWake: Int, val points: List<Point>,
        /** Points used up (or given back) since waking, by cause. */
        val usedByActivity: Int, val usedByStress: Int, val usedByBeingAwake: Int, val gainedByRest: Int,
    ) {
        val label get() = when { now >= 70 -> "High"; now >= 40 -> "Medium"; now >= 15 -> "Low"; else -> "Very low" }
        val tone get() = when { now >= 40 -> 0; now >= 15 -> 1; else -> 2 }
        val advice get() = when {
            now >= 70 -> "Plenty left. A good time for your harder tasks or a workout."
            now >= 40 -> "A comfortable level. Keep a steady pace."
            now >= 15 -> "Running low. Take it easy and plan an earlier night."
            else -> "Nearly empty. Rest, eat well and get to bed soon."
        }
    }

    /** Level on waking: mostly recovery when we have it, otherwise how much of the sleep goal was slept. */
    fun morningLevel(recovery: Int?, asleepMin: Double?, goalMin: Int): Int? {
        val sleep = asleepMin?.let { (it / goalMin).coerceIn(0.0, 1.0) * 100 }
        val raw = when {
            recovery != null && sleep != null -> 0.55 * sleep + 0.45 * recovery
            sleep != null -> sleep
            recovery != null -> recovery.toDouble()
            else -> return null
        }
        return (15 + raw * 0.85).roundToInt().coerceIn(20, 100)
    }

    fun compute(start: Int, slots: List<Slot>, restingHr: Double?, maxHr: Int): Result {
        var level = start.toDouble()
        var activity = 0.0
        var stress = 0.0
        var awake = 0.0
        var rest = 0.0
        val points = ArrayList<Point>()
        slots.firstOrNull()?.let { points += Point(it.start, start) }
        var lastHr: Double? = null
        for (s in slots) {
            if (s.hr != null) lastHr = s.hr
            val hr = s.hr ?: lastHr
            // share of the heart-rate reserve in use: 0 at rest, 1 at maximum
            val effort = if (hr != null && restingHr != null && maxHr > restingHr) ((hr - restingHr) / (maxHr - restingHr)).coerceIn(0.0, 1.0) else null
            if (s.asleep) {
                val gain = minOf(3.0, 100 - level)
                level += gain; rest += gain
            } else {
                val base = 0.5
                // steps count too: with a reading only every few minutes a walk is easy to miss in the heart rate
                val byHeart = if (effort != null) (effort - 0.25).coerceAtLeast(0.0) * 9 else 0.0
                val bySteps = s.steps / 1000.0 * 1.6
                val move = maxOf(byHeart, bySteps)
                val tense = s.stress?.let { ((it - 50) / 50.0).coerceAtLeast(0.0) * 1.2 } ?: 0.0
                val calm = s.stress != null && s.stress < 30 && s.steps < 50 && (effort == null || effort < 0.15)
                val d1 = minOf(base, level); level -= d1; awake += d1
                val d2 = minOf(move, level); level -= d2; activity += d2
                val d3 = minOf(tense, level); level -= d3; stress += d3
                if (calm) { val gain = minOf(1.0, 100 - level); level += gain; rest += gain }
            }
            points += Point(s.start + SLOT_MS, level.roundToInt().coerceIn(0, 100))
        }
        return Result(level.roundToInt().coerceIn(0, 100), start, points, activity.roundToInt(), stress.roundToInt(), awake.roundToInt(), rest.roundToInt())
    }

    /** Today's energy from the database; null until last night's sleep (or recovery) is known. */
    fun today(db: Db, sleepGoalMin: Int, maxHr: Int, now: Long = System.currentTimeMillis()): Result? {
        val date = java.time.LocalDate.now()
        val sleep = Stats.sleepFor(db, date) ?: return null
        val recovery = Recovery.compute(db, date, sleepGoalMin)?.score
        val start = morningLevel(recovery, sleep.asleep, sleepGoalMin) ?: return null
        val wake = sleep.end.coerceAtMost(now)
        val hr = db.series(M.HR, wake, now)
        // the same movement can arrive from the band and through the Pebble app: use one source only
        val steps = db.series(M.STEPS, wake, now).groupBy { it.source }.values.maxByOrNull { l -> l.sumOf { it.value } } ?: emptyList()
        val stress = db.series(M.STRESS, wake, now)
        val naps = db.series(M.SLEEP_LIGHT, wake, now) + db.series(M.SLEEP_DEEP, wake, now)
        val resting = com.openband.app.ui.restingHr(db, sleep, date).first
        val slots = generateSequence(wake) { it + SLOT_MS }.takeWhile { it + SLOT_MS <= now }.map { from ->
            val to = from + SLOT_MS
            Slot(
                from,
                hr.filter { it.ts in from until to }.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
                steps.filter { it.ts in from until to }.sumOf { it.value },
                stress.filter { it.ts in from until to }.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
                naps.any { it.ts < to && it.endTs > from },
            )
        }.toList()
        return compute(start, slots, resting, maxHr)
    }
}
