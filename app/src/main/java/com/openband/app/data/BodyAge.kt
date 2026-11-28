package com.openband.app.data

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Body age: your real age moved up or down by how your last 30 days compare with healthy habits and typical values.
 * Our own estimate for motivation - not Pebble's formula and not a medical measurement.
 */
object BodyAge {
    const val DAYS = 30
    /** Readings needed before a number is shown. */
    const val MIN_FACTORS = 3

    data class Input(
        val age: Int, val heightCm: Int, val weightKg: Int,
        val restingHr: Double?, val hrv: Double?, val sleepHours: Double?, val dailySteps: Double?,
        /** Minutes of recorded exercise in an average week; null when there is too little history to judge. */
        val weeklyExerciseMin: Double?,
    )

    /** [years] below zero makes you younger. */
    data class Factor(val name: String, val value: String, val years: Double, val tip: String)

    data class Result(val bodyAge: Int, val age: Int, val factors: List<Factor>, val days: Int = 0) {
        val difference get() = bodyAge - age
        val headline get() = when {
            difference < 0 -> "${abs(difference)} year${if (abs(difference) == 1) "" else "s"} younger than your age"
            difference > 0 -> "$difference year${if (difference == 1) "" else "s"} older than your age"
            else -> "The same as your age"
        }
        /** The change that would help most. */
        val focus: Factor? get() = factors.filter { it.years > 0.4 }.maxByOrNull { it.years }
    }

    /** Typical overnight heart rate variability (ms) by age, roughly, from population studies. */
    fun usualHrv(age: Int): Double {
        val points = listOf(20 to 60.0, 30 to 47.0, 40 to 37.0, 50 to 30.0, 60 to 24.0, 70 to 20.0)
        if (age <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            if (age <= x1) {
                val (x0, y0) = points[i - 1]
                return y0 + (y1 - y0) * (age - x0) / (x1 - x0)
            }
        }
        return points.last().second
    }

    fun compute(i: Input): Result? {
        val list = ArrayList<Factor>()
        i.restingHr?.let {
            list += Factor("Resting heart rate", "${it.roundToInt()} bpm", ((it - 65) * 0.2).coerceIn(-5.0, 6.0), "Regular walking or running lowers it over weeks.")
        }
        i.hrv?.let {
            val usual = usualHrv(i.age)
            list += Factor("Heart rate variability", "${it.roundToInt()} ms", ((usual - it) / usual * 8).coerceIn(-5.0, 5.0), "Steady sleep times, less late caffeine and calm breathing help.")
        }
        i.sleepHours?.let {
            val y = when { it < 7 -> ((7 - it) * 2).coerceAtMost(5.0); it <= 9 -> -1.5; else -> ((it - 9) * 1.0).coerceAtMost(2.0) }
            list += Factor("Sleep", Stats.fmtMinutes(it * 60) + " a night", y, "Aim for 7 to 9 hours.")
        }
        i.dailySteps?.let {
            list += Factor("Daily steps", "%,d".format(it.roundToInt()), ((7500 - it) / 2500 * 1.5).coerceIn(-4.0, 4.0), "Every extra 2,000 steps a day counts.")
        }
        if (i.heightCm in 100..230 && i.weightKg in 30..250) {
            val bmi = i.weightKg / ((i.heightCm / 100.0) * (i.heightCm / 100.0))
            val y = when { bmi < 18.5 -> 1.0; bmi <= 25 -> -1.0; else -> ((bmi - 25) * 0.4).coerceAtMost(6.0) }
            list += Factor("Body mass index", "%.1f".format(bmi), y, "From the height and weight in Goals & units.")
        }
        i.weeklyExerciseMin?.let {
            val y = when { it >= 150 -> -2.0; it >= 75 -> -1.0; it >= 30 -> 0.0; else -> 1.0 }
            list += Factor("Exercise", "${it.roundToInt()} min a week", y, "150 minutes a week is the usual health target.")
        }
        // height and weight alone say nothing about the last month
        if (list.count { it.name != "Body mass index" } < MIN_FACTORS) return null
        val body = (i.age + list.sumOf { it.years }).roundToInt().coerceIn(maxOf(14, i.age - 12), i.age + 15)
        return Result(body, i.age, list)
    }

    /** Averages of the last [DAYS] days; null until at least 3 days have data. */
    fun fromHistory(db: Db, age: Int, heightCm: Int, weightKg: Int, today: LocalDate = LocalDate.now()): Result? {
        // finished days only: today's steps and exercise are still growing
        val dates = (1..DAYS).map { today.minusDays(it.toLong()) }
        val sleeps = dates.associateWith { Stats.sleepFor(db, it) }
        fun <T> avg(values: List<T>, min: Int, f: (T) -> Double) = values.takeIf { it.size >= min }?.map(f)?.average()
        val rhr = avg(dates.mapNotNull { com.openband.app.ui.restingHr(db, sleeps[it], it).first }, 3) { it }
        val hrv = avg(dates.mapNotNull { Stats.dayValue(db, M.HRV, it) }, 3) { it }
        val sleep = avg(sleeps.values.mapNotNull { it?.asleep }.filter { it >= 120 }, 3) { it / 60.0 }
        val stepDays = dates.mapNotNull { Stats.dayValue(db, M.STEPS, it)?.takeIf { v -> v > 0 } }
        val steps = avg(stepDays, 3) { it }
        val from = Stats.dayStart(today.minusDays(DAYS.toLong()))
        val workouts = db.workouts(1000).filter { it.start >= from && it.start < Stats.dayStart(today) }
        val exercise = if (stepDays.size >= 7) workouts.sumOf { it.movingMs } / 60_000.0 / (stepDays.size / 7.0) else null
        return compute(Input(age, heightCm, weightKg, rhr, hrv, sleep, steps, exercise))?.copy(days = stepDays.size)
    }
}
