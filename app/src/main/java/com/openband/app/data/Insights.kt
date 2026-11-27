package com.openband.app.data

import com.openband.app.ui.restingHr
import com.openband.app.workout.Laps
import com.openband.app.workout.RouteMath
import com.openband.app.workout.Sports
import com.openband.app.workout.Strength
import com.openband.app.workout.Training
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Personal records and weekly / monthly reports, worked out from what is stored on the phone. */
object Insights {

    // ------------------------------------------------------------------------------------------------ records

    enum class Group { FOOT, CYCLE, STRENGTH, ALL, DAILY }

    /** [value] is in the unit [kind] says: seconds, metres, count or kcal. */
    data class Record(val group: Group, val title: String, val kind: Kind, val value: Double, val extra: Double? = null, val workoutId: Long? = null, val at: Long)

    enum class Kind { TIME_FOR_DISTANCE, ROUND, METERS, DURATION, COUNT, CLIMB, KCAL, SLEEP, WEIGHT }

    fun isFoot(sport: String) = sport in setOf("run", "trail", "walk", "hike", "treadmill")
    fun isCycle(sport: String) = sport in setOf("bike", "mtb")

    fun records(db: Db, elevationGain: (List<RoutePt>) -> Double): List<Record> {
        val workouts = db.workouts(1000)
        val out = ArrayList<Record>()

        fun effort(group: Group, title: String, meters: Double, list: List<Workout>) {
            var best: Pair<Double, Workout>? = null
            for (w in list) {
                val t = RouteMath.bestEffort(w.route, meters, Sports.byId(w.sport).maxSpeed) ?: continue
                if (best == null || t < best.first) best = t to w
            }
            best?.let { (t, w) -> out += Record(group, title, Kind.TIME_FOR_DISTANCE, t, meters, w.id, w.start) }
        }
        val foot = workouts.filter { isFoot(it.sport) }
        val cycle = workouts.filter { isCycle(it.sport) }
        effort(Group.FOOT, "Fastest 1 km", 1000.0, foot)
        effort(Group.FOOT, "Fastest 5 km", 5000.0, foot)
        effort(Group.FOOT, "Fastest 10 km", 10000.0, foot)
        effort(Group.CYCLE, "Fastest 5 km", 5000.0, cycle)
        effort(Group.CYCLE, "Fastest 20 km", 20000.0, cycle)

        val rounds = foot.mapNotNull { w -> Laps.detect(w.route)?.let { w to it } }
        rounds.map { (w, r) -> w to r.fastest }.minByOrNull { (_, l) -> l.seconds / l.meters }?.let { (w, l) ->
            out += Record(Group.FOOT, "Fastest round", Kind.ROUND, l.seconds.toDouble(), l.meters, w.id, w.start)
        }
        rounds.maxByOrNull { it.second.count }?.let { (w, r) -> out += Record(Group.FOOT, "Most rounds in one go", Kind.COUNT, r.count.toDouble(), null, w.id, w.start) }

        workouts.filter { it.distanceM >= 100 }.maxByOrNull { it.distanceM }?.let { out += Record(Group.ALL, "Longest distance", Kind.METERS, it.distanceM, null, it.id, it.start) }
        workouts.maxByOrNull { it.movingMs }?.let { out += Record(Group.ALL, "Longest workout", Kind.DURATION, it.movingMs / 1000.0, null, it.id, it.start) }
        workouts.filter { it.route.size > 10 }.map { it to elevationGain(it.route) }.maxByOrNull { it.second }?.takeIf { it.second >= 5 }
            ?.let { (w, c) -> out += Record(Group.ALL, "Most climb", Kind.CLIMB, c, null, w.id, w.start) }
        workouts.maxByOrNull { it.kcal }?.takeIf { it.kcal > 1 }?.let { out += Record(Group.ALL, "Most calories in a workout", Kind.KCAL, it.kcal, null, it.id, it.start) }

        // best estimated one-rep max per exercise
        workouts.flatMap { w -> Training.setsOf(w.extra).map { it to w } }.groupBy { it.first.exercise }.forEach { (id, list) ->
            list.filter { it.first.oneRepMax != null }.maxByOrNull { it.first.oneRepMax!! }?.let { (s, w) ->
                out += Record(Group.STRENGTH, Strength.byId(id).name, Kind.WEIGHT, s.oneRepMax!!, null, w.id, w.start)
            }
        }

        val today = LocalDate.now()
        var steps: Pair<Double, LocalDate>? = null
        var sleep: Pair<Double, LocalDate>? = null
        for (i in 0 until 180) {
            val d = today.minusDays(i.toLong())
            Stats.dayValue(db, M.STEPS, d)?.let { if (steps == null || it > steps!!.first) steps = it to d }
            Stats.sleepFor(db, d)?.asleep?.let { if (sleep == null || it > sleep!!.first) sleep = it to d }
        }
        steps?.let { (v, d) -> out += Record(Group.DAILY, "Most steps in a day", Kind.COUNT, v, null, null, Stats.dayStart(d)) }
        sleep?.let { (v, d) -> out += Record(Group.DAILY, "Longest night's sleep", Kind.SLEEP, v, null, null, Stats.dayStart(d)) }
        return out
    }

    // ------------------------------------------------------------------------------------------------ reports

    data class Day(
        val date: LocalDate, val steps: Double?, val kcal: Double?, val distance: Double?, val sleep: Double?,
        val restingHr: Double?, val hrv: Double?, val spo2: Double?, val stress: Double?,
    )

    data class Period(val from: LocalDate, val to: LocalDate, val days: List<Day>, val workouts: List<Workout>) {
        fun avg(f: (Day) -> Double?): Double? = days.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        fun sum(f: (Day) -> Double?): Double? = days.mapNotNull(f).takeIf { it.isNotEmpty() }?.sum()
    }

    /** First and last day of the week (Monday first) or month, [back] periods before the current one. */
    fun range(monthly: Boolean, back: Int, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> =
        if (monthly) {
            val first = today.withDayOfMonth(1).minusMonths(back.toLong())
            first to first.plusMonths(1).minusDays(1)
        } else {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(back.toLong())
            monday to monday.plusDays(6)
        }

    fun period(db: Db, from: LocalDate, to: LocalDate): Period {
        val today = LocalDate.now()
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            if (d.isAfter(today)) Day(d, null, null, null, null, null, null, null, null) else {
                val sleep = Stats.sleepFor(db, d)
                Day(
                    d, Stats.dayValue(db, M.STEPS, d), Stats.dayValue(db, M.CALORIES, d), Stats.dayValue(db, M.DISTANCE, d), sleep?.asleep,
                    restingHr(db, sleep, d).first, Stats.dayValue(db, M.HRV, d), Stats.dayValue(db, M.SPO2, d), Stats.dayValue(db, M.STRESS, d),
                )
            }
        }.toList()
        val a = Stats.dayStart(from); val b = Stats.dayStart(to.plusDays(1))
        return Period(from, to, days, db.workouts(1000).filter { it.start in a until b }.sortedBy { it.start })
    }
}
