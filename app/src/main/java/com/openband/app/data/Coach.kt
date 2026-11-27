package com.openband.app.data

import com.openband.app.ui.RacePrefs
import com.openband.app.ui.restingHr
import com.openband.app.workout.Laps
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The coach's two jobs: plain-language observations worked out on the phone, and a compact numeric summary that can be
 * given to an AI model. All figures are calculated here, so an AI only has to explain them, never to compute them.
 */
object Coach {

    data class Insight(val title: String, val text: String, /** 0 good, 1 worth a look, 2 take care, -1 neutral */ val tone: Int)

    fun insights(db: Db, p: RacePrefs, today: LocalDate = LocalDate.now()): List<Insight> {
        val out = ArrayList<Insight>()

        Recovery.compute(db, today, p.sleepGoalMin)?.let { r ->
            val weakest = r.parts.minByOrNull { it.score }
            out += Insight(
                "Recovery ${r.score} · ${r.label}",
                r.advice + (weakest?.takeIf { it.score < 67 }?.let { " Pulled down mostly by ${it.name.lowercase()} (${it.value}${it.usual?.let { u -> ", $u" } ?: ""})." } ?: "") +
                    if (r.provisional) " Early estimate: still learning your usual values." else "",
                r.tone,
            )
        }

        val nights = (0..6).mapNotNull { Stats.sleepFor(db, today.minusDays(it.toLong()))?.asleep }
        if (nights.size >= 3) {
            val debt = nights.sumOf { (p.sleepGoalMin - it).coerceAtLeast(0.0) }
            val avg = nights.average()
            out += Insight(
                "Sleep this week",
                "You averaged ${Stats.fmtMinutes(avg)} over ${nights.size} nights" +
                    if (debt >= 60) ", about ${Stats.fmtMinutes(debt)} short of your goal in total. Going to bed 30 minutes earlier would help." else ", close to your goal.",
                if (debt >= 180) 2 else if (debt >= 60) 1 else 0,
            )
        }

        fun usual(f: (LocalDate) -> Double?) = (1..28).mapNotNull { f(today.minusDays(it.toLong())) }.takeIf { it.size >= 3 }?.average()
        val rhr = restingHr(db, Stats.sleepFor(db, today), today).first
        val rhrUsual = usual { restingHr(db, Stats.sleepFor(db, it), it).first }
        if (rhr != null && rhrUsual != null && abs(rhr - rhrUsual) >= 4) {
            val up = rhr > rhrUsual
            out += Insight(
                if (up) "Resting heart rate is up" else "Resting heart rate is down",
                "${rhr.roundToInt()} bpm today against your usual ${rhrUsual.roundToInt()}. " +
                    if (up) "Tiredness, stress, late meals or an illness coming on can do this." else "Usually a sign of good recovery.",
                if (up) 1 else 0,
            )
        }
        val temp = db.series(M.TEMP, Stats.dayStart(today) - 6 * 3_600_000L, Stats.dayStart(today) + 12 * 3_600_000L).map { it.value }.takeIf { it.isNotEmpty() }?.average()
        val tempUsual = usual { d -> db.series(M.TEMP, Stats.dayStart(d), Stats.dayStart(d) + 12 * 3_600_000L).map { it.value }.takeIf { it.isNotEmpty() }?.average() }
        if (temp != null && tempUsual != null && temp - tempUsual >= 0.5) out += Insight(
            "Warmer than usual", "Wrist temperature %.1f °C against your usual %.1f °C. Keep an eye on how you feel.".format(temp, tempUsual), 2,
        )

        val last7 = (1..7).mapNotNull { Stats.dayValue(db, M.STEPS, today.minusDays(it.toLong())) }
        val prev7 = (8..14).mapNotNull { Stats.dayValue(db, M.STEPS, today.minusDays(it.toLong())) }
        if (last7.size >= 3) {
            val a = last7.average()
            val change = if (prev7.size >= 3) (a - prev7.average()) / prev7.average() * 100 else null
            out += Insight(
                "Steps",
                "About %,d a day over the last week".format(a.roundToInt()) +
                    (change?.takeIf { abs(it) >= 5 }?.let { ", ${if (it > 0) "up" else "down"} ${abs(it).roundToInt()}% on the week before" } ?: "") +
                    if (a < p.stepGoal) ". %,d more a day would reach your goal.".format((p.stepGoal - a).roundToInt()) else ". You are meeting your goal.",
                if (a >= p.stepGoal) 0 else if (change != null && change < -15) 1 else -1,
            )
        }

        db.series(M.HRR1, Stats.dayStart(today.minusDays(30)), Stats.dayStart(today.plusDays(1))).lastOrNull()?.let { s ->
            val d = s.value.roundToInt()
            val (word, tone) = com.openband.app.workout.HeartTest.rating(d)
            out += Insight("Heart recovery · $word", "Your heart rate fell $d bpm in the first minute at your latest test.", tone)
        }
        return out
    }

    /**
     * Comparisons worked out by the app: this week against the one before, sleep habits, and how short nights went with
     * the next day's body readings. The AI is asked to explain these, not to calculate.
     */
    fun analysis(db: Db, p: RacePrefs, today: LocalDate = LocalDate.now()): String {
        val b = StringBuilder()
        class D(val steps: Double?, val sleep: Stats.Sleep?, val hrv: Double?, val rhr: Double?)
        val days = (0..13).map { i ->
            val d = today.minusDays(i.toLong())
            val sl = Stats.sleepFor(db, d)
            D(Stats.dayValue(db, M.STEPS, d), sl, Stats.dayValue(db, M.HRV, d), restingHr(db, sl, d).first)
        }
        // today is unfinished, so "this week" is the seven days before it
        val now = days.subList(1, 8); val before = days.subList(8, 14)
        fun compare(name: String, f: (D) -> Double?, fmt: (Double) -> String) {
            val a = now.mapNotNull(f); val z = before.mapNotNull(f)
            if (a.size < 2) return
            b.append("$name: ${fmt(a.average())} (${a.size} days)")
            if (z.size >= 3) b.append(", previous week ${fmt(z.average())} (${z.size} days), change ${((a.average() - z.average()) / z.average() * 100).roundToInt()}%")
            else b.append(", not enough earlier data to compare")
            b.appendLine()
        }
        b.appendLine("Last 7 full days compared with the 7 before:")
        compare("Steps per day", { it.steps }, { it.roundToInt().toString() })
        compare("Sleep per night (minutes)", { it.sleep?.asleep }, { it.roundToInt().toString() })
        compare("HRV (ms)", { it.hrv }, { it.roundToInt().toString() })
        compare("Resting heart rate", { it.rhr }, { it.roundToInt().toString() })
        val nights = days.take(7).mapNotNull { it.sleep }
        if (nights.size >= 3) {
            val zone = ZoneId.systemDefault()
            // minutes after 18:00, so bedtimes either side of midnight average correctly
            fun mins(t: Long) = Instant.ofEpochMilli(t).atZone(zone).toLocalTime().toSecondOfDay() / 60
            val bed = nights.map { (mins(it.start) - 18 * 60 + 1440) % 1440 }
            val wake = nights.map { mins(it.end) }
            fun clock(m: Int) = "%02d:%02d".format((m % 1440) / 60, m % 60)
            val spread = bed.map { abs(it - bed.average()) }.average().roundToInt()
            b.appendLine("Typical bedtime ${clock(bed.average().roundToInt() + 18 * 60)}, wake ${clock(wake.average().roundToInt())}, bedtime varies by about $spread minutes (${nights.size} nights).")
            b.appendLine("Sleep short of goal over these nights: ${nights.sumOf { (p.sleepGoalMin - it.asleep).coerceAtLeast(0.0) }.roundToInt()} minutes.")
        }
        val pairs = days.filter { it.sleep != null && it.hrv != null }
        val short = pairs.filter { it.sleep!!.asleep < p.sleepGoalMin - 45 }; val long = pairs - short.toSet()
        if (short.size >= 3 && long.size >= 3) b.appendLine(
            "After shorter nights HRV averaged ${short.map { it.hrv!! }.average().roundToInt()} ms (${short.size} days), after fuller nights ${long.map { it.hrv!! }.average().roundToInt()} ms (${long.size} days). Small sample.",
        )
        val week = Stats.dayStart(today.minusDays(6)); val prev = Stats.dayStart(today.minusDays(13))
        val ws = db.workouts(200)
        val a = ws.filter { it.start >= week }; val z = ws.filter { it.start in prev until week }
        b.appendLine(
            "Workouts, last 7 days: ${a.size} totalling ${(a.sumOf { it.movingMs } / 60000.0).roundToInt()} minutes; 7 days before: ${z.size} totalling ${(z.sumOf { it.movingMs } / 60000.0).roundToInt()} minutes.",
        )
        val latest = ws.firstOrNull { it.route.size > 20 && it.distanceM > 300 }
        if (latest != null) {
            val same = ws.filter { it.id != latest.id && it.start < latest.start && com.openband.app.workout.RouteMath.sameRoute(latest.route, it.route) }.maxByOrNull { it.start }
            if (same != null) {
                fun pace(w: Workout) = (w.movingMs / 1000.0 / (w.distanceM / 1000)).roundToInt()
                b.appendLine("Latest route compared with the time before: pace ${pace(latest)} s/km vs ${pace(same)} s/km, average heart rate ${latest.avgHr} vs ${same.avgHr}.")
            }
        }
        return b.toString()
    }

    /**
     * The text sent to an AI model: the last two weeks as numbers. No name, no photo, no places and no GPS positions.
     * Age, sex, height and weight only when [profile] is switched on.
     */
    fun summary(db: Db, p: RacePrefs, profile: Boolean, today: LocalDate = LocalDate.now()): String {
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
        val b = StringBuilder()
        b.appendLine("Today: ${today.format(day)}. Goals: ${p.stepGoal} steps, ${p.calGoal} active kcal, ${Stats.fmtMinutes(p.sleepGoalMin.toDouble())} sleep.")
        if (profile) b.appendLine("User: age ${p.age}, ${when (p.sex) { 1 -> "male"; 2 -> "female"; else -> "sex not given" }}, ${p.heightCm} cm, ${p.weightKg} kg.")
        b.appendLine()
        b.append(analysis(db, p, today))
        b.appendLine()
        b.appendLine("Daily data (a dash means not measured). Today is still in progress.")
        b.appendLine("date | steps | active kcal | sleep (deep, REM, awake min) | resting HR | HRV ms | SpO2 % | stress 0-100 | wrist temp C | recovery 1-100")
        for (i in 13 downTo 0) {
            val d = today.minusDays(i.toLong())
            val sleep = Stats.sleepFor(db, d)
            fun n(v: Double?) = v?.roundToInt()?.toString() ?: "-"
            val steps = Stats.dayValue(db, M.STEPS, d)
            val hrv = Stats.dayValue(db, M.HRV, d)
            if (steps == null && sleep == null && hrv == null) continue
            b.appendLine(
                listOf(
                    d.format(day), n(steps), n(Stats.dayValue(db, M.CALORIES, d)),
                    sleep?.let { "${Stats.fmtMinutes(it.asleep)} (${it.deep.roundToInt()}, ${it.rem.roundToInt()}, ${it.awake.roundToInt()})" } ?: "-",
                    n(restingHr(db, sleep, d).first), n(hrv), n(Stats.dayValue(db, M.SPO2, d)), n(Stats.dayValue(db, M.STRESS, d)),
                    Stats.dayValue(db, M.TEMP, d)?.let { "%.1f".format(Locale.US, it) } ?: "-",
                    n(Recovery.compute(db, d, p.sleepGoalMin)?.score?.toDouble()),
                ).joinToString(" | "),
            )
        }
        val from = Stats.dayStart(today.minusDays(30))
        val workouts = db.workouts(100).filter { it.start >= from }.take(20)
        b.appendLine(if (workouts.isEmpty()) "No workouts in the last 30 days." else "Workouts (newest first):")
        val time = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US)
        for (w in workouts) {
            val at = Instant.ofEpochMilli(w.start).atZone(ZoneId.systemDefault()).format(time)
            val parts = arrayListOf("$at ${w.sport}", "${(w.movingMs / 60000.0).roundToInt()} min")
            if (w.distanceM >= 50) {
                parts += "%.2f km".format(Locale.US, w.distanceM / 1000)
                val pace = w.movingMs / 1000.0 / (w.distanceM / 1000)
                parts += "pace %d:%02d/km".format((pace / 60).toInt(), (pace % 60).toInt())
            }
            if (w.avgHr > 0) parts += "HR avg ${w.avgHr} max ${w.maxHr}"
            parts += "${w.kcal.roundToInt()} kcal"
            Laps.detect(w.route)?.let { parts += "${it.count} rounds of ${it.avgMeters.roundToInt()} m" }
            val sets = com.openband.app.workout.Training.setsOf(w.extra)
            if (sets.isNotEmpty()) parts += "lifting: " + sets.groupBy { it.exercise }.entries.joinToString("; ") { (id, l) ->
                com.openband.app.workout.Strength.byId(id).name + " " + l.joinToString(" ") { "${it.weight.roundToInt()}kgx${it.reps}" }
            } + " (volume ${com.openband.app.workout.Strength.volume(sets).roundToInt()} kg)"
            b.appendLine(parts.joinToString(", "))
        }
        val tests = db.series(M.HRR1, from, Stats.dayStart(today.plusDays(1)))
        if (tests.isNotEmpty()) b.appendLine(
            "Heart-rate recovery tests (drop in bpm after 1 minute): " +
                tests.takeLast(8).joinToString(", ") { Instant.ofEpochMilli(it.ts).atZone(ZoneId.systemDefault()).format(day) + " " + it.value.roundToInt() },
        )
        return b.toString()
    }
}
