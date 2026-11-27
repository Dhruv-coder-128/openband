package com.openband.app.data

import com.openband.app.ui.RacePrefs
import com.openband.app.ui.restingHr
import com.openband.app.workout.RouteMath
import com.openband.app.workout.Sports
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The coach's brain, on the phone: from the measured data it decides how hard today should be, what to do, how many
 * steps to aim for and when to sleep, and says it in plain Gujarati or English. Instant, offline and never guessing.
 */
object Planner {

    enum class Level { REST, EASY, NORMAL, PUSH, UNKNOWN }

    data class Note(val title: String, val text: String, /** 0 good, 1 look, 2 care */ val tone: Int)

    data class Plan(
        val level: Level, val score: Int?, val provisional: Boolean,
        val headline: String, val reason: String,
        val move: String, val moveDetail: String, val doneToday: String?,
        val stepsNow: Int, val stepsTarget: Int, val stepsDetail: String,
        val bedtime: String?, val bedDetail: String,
        val notes: List<Note>,
    )

    class L(val gu: Boolean) {
        operator fun invoke(guText: String, en: String) = if (gu) guText else en
    }

    private fun dur(min: Double, t: L): String {
        val m = min.roundToInt()
        return if (m < 60) t("$m મિનિટ", "$m min") else t("${m / 60} કલાક ${m % 60} મિનિટ", "${m / 60}h ${m % 60}m")
    }

    private fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        val h = m / 60
        return "%d:%02d %s".format(((h + 11) % 12) + 1, m % 60, if (h < 12) "AM" else "PM")
    }

    private class Facts(
        val recovery: Recovery.Result?, val sleep: Stats.Sleep?, val debt: Double, val nights: Int, val wake: Int?,
        val avgSteps: Double?, val steps: Int, val todayWorkouts: List<Workout>, val recent: List<Workout>,
        val rhrUp: Double?, val tempUp: Double?,
    )

    private fun facts(db: Db, p: RacePrefs, today: LocalDate): Facts {
        val zone = ZoneId.systemDefault()
        val week = (0..6).mapNotNull { Stats.sleepFor(db, today.minusDays(it.toLong())) }
        val wakes = week.map { Instant.ofEpochMilli(it.end).atZone(zone).toLocalTime().toSecondOfDay() / 60 }.sorted()
        val workouts = db.workouts(100)
        val start = Stats.dayStart(today)
        val sleep = Stats.sleepFor(db, today)
        fun usual(f: (LocalDate) -> Double?) = (1..28).mapNotNull { f(today.minusDays(it.toLong())) }.takeIf { it.size >= 3 }?.average()
        val rhr = restingHr(db, sleep, today).first
        val rhrUsual = usual { restingHr(db, Stats.sleepFor(db, it), it).first }
        fun morningTemp(d: LocalDate) = db.series(M.TEMP, Stats.dayStart(d), Stats.dayStart(d) + 12 * 3_600_000L).map { it.value }.takeIf { it.isNotEmpty() }?.average()
        val temp = morningTemp(today); val tempUsual = usual { morningTemp(it) }
        return Facts(
            Recovery.compute(db, today, p.sleepGoalMin), sleep,
            week.sumOf { (p.sleepGoalMin - it.asleep).coerceAtLeast(0.0) }, week.size,
            wakes.takeIf { it.isNotEmpty() }?.get(wakes.size / 2),
            (1..7).mapNotNull { Stats.dayValue(db, M.STEPS, today.minusDays(it.toLong())) }.takeIf { it.isNotEmpty() }?.average(),
            Stats.total(db, M.STEPS, start, start + 86_400_000L)?.toInt() ?: 0,
            workouts.filter { it.start >= start }, workouts,
            if (rhr != null && rhrUsual != null) rhr - rhrUsual else null,
            if (temp != null && tempUsual != null) temp - tempUsual else null,
        )
    }

    fun plan(db: Db, p: RacePrefs, gu: Boolean, today: LocalDate = LocalDate.now(), nowMinute: Int = java.time.LocalTime.now().toSecondOfDay() / 60): Plan {
        val t = L(gu)
        val f = facts(db, p, today)
        val r = f.recovery

        // ---------------- how hard should today be
        val warm = (f.tempUp ?: 0.0) >= 0.6
        val level = when {
            warm -> Level.REST
            r == null -> Level.UNKNOWN
            r.score < 34 -> Level.REST
            r.score < 55 || (f.rhrUp ?: 0.0) >= 6 -> Level.EASY
            r.score < 75 || f.debt >= 240 -> Level.NORMAL
            else -> Level.PUSH
        }
        val headline = when (level) {
            Level.REST -> t("આજે આરામ કરો", "Rest today")
            Level.EASY -> t("આજે હળવો દિવસ", "Take it easy today")
            Level.NORMAL -> t("આજે સામાન્ય કસરત", "A normal training day")
            Level.PUSH -> t("આજે જોર લગાવી શકો", "You can push today")
            Level.UNKNOWN -> t("આજે રોજ મુજબ", "Carry on as usual")
        }
        val slept = f.sleep?.asleep
        val short = slept != null && slept < p.sleepGoalMin - 45
        val reason = when {
            warm -> t("કાંડાનું તાપમાન હંમેશા કરતાં વધારે છે. તબિયત પર ધ્યાન રાખો.", "Your wrist is warmer than usual. Watch how you feel.")
            r == null -> t("આજે સવારનો ડેટા નથી, એટલે અંદાજ આપી શકાય નહીં.", "There is no reading from last night to judge by.")
            (f.rhrUp ?: 0.0) >= 6 -> t("આરામના ધબકારા હંમેશા કરતાં ${f.rhrUp!!.roundToInt()} વધારે છે.", "Resting heart rate is ${f.rhrUp!!.roundToInt()} beats above usual.")
            short -> t("રાત્રે ${dur(slept!!, t)} ઊંઘ્યા, લક્ષ્ય કરતાં ${dur(p.sleepGoalMin - slept, t)} ઓછી.", "You slept ${dur(slept!!, t)}, ${dur(p.sleepGoalMin - slept, t)} under your goal.")
            f.debt >= 180 -> t("આ અઠવાડિયે કુલ ${dur(f.debt, t)} ઊંઘ બાકી છે.", "You are ${dur(f.debt, t)} short of sleep this week.")
            r.score >= 75 -> t("શરીર સારી રીતે તાજું થયું છે.", "Your body has recovered well.")
            else -> t("તમારા આંકડા રોજ જેવા છે.", "Your numbers look like a usual day.")
        }

        // ---------------- what to do
        val max = p.maxHr
        fun zone(a: Int, b: Int) = "${max * a / 100}–${max * b / 100} bpm"
        val (move, detail) = when (level) {
            Level.REST -> t("15 મિનિટ હળવું સ્ટ્રેચિંગ", "15 minutes of gentle stretching") to t("ભારે કસરત આજે ટાળો.", "Skip hard exercise today.")
            Level.EASY -> t("25 મિનિટ ધીમી ચાલ", "25 minute easy walk") to t("ધબકારા ${zone(50, 60)} રાખો. વાત કરી શકાય એટલી ઝડપ.", "Keep your heart at ${zone(50, 60)}: you can chat comfortably.")
            Level.NORMAL -> t("35 મિનિટ ઝડપી ચાલ", "35 minute brisk walk") to t("ધબકારા ${zone(60, 70)} રાખો.", "Keep your heart at ${zone(60, 70)}.")
            Level.PUSH -> t("40 મિનિટ જોરવાળી કસરત", "40 minute harder session") to t("ઝડપી–ધીમી ચાલ અથવા જિમ. ઝડપી ભાગમાં ${zone(70, 85)}.", "Intervals or gym. Reach ${zone(70, 85)} in the hard parts.")
            Level.UNKNOWN -> t("30 મિનિટ ચાલ", "30 minute walk") to t("આરામદાયક ઝડપે.", "At a comfortable pace.")
        }
        val done = f.todayWorkouts.takeIf { it.isNotEmpty() }?.let { w ->
            val min = w.sumOf { it.movingMs } / 60000.0
            val guNames = mapOf("walk" to "ચાલ", "run" to "દોડ", "bike" to "સાયકલ", "swim" to "તરવું", "yoga" to "યોગ", "strength" to "કસરત (વજન)", "compound" to "કસરત (વજન)", "hike" to "ટ્રેકિંગ")
            val names = w.map { (if (gu) guNames[it.sport] else null) ?: Sports.byId(it.sport).name }.distinct().joinToString()
            t("આજે થઈ ગયું: $names, ${dur(min, t)}", "Done today: $names, ${dur(min, t)}")
        }

        // ---------------- steps
        val avg = f.avgSteps
        val stretch = if (avg == null) p.stepGoal else (((avg * 1.15) / 500).roundToInt() * 500).coerceIn(minOf(4000, p.stepGoal), p.stepGoal)
        val target = if (level == Level.REST) minOf(stretch, 4000) else stretch
        val left = target - f.steps
        // today's target is a realistic step up from the recent average; say so when it is below the full goal
        val full = if (target < p.stepGoal) t(" પૂરું લક્ષ્ય %,d.".format(p.stepGoal), " Full goal %,d.".format(p.stepGoal)) else ""
        val stepsDetail = if (left <= 0) t("આજનું લક્ષ્ય પૂરું થયું!", "Today's target reached!") + full
        else t("%,d બાકી, લગભગ %d મિનિટ ચાલ".format(left, (left / 105.0).roundToInt()), "%,d to go, about %d minutes of walking".format(left, (left / 105.0).roundToInt()))

        // ---------------- bedtime: wake time minus the sleep goal, 15 min to fall asleep, a little extra when behind
        val extra = if (f.debt >= 180) 30 else 0
        val bedMin = f.wake?.let { ((it - p.sleepGoalMin - 15 - extra) + 1440 * 2) % 1440 }?.let {
            // keep it humane: between 9 pm and half past midnight
            if (it in 31 until 12 * 60) 30 else if (it in 12 * 60 until 21 * 60) 21 * 60 else it
        }
        val bedDetail = if (bedMin == null) t("ઊંઘનો ડેટા મળ્યા પછી સમય બતાવીશ.", "A time appears once sleep is recorded.")
        else {
            val base = t("તમે રોજ ${clock(f.wake!!)} આસપાસ ઊઠો છો.", "You usually wake around ${clock(f.wake!!)}.")
            val until = (bedMin - nowMinute + 1440) % 1440
            base + (if (extra > 0) t(" બાકી ઊંઘ માટે 30 મિનિટ વહેલા.", " 30 minutes earlier to catch up.") else "") +
                if (until in 1..360) t(" હજુ ${dur(until.toDouble(), t)} બાકી.", " That is in ${dur(until.toDouble(), t)}.") else ""
        }

        // ---------------- worth knowing
        val notes = ArrayList<Note>()
        val gps = f.recent.filter { it.route.size > 20 && it.distanceM > 300 }
        val latest = gps.firstOrNull()
        if (latest != null && System.currentTimeMillis() - latest.start < 7 * 86_400_000L) {
            val before = gps.filter { it.id != latest.id && it.start < latest.start && RouteMath.sameRoute(latest.route, it.route) }.maxByOrNull { it.start }
            if (before != null) {
                val d = (latest.movingMs / latest.distanceM - before.movingMs / before.distanceM).roundToInt() // ms per m = s per km
                if (abs(d) >= 5) notes += if (d < 0) Note(
                    t("ચાલ ઝડપી થઈ", "Getting faster"),
                    t("એ જ રસ્તે ગયા વખત કરતાં દર કિમીએ ${-d} સેકન્ડ ઝડપી.", "${-d} s per km faster than last time on the same route."), 0,
                ) else Note(
                    t("ચાલ થોડી ધીમી", "A little slower"),
                    t("એ જ રસ્તે ગયા વખત કરતાં દર કિમીએ $d સેકન્ડ ધીમી.", "$d s per km slower than last time on the same route."), 1,
                )
            }
        }
        if (f.nights >= 3 && f.debt >= 120) notes += Note(
            t("ઊંઘ બાકી", "Sleep debt"),
            t("${f.nights} રાતમાં લક્ષ્ય કરતાં ${dur(f.debt, t)} ઓછી ઊંઘ.", "${dur(f.debt, t)} short of your goal over ${f.nights} nights."), if (f.debt >= 240) 2 else 1,
        )
        if ((f.rhrUp ?: 0.0) >= 4) notes += Note(
            t("આરામના ધબકારા વધ્યા", "Resting heart rate up"),
            t("હંમેશા કરતાં ${f.rhrUp!!.roundToInt()} વધારે. થાક, તણાવ કે બીમારીથી આવું થાય.", "${f.rhrUp!!.roundToInt()} above usual: tiredness, stress or illness can do this."), 1,
        ) else if ((f.rhrUp ?: 0.0) <= -4) notes += Note(
            t("હૃદય શાંત છે", "Calmer heart"),
            t("આરામના ધબકારા હંમેશા કરતાં ${-f.rhrUp!!.roundToInt()} ઓછા. સારી નિશાની.", "Resting heart rate ${-f.rhrUp!!.roundToInt()} below usual. A good sign."), 0,
        )
        val prev = (8..14).mapNotNull { Stats.dayValue(db, M.STEPS, today.minusDays(it.toLong())) }
        if (avg != null && prev.size >= 3) {
            val c = ((avg - prev.average()) / prev.average() * 100).roundToInt()
            if (abs(c) >= 10) notes += Note(
                t(if (c > 0) "વધારે ચાલો છો" else "ચાલવાનું ઘટ્યું", if (c > 0) "Walking more" else "Walking less"),
                t("રોજ સરેરાશ %,d પગલાં, ગયા અઠવાડિયા કરતાં %d%% %s.".format(avg.roundToInt(), abs(c), if (c > 0) "વધારે" else "ઓછા"), "%,d steps a day, %d%% %s than the week before.".format(avg.roundToInt(), abs(c), if (c > 0) "more" else "fewer")),
                if (c > 0) 0 else 1,
            )
        }
        return Plan(
            level, r?.score, r?.provisional == true, headline, reason, move, detail, done, f.steps, target, stepsDetail,
            bedMin?.let { clock(it) }, bedDetail, notes.sortedByDescending { it.tone }.take(3),
        )
    }

    /** Ready answers to the everyday questions, worked out on the phone. */
    enum class Question(val gu: String, val en: String) {
        TODAY("આજે હું કેમ છું?", "How am I today?"),
        SLEEP("મારી ઊંઘ કેવી રહી?", "How did I sleep?"),
        PROGRESS("હું સુધરું છું?", "Am I improving?"),
        WEEK("આ અઠવાડિયું કેવું?", "How was my week?"),
    }

    fun answer(db: Db, p: RacePrefs, q: Question, gu: Boolean, today: LocalDate = LocalDate.now()): String {
        val t = L(gu)
        val plan = plan(db, p, gu, today)
        return when (q) {
            Question.TODAY -> buildString {
                append(plan.headline).append(". ").appendLine(plan.reason)
                plan.score?.let { appendLine(t("રિકવરી $it/100", "Recovery $it/100") + if (plan.provisional) t(" (શરૂઆતનો અંદાજ)", " (early estimate)") else "") }
                appendLine(); appendLine(t("કરો: ", "Do: ") + plan.move + ". " + plan.moveDetail)
                plan.doneToday?.let { appendLine(it) }
                append(t("પગલાં: ", "Steps: ") + "%,d / %,d".format(plan.stepsNow, plan.stepsTarget))
                plan.bedtime?.let { append("\n" + t("સૂવાનો સમય: ", "Bedtime: ") + it) }
            }
            Question.SLEEP -> {
                val s = Stats.sleepFor(db, today) ?: return t("ગઈ રાતની ઊંઘ નોંધાઈ નથી.", "No sleep was recorded last night.")
                val past = (1..7).mapNotNull { Stats.sleepFor(db, today.minusDays(it.toLong()))?.asleep }
                buildString {
                    appendLine(t("ગઈ રાત્રે ${dur(s.asleep, t)} ઊંઘ્યા. લક્ષ્ય ${dur(p.sleepGoalMin.toDouble(), t)}.", "Last night you slept ${dur(s.asleep, t)}. Goal ${dur(p.sleepGoalMin.toDouble(), t)}."))
                    if (s.deep + s.rem > 0) appendLine(t("ગાઢ ઊંઘ ${dur(s.deep, t)}, REM ${dur(s.rem, t)}.", "Deep ${dur(s.deep, t)}, REM ${dur(s.rem, t)}."))
                    if (past.size >= 2) {
                        val d = s.asleep - past.average()
                        appendLine(if (abs(d) < 15) t("આ તમારી સરેરાશ જેટલી જ છે.", "About your recent average.") else t("સરેરાશ કરતાં ${dur(abs(d), t)} ${if (d > 0) "વધારે" else "ઓછી"}.", "${dur(abs(d), t)} ${if (d > 0) "more" else "less"} than your recent average."))
                    }
                    plan.bedtime?.let { append("\n" + t("આજે રાત્રે $it વાગ્યે સૂઈ જાવ. ", "Go to bed at $it tonight. ") + plan.bedDetail) }
                }
            }
            Question.PROGRESS -> {
                val walks = db.workouts(100)
                buildString {
                    if (plan.notes.isNotEmpty()) plan.notes.forEach { appendLine("• " + it.text) }
                    val week = Stats.dayStart(today.minusDays(6)); val prev = Stats.dayStart(today.minusDays(13))
                    val a = walks.filter { it.start >= week }; val b = walks.filter { it.start in prev until week }
                    appendLine("• " + t("7 દિવસમાં ${a.size} કસરત (${dur(a.sumOf { it.movingMs } / 60000.0, t)}), એ પહેલાંના 7 દિવસમાં ${b.size}.", "${a.size} workouts (${dur(a.sumOf { it.movingMs } / 60000.0, t)}) in 7 days, ${b.size} in the 7 before."))
                    val tests = db.series(M.HRR1, 0, Long.MAX_VALUE)
                    if (tests.isNotEmpty()) append("• " + t("છેલ્લા heart test માં 1 મિનિટે ${tests.last().value.roundToInt()} ધબકારા ઘટ્યા.", "Last heart test: ${tests.last().value.roundToInt()} beats lower after 1 minute."))
                    else append("• " + t("Fitness માપવા walk પછી Heart test કરો.", "Do a heart test after a walk to track fitness."))
                }
            }
            Question.WEEK -> {
                val days = (1..7).map { today.minusDays(it.toLong()) }
                val steps = days.mapNotNull { d -> Stats.dayValue(db, M.STEPS, d)?.let { d to it } }
                val sleep = days.mapNotNull { Stats.sleepFor(db, it)?.asleep }
                buildString {
                    if (steps.isNotEmpty()) {
                        appendLine("• " + t("પગલાં: રોજ સરેરાશ %,d".format(steps.map { it.second }.average().roundToInt()), "Steps: %,d a day on average".format(steps.map { it.second }.average().roundToInt())))
                        val best = steps.maxBy { it.second }
                        appendLine("• " + t("સૌથી સારો દિવસ: ${best.first.dayOfMonth} તારીખ, %,d પગલાં".format(best.second.roundToInt()), "Best day: the ${best.first.dayOfMonth}, %,d steps".format(best.second.roundToInt())))
                        appendLine("• " + t("લક્ષ્ય ${steps.count { it.second >= p.stepGoal }} દિવસ પૂરું", "Goal reached on ${steps.count { it.second >= p.stepGoal }} days"))
                    }
                    if (sleep.isNotEmpty()) appendLine("• " + t("ઊંઘ: સરેરાશ ${dur(sleep.average(), t)}", "Sleep: ${dur(sleep.average(), t)} on average"))
                    val w = db.workouts(100).filter { it.start >= Stats.dayStart(days.last()) }
                    append("• " + t("કસરત: ${w.size}, કુલ ${dur(w.sumOf { it.movingMs } / 60000.0, t)}", "Workouts: ${w.size}, ${dur(w.sumOf { it.movingMs } / 60000.0, t)} in total"))
                }
            }
        }.trim()
    }
}
