package com.openband.app.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Sunday-night look back at the week (Monday to Sunday). */
object WeeklyRecap {
    const val HOUR = 20

    data class Recap(
        val from: LocalDate, val to: LocalDate,
        val totalSteps: Int, val avgSteps: Int, val goalDays: Int, val bestDay: Pair<LocalDate, Int>?,
        /** Change of average steps against the week before, in %. */
        val stepsChange: Int?,
        val distanceM: Double, val activeKcal: Int,
        val avgSleepMin: Double?, val sleepChangeMin: Double?,
        val restingHr: Double?, val restingChange: Double?,
        val workouts: Int, val workoutMs: Long, val workoutDistanceM: Double,
        val days: List<Pair<LocalDate, Int?>>,
        val headline: String, val good: List<String>, val tryNext: String,
    ) {
        val short get() = "%,d steps".format(totalSteps) +
            (avgSleepMin?.let { " · ${Stats.fmtMinutes(it)} sleep a night" } ?: "") +
            " · $workouts workout" + if (workouts == 1) "" else "s"
    }

    fun build(week: Insights.Period, before: Insights.Period, stepGoal: Int, sleepGoalMin: Int): Recap? {
        val stepDays = week.days.mapNotNull { d -> d.steps?.takeIf { it > 0 }?.let { d.date to it.roundToInt() } }
        val sleeps = week.days.mapNotNull { it.sleep?.takeIf { v -> v >= 120 } }
        if (stepDays.isEmpty() && sleeps.isEmpty() && week.workouts.isEmpty()) return null
        val total = stepDays.sumOf { it.second }
        val avg = if (stepDays.isEmpty()) 0 else total / stepDays.size
        val prevSteps = before.days.mapNotNull { it.steps?.takeIf { v -> v > 0 } }
        // two or three days are too few to call a trend
        val change = if (stepDays.size >= 4 && prevSteps.size >= 4) ((avg / prevSteps.average() - 1) * 100).roundToInt() else null
        val sleep = sleeps.takeIf { it.isNotEmpty() }?.average()
        val prevSleep = before.days.mapNotNull { it.sleep?.takeIf { v -> v >= 120 } }.takeIf { it.size >= 3 }?.average()
        val rhr = week.avg { it.restingHr }
        val prevRhr = before.avg { it.restingHr }
        val goalDays = stepDays.count { it.second >= stepGoal }
        val best = stepDays.maxByOrNull { it.second }
        val moving = week.workouts.sumOf { it.movingMs }

        val good = ArrayList<String>()
        if (goalDays > 0) good += "You reached your step goal on $goalDays day${if (goalDays == 1) "" else "s"}."
        if (change != null && change >= 5) good += "You walked $change% more than the week before."
        if (sleep != null && sleep >= sleepGoalMin * 0.95) good += "Your sleep was on target."
        if (week.workouts.size >= 3) good += "${week.workouts.size} workouts: a solid training week."
        if (rhr != null && prevRhr != null && prevRhr - rhr >= 2) good += "Resting heart rate dropped by ${(prevRhr - rhr).roundToInt()} bpm."
        best?.let { good += "Best day: ${it.first.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} with ${"%,d".format(it.second)} steps." }

        val next = when {
            sleep != null && sleep < sleepGoalMin - 45 -> "Go to bed ${((sleepGoalMin - sleep) / 2).roundToInt().coerceIn(15, 60)} minutes earlier next week."
            moving < 150 * 60_000L && avg >= stepGoal * 0.7 -> "Add one or two longer workouts to reach 150 active minutes."
            stepDays.isNotEmpty() && avg < stepGoal -> "Aim for ${"%,d".format(((avg + 1000) / 500) * 500)} steps a day, a little above this week."
            else -> "Keep the same rhythm going."
        }
        val headline = when {
            change != null && change >= 15 -> "A much more active week"
            goalDays >= 5 -> "A strong week"
            change != null && change <= -20 -> "A quieter week than usual"
            week.workouts.size >= 3 -> "A good training week"
            else -> "Your week in numbers"
        }
        return Recap(
            week.from, week.to, total, avg, goalDays, best, change,
            week.sum { it.distance } ?: 0.0, (week.sum { it.kcal } ?: 0.0).roundToInt(),
            sleep, if (sleep != null && prevSleep != null && abs(sleep - prevSleep) >= 1) sleep - prevSleep else null,
            rhr, if (rhr != null && prevRhr != null) rhr - prevRhr else null,
            week.workouts.size, moving, week.workouts.sumOf { it.distanceM },
            week.days.map { d -> d.date to d.steps?.roundToInt() },
            headline, good, next,
        )
    }

    /** The Sunday whose recap should be sent at [now], or null. A missed Sunday evening is caught up until Monday noon. */
    fun due(now: LocalDateTime): LocalDate? = when {
        now.dayOfWeek == DayOfWeek.SUNDAY && now.hour >= HOUR -> now.toLocalDate()
        now.dayOfWeek == DayOfWeek.MONDAY && now.hour < 12 -> now.toLocalDate().minusDays(1)
        else -> null
    }

    /** The next Sunday at [HOUR]:30 after [now]. */
    fun nextAlarm(now: LocalDateTime): LocalDateTime {
        var t = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(HOUR, 30)
        if (!t.isAfter(now)) t = t.plusWeeks(1)
        return t
    }

    /** The week the recap page shows: the one that just ended, or the current one from Sunday evening on. */
    fun shownWeek(now: LocalDateTime): LocalDate {
        val today = now.toLocalDate()
        return if (today.dayOfWeek == DayOfWeek.SUNDAY && now.hour >= HOUR) today
        else today.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY))
    }

    fun load(db: Db, sunday: LocalDate, stepGoal: Int, sleepGoalMin: Int): Recap? {
        val monday = sunday.minusDays(6)
        return build(Insights.period(db, monday, sunday), Insights.period(db, monday.minusDays(7), sunday.minusDays(7)), stepGoal, sleepGoalMin)
    }
}
