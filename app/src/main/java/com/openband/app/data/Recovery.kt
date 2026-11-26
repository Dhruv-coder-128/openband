package com.openband.app.data

import com.openband.app.ui.restingHr
import java.time.LocalDate

/**
 * Morning readiness score 1..100. Our own formula (not Whoop's and not medical):
 * last night's HRV and resting heart rate compared with your own previous four weeks, plus sleep against your goal.
 */
object Recovery {

    data class Part(
        val name: String, val value: String, val usual: String?,
        /** 0..100 for this ingredient. */
        val score: Int, val weight: Int,
    )

    data class Result(
        val score: Int, val parts: List<Part>,
        /** Earlier nights available for comparison; under [GOOD_BASELINE] the score is provisional. */
        val baselineNights: Int,
    ) {
        val provisional get() = baselineNights < GOOD_BASELINE
        val label get() = when { score >= 67 -> "Ready"; score >= 34 -> "Moderate"; else -> "Low" }
        val tone get() = when { score >= 67 -> 0; score >= 34 -> 1; else -> 2 }
        val advice get() = when {
            score >= 67 -> "Your body looks well rested. A good day for a harder workout."
            score >= 34 -> "A normal day. Keep your usual activity and aim for a full night's sleep."
            else -> "Your body may still be tired. Go easy today and sleep earlier tonight."
        }
    }

    const val GOOD_BASELINE = 7

    private class Night(val sleep: Stats.Sleep?, val hrv: Double?, val rhr: Double?, val temp: Double?)

    private fun night(db: Db, date: LocalDate): Night {
        val sleep = Stats.sleepFor(db, date)
        val from = sleep?.start ?: Stats.dayStart(date)
        val to = sleep?.end ?: (Stats.dayStart(date) + 12 * 3_600_000L)
        return Night(
            sleep,
            Stats.mma(db.series(M.HRV, from, to + 1))?.avg ?: Stats.mma(db.series(M.HRV, Stats.dayStart(date), Stats.dayStart(date) + 12 * 3_600_000L))?.avg,
            restingHr(db, sleep, date).first,
            Stats.mma(db.series(M.TEMP, from, to + 1))?.avg,
        )
    }

    fun compute(db: Db, date: LocalDate, sleepGoalMin: Int): Result? {
        val tonight = night(db, date)
        // without anything measured last night there is nothing to judge
        if (tonight.sleep == null && tonight.hrv == null) return null
        val past = (1..28).map { night(db, date.minusDays(it.toLong())) }
        fun usual(f: (Night) -> Double?): Pair<Double?, Int> {
            val v = past.mapNotNull(f)
            return (if (v.size >= 2) v.average() else null) to v.size
        }
        val parts = ArrayList<Part>()
        val (hrvUsual, hrvN) = usual { it.hrv }
        tonight.hrv?.let { v ->
            val s = if (hrvUsual != null) 70 + (v / hrvUsual - 1) * 150 else 70.0
            parts += Part("Heart rate variability", "${v.toInt()} ms", hrvUsual?.let { "usual ${it.toInt()} ms" }, s.toInt().coerceIn(1, 100), 40)
        }
        val (rhrUsual, rhrN) = usual { it.rhr }
        tonight.rhr?.let { v ->
            val s = if (rhrUsual != null) 70 - (v / rhrUsual - 1) * 350 else 70.0
            parts += Part("Resting heart rate", "${v.toInt()} bpm", rhrUsual?.let { "usual ${it.toInt()} bpm" }, s.toInt().coerceIn(1, 100), 30)
        }
        tonight.sleep?.let { sl ->
            val s = sl.asleep / sleepGoalMin * 100
            parts += Part("Sleep", Stats.fmtMinutes(sl.asleep), "goal ${Stats.fmtMinutes(sleepGoalMin.toDouble())}", s.toInt().coerceIn(1, 100), 30)
        }
        if (parts.isEmpty()) return null
        var score = parts.sumOf { it.score * it.weight }.toDouble() / parts.sumOf { it.weight }
        // a clearly warmer wrist than usual is worth holding back for
        val (tempUsual, _) = usual { it.temp }
        val t = tonight.temp
        if (t != null && tempUsual != null && t - tempUsual >= 0.5) {
            score -= 10
            parts += Part("Skin temperature", "%.1f °C".format(t), "usual %.1f °C".format(tempUsual), 30, 0)
        }
        return Result(score.toInt().coerceIn(1, 100), parts, maxOf(hrvN, rhrN, past.count { it.sleep != null }))
    }
}
