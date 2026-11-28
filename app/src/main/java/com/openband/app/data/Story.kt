package com.openband.app.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A week or a month told as a series of cards, one subject each. */
object Story {

    enum class Kind { COVER, STEPS, BEST, ENERGY, SLEEP, HEART, BODY, WORKOUTS, SCORE, REVIEW, NEXT }

    data class Bar(val label: String, val value: Double?)
    /** [tone]: 0 good, 1 watch, -1 neutral. */
    data class Line(val name: String, val value: String, val note: String = "", val tone: Int = -1)

    data class Slide(
        val kind: Kind, val kicker: String, val title: String,
        /** The one large figure of the card. */
        val big: String? = null, val unit: String = "", val note: String = "",
        val bars: List<Bar> = emptyList(), val goal: Double? = null, val lines: List<Line> = emptyList(),
    )

    data class Deck(
        val monthly: Boolean, val from: LocalDate, val to: LocalDate, val slides: List<Slide>,
        /** The figures in plain words, for the written AI review. No name or location in here. */
        val facts: String,
    )

    data class Goals(val steps: Int, val sleepMin: Int, val imperial: Boolean = false)

    private fun n(v: Number) = "%,d".format(Locale.US, Math.round(v.toDouble()))
    private val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    fun word(score: Int) = when { score >= 85 -> "Excellent"; score >= 70 -> "Good"; score >= 50 -> "Fair"; else -> "Needs work" }

    /** "up 12% on the week before", or empty when there is nothing fair to compare with. */
    private fun change(now: Double?, before: Double?, period: String, unit: String = "%"): String {
        if (now == null || before == null || before <= 0) return ""
        val d = if (unit == "%") (now / before - 1) * 100 else now - before
        if (abs(d) < 1) return "Same as the $period before"
        val amount = if (unit == "%") "${abs(d).roundToInt()}%" else "${abs(d).roundToInt()} $unit"
        return "${if (d > 0) "Up" else "Down"} $amount on the $period before"
    }

    /**
     * @param recovery recovery score of each day that has one.
     * @return null when the period has nothing recorded.
     */
    fun build(
        now: Insights.Period, before: Insights.Period, monthly: Boolean, goals: Goals,
        recovery: List<Pair<LocalDate, Int>>, bodyAge: Int?, today: LocalDate,
    ): Deck? {
        val period = if (monthly) "month" else "week"
        val stepDays = now.days.mapNotNull { d -> d.steps?.takeIf { it > 0 }?.let { d.date to it } }
        val sleepDays = now.days.mapNotNull { d -> d.sleep?.takeIf { it >= 120 }?.let { d.date to it } }
        if (stepDays.isEmpty() && sleepDays.isEmpty() && now.workouts.isEmpty()) return null
        val running = !today.isAfter(now.to)
        val range = if (monthly) now.from.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) else "${now.from.format(day)} – ${now.to.format(day)}"
        // a comparison needs a few days on both sides
        val enough = if (monthly) 10 else 4
        fun bars(f: (Insights.Day) -> Double?) = now.days.mapIndexed { i, d ->
            val label = if (!monthly) d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH) else if (i == 0 || (i + 1) % 5 == 0) "${d.date.dayOfMonth}" else ""
            Bar(label, f(d))
        }
        fun avg(p: Insights.Period, f: (Insights.Day) -> Double?, min: Int = enough): Double? = p.days.mapNotNull(f).takeIf { it.size >= min }?.average()

        val totalSteps = stepDays.sumOf { it.second }
        val avgSteps = if (stepDays.isEmpty()) null else totalSteps / stepDays.size
        val goalDays = stepDays.count { it.second >= goals.steps }
        val prevSteps = avg(before, { d -> d.steps?.takeIf { it > 0 } })
        val avgSleep = sleepDays.takeIf { it.isNotEmpty() }?.map { it.second }?.average()
        val prevSleep = avg(before, { d -> d.sleep?.takeIf { it >= 120 } })
        val rhr = avg(now, { it.restingHr }, 1)
        val hrv = avg(now, { it.hrv }, 1)
        val spo2 = avg(now, { it.spo2 }, 1)
        val stress = avg(now, { it.stress }, 1)
        val movingMin = now.workouts.sumOf { it.movingMs } / 60_000.0
        val daysSoFar = now.days.count { !it.date.isAfter(today) }.coerceAtLeast(1)
        val weeklyMin = movingMin / daysSoFar * 7
        val km = (now.sum { it.distance } ?: 0.0) / if (goals.imperial) 1609.344 else 1000.0
        val unit = if (goals.imperial) "mi" else "km"
        val kcal = now.sum { it.kcal } ?: 0.0
        val avgRecovery = recovery.takeIf { it.isNotEmpty() }?.map { it.second }?.average()

        // ---- report card, 0..100 each
        val scores = ArrayList<Pair<String, Int>>()
        avgSteps?.let { scores += "Activity" to (it / goals.steps * 100).roundToInt().coerceIn(0, 100) }
        avgSleep?.let { scores += "Sleep" to (it / goals.sleepMin * 100).roundToInt().coerceIn(0, 100) }
        // a workout-free first day or two says little yet
        if (now.workouts.isNotEmpty() || daysSoFar >= 4) scores += "Exercise" to (weeklyMin / 150 * 100).roundToInt().coerceIn(0, 100)
        avgRecovery?.let { scores += "Recovery" to it.roundToInt() }
        val overall = scores.takeIf { it.isNotEmpty() }?.map { it.second }?.average()?.roundToInt()

        val headline = when {
            overall == null -> "Your $period"
            overall >= 85 -> "An excellent $period"
            overall >= 70 -> "A good $period"
            overall >= 50 -> "A fair $period"
            else -> "A $period to build on"
        }

        val slides = ArrayList<Slide>()
        slides += Slide(
            Kind.COVER, if (running) "This $period so far" else "Your $period", headline, big = overall?.toString(), unit = if (overall != null) "/100" else "",
            note = range,
            lines = listOfNotNull(
                if (stepDays.isEmpty()) null else Line("Steps", n(totalSteps)),
                avgSleep?.let { Line("Sleep a night", Stats.fmtMinutes(it)) },
                Line("Workouts", "${now.workouts.size}"),
            ),
        )
        if (stepDays.isNotEmpty()) slides += Slide(
            Kind.STEPS, "Steps", "You walked", big = n(totalSteps), unit = "steps",
            note = listOf("${n(avgSteps!!)} a day", change(if (stepDays.size >= enough) avgSteps else null, prevSteps, period)).filter { it.isNotEmpty() }.joinToString(" · "),
            bars = bars { it.steps }, goal = goals.steps.toDouble(),
            lines = listOf(Line("Goal reached", "$goalDays of ${stepDays.size} days", tone = if (goalDays * 2 >= stepDays.size) 0 else 1)),
        )
        stepDays.maxByOrNull { it.second }?.takeIf { stepDays.size >= 2 }?.let { (date, steps) ->
            val d = now.days.first { it.date == date }
            slides += Slide(
                Kind.BEST, "Best day", "${weekday(date)}, ${date.format(day)}", big = n(steps), unit = "steps",
                note = avgSteps?.takeIf { it > 0 }?.let { "%.1f× your daily average".format(Locale.US, steps / it) } ?: "",
                lines = listOfNotNull(
                    d.distance?.let { Line("Distance", "%.2f %s".format(Locale.US, it / if (goals.imperial) 1609.344 else 1000.0, unit)) },
                    d.kcal?.let { Line("Active energy", "${n(it)} kcal") },
                    recovery.firstOrNull { it.first == date }?.let { Line("Recovery that morning", "${it.second}") },
                ),
            )
        }
        if (km > 0 || kcal > 0) slides += Slide(
            Kind.ENERGY, "Distance and energy", "You covered", big = "%.1f".format(Locale.US, km), unit = unit,
            note = "${n(kcal)} active kcal burnt", bars = bars { it.kcal },
            lines = listOf(Line("About", "${(km * (if (goals.imperial) 1609.344 else 1000.0) / 400).roundToInt()} rounds of a 400 m track")),
        )
        if (sleepDays.isNotEmpty()) {
            val best = sleepDays.maxBy { it.second }
            val short = sleepDays.minBy { it.second }
            slides += Slide(
                Kind.SLEEP, "Sleep", "A typical night", big = Stats.fmtMinutes(avgSleep!!),
                note = change(if (sleepDays.size >= enough) avgSleep else null, prevSleep, period, "min").ifEmpty { "Goal ${Stats.fmtMinutes(goals.sleepMin.toDouble())}" },
                bars = bars { d -> d.sleep?.takeIf { it >= 120 }?.let { it / 60 } }, goal = goals.sleepMin / 60.0,
                lines = listOfNotNull(
                    Line("Longest", "${weekday(best.first)} · ${Stats.fmtMinutes(best.second)}", tone = 0),
                    if (sleepDays.size >= 2) Line("Shortest", "${weekday(short.first)} · ${Stats.fmtMinutes(short.second)}", tone = 1) else null,
                    Line("Nights on goal", "${sleepDays.count { it.second >= goals.sleepMin }} of ${sleepDays.size}"),
                ),
            )
        }
        if (rhr != null || hrv != null) slides += Slide(
            Kind.HEART, "Heart", if (rhr != null) "Resting heart rate" else "Heart rate variability",
            big = (rhr ?: hrv!!).roundToInt().toString(), unit = if (rhr != null) "bpm" else "ms",
            note = if (rhr != null) change(avg(now, { it.restingHr }), avg(before, { it.restingHr }), period, "bpm") else "",
            bars = bars { if (rhr != null) it.restingHr else it.hrv },
            lines = listOfNotNull(
                hrv?.let { Line("Variability (HRV)", "${it.roundToInt()} ms", change(avg(now, { d -> d.hrv }), avg(before, { d -> d.hrv }), period, "ms")) },
                avgRecovery?.let { Line("Average recovery", "${it.roundToInt()} /100", tone = if (it >= 67) 0 else 1) },
            ),
        )
        if (spo2 != null || stress != null) slides += Slide(
            Kind.BODY, "Body signals", if (stress != null) "Average stress" else "Blood oxygen",
            big = (stress ?: spo2!!).roundToInt().toString(), unit = if (stress != null) "/100" else "%",
            bars = bars { if (stress != null) it.stress else it.spo2 },
            lines = listOfNotNull(
                if (stress != null) now.days.filter { it.stress != null }.minByOrNull { it.stress!! }?.let { Line("Calmest day", weekday(it.date), tone = 0) } else null,
                if (stress != null) now.days.filter { it.stress != null }.maxByOrNull { it.stress!! }?.let { Line("Most stressful day", weekday(it.date), tone = 1) } else null,
                spo2?.let { Line("Blood oxygen", "${it.roundToInt()} %", tone = if (it >= 95) 0 else 1) },
                bodyAge?.let { Line("Body age today", "$it") },
            ),
        )
        slides += Slide(
            Kind.WORKOUTS, "Workouts", if (now.workouts.isEmpty()) "No workouts recorded" else "You trained for",
            big = if (now.workouts.isEmpty()) "0" else Stats.fmtMinutes(movingMin), unit = "",
            note = if (now.workouts.isEmpty()) "Even one 30 minute walk counts. Start it from Activity." else "${now.workouts.size} workout${if (now.workouts.size == 1) "" else "s"}",
            lines = now.workouts.sortedByDescending { it.movingMs }.take(4).map { w ->
                val dist = if (w.distanceM > 50) " · %.2f %s".format(Locale.US, w.distanceM / if (goals.imperial) 1609.344 else 1000.0, unit) else ""
                val date = java.time.Instant.ofEpochMilli(w.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Line(com.openband.app.workout.Sports.byId(w.sport).name, Stats.fmtMinutes(w.movingMs / 60_000.0) + dist, date.format(day))
            },
        )
        if (overall != null) slides += Slide(
            Kind.SCORE, "Report card", word(overall), big = overall.toString(), unit = "/100",
            lines = scores.map { Line(it.first, "${it.second}", word(it.second), if (it.second >= 70) 0 else 1) },
        )

        // ---- the written verdict, worked out on the phone (an AI service may replace the text)
        val strong = scores.filter { it.second >= 75 }.sortedByDescending { it.second }
        val weak = scores.filter { it.second < 75 }.minByOrNull { it.second }
        val review = buildString {
            if (strong.isNotEmpty()) append("Your strength this $period: ${strong.joinToString(" and ") { it.first.lowercase() }}. ")
            else if (scores.isNotEmpty()) append("A steady start, with room to grow. ")
            if (avgSteps != null && prevSteps != null && stepDays.size >= enough) {
                val d = ((avgSteps / prevSteps - 1) * 100).roundToInt()
                if (d >= 5) append("You moved $d% more than the $period before. ") else if (d <= -5) append("You moved ${-d}% less than the $period before. ")
            }
            weak?.let { append("The biggest gain is in ${it.first.lowercase()}.") } ?: append("Keep this rhythm going.")
        }
        slides += Slide(Kind.REVIEW, "Review", "What it adds up to", note = review)

        val goalsNext = ArrayList<Line>()
        avgSteps?.let {
            val target = if (it >= goals.steps) it else minOf(goals.steps.toDouble(), (((it + 1000) / 500).toInt() * 500).toDouble())
            goalsNext += Line("Steps a day", n(target))
        }
        avgSleep?.let {
            goalsNext += if (it < goals.sleepMin - 20) Line("Sleep", "${((goals.sleepMin - it) / 2).roundToInt().coerceIn(15, 60)} min earlier to bed") else Line("Sleep", "Keep your bedtime")
        }
        goalsNext += Line("Exercise", if (weeklyMin >= 150) "Keep 150+ active minutes" else if (weeklyMin >= 60) "Add one more workout" else "Two 30 minute walks")
        slides += Slide(Kind.NEXT, "Next $period", "Your targets", lines = goalsNext)

        val facts = buildString {
            appendLine("Period: $period $range" + if (running) " (still running, $daysSoFar days so far)" else "")
            avgSteps?.let { appendLine("Average steps a day: ${it.roundToInt()} (goal ${goals.steps}); total ${totalSteps.roundToInt()}; goal reached on $goalDays of ${stepDays.size} days") }
            prevSteps?.let { appendLine("Average steps the $period before: ${it.roundToInt()}") }
            avgSleep?.let { appendLine("Average sleep a night: ${Stats.fmtMinutes(it)} (goal ${Stats.fmtMinutes(goals.sleepMin.toDouble())}), ${sleepDays.size} nights") }
            prevSleep?.let { appendLine("Average sleep the $period before: ${Stats.fmtMinutes(it)}") }
            rhr?.let { appendLine("Resting heart rate: ${it.roundToInt()} bpm") }
            hrv?.let { appendLine("Heart rate variability: ${it.roundToInt()} ms") }
            spo2?.let { appendLine("Blood oxygen: ${it.roundToInt()} %") }
            stress?.let { appendLine("Stress: ${it.roundToInt()} out of 100") }
            avgRecovery?.let { appendLine("Average recovery score: ${it.roundToInt()} out of 100") }
            appendLine("Workouts: ${now.workouts.size}, ${movingMin.roundToInt()} minutes in total")
            if (km > 0) appendLine("Distance: %.1f %s; active energy %d kcal".format(Locale.US, km, unit, kcal.roundToInt()))
            if (scores.isNotEmpty()) appendLine("App scores out of 100: " + scores.joinToString { "${it.first} ${it.second}" } + "; overall $overall")
        }
        return Deck(monthly, now.from, now.to, slides, facts)
    }

    /** Reads the database and builds the story of the week or month [back] periods before the current one. */
    fun load(db: Db, monthly: Boolean, back: Int, goals: Goals, bodyAge: () -> Int?, today: LocalDate = LocalDate.now()): Deck? {
        val (a, b) = Insights.range(monthly, back, today)
        val (c, d) = Insights.range(monthly, back + 1, today)
        val now = Insights.period(db, a, b)
        val recovery = now.days.filter { !it.date.isAfter(today) }.mapNotNull { x -> Recovery.compute(db, x.date, goals.sleepMin)?.let { x.date to it.score } }
        return build(now, Insights.period(db, c, d), monthly, goals, recovery, if (back == 0) bodyAge() else null, today)
    }
}
