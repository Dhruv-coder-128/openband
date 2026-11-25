package com.openband.app.data

import java.time.LocalDate
import java.time.ZoneId

/** Aggregation helpers shared by the dashboard and history screens. */
object Stats {

    fun dayStart(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun todayRange(): Pair<Long, Long> {
        val d = LocalDate.now()
        return dayStart(d) to dayStart(d.plusDays(1))
    }

    /**
     * Total of a cumulative metric (steps, calories...). When several sources recorded the same thing
     * (e.g. official app via Health Connect AND the band directly) we take the largest source total
     * instead of adding them, which would double count.
     */
    fun total(db: Db, type: String, from: Long, to: Long): Double? {
        val rows = db.series(type, from, to)
        if (rows.isEmpty()) return null
        val bySource = rows.groupBy { it.source }
        // the band's own data is the truth (it matches the Pebble app exactly); other sources only fill days the band has no data for
        bySource[SOURCE_BAND]?.let { return it.sumOf { r -> r.value } }
        return bySource.values.maxOf { list -> list.sumOf { it.value } }
    }

    data class MinMaxAvg(val min: Double, val max: Double, val avg: Double, val count: Int)

    fun mma(rows: List<Sample>): MinMaxAvg? {
        if (rows.isEmpty()) return null
        return MinMaxAvg(rows.minOf { it.value }, rows.maxOf { it.value }, rows.sumOf { it.value } / rows.size, rows.size)
    }

    /** Value of a metric for a single day according to its aggregation rule. */
    fun dayValue(db: Db, type: String, date: LocalDate): Double? {
        val from = dayStart(date)
        val to = dayStart(date.plusDays(1))
        return when (M.info[type]?.agg ?: M.Agg.AVG) {
            M.Agg.SUM -> total(db, type, from, to)
            M.Agg.AVG -> mma(db.series(type, from, to))?.avg
            M.Agg.LATEST -> db.series(type, from, to).lastOrNull()?.value
        }
    }

    /** One block of the hypnogram. */
    data class SleepSeg(val type: String, val start: Long, val end: Long)

    data class Sleep(
        val deep: Double, val light: Double, val rem: Double, val awake: Double, val other: Double, val start: Long, val end: Long,
        val segments: List<SleepSeg> = emptyList(),
    ) {
        /** Total sleep session including short awakenings (what the official app shows). */
        val total get() = deep + light + rem + other + awake
        /** Time actually asleep. */
        val asleep get() = deep + light + rem + other
        /** Asleep / total, in %. */
        val efficiency get() = if (total > 0) asleep * 100 / total else 0.0
        val wakeUps get() = segments.count { it.type == M.SLEEP_AWAKE }
    }

    /** Sleep that ended on [date]'s morning: window from 18:00 the previous day to 18:00 on [date]. */
    fun sleepFor(db: Db, date: LocalDate): Sleep? {
        val zone = ZoneId.systemDefault()
        val from = date.minusDays(1).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val to = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        val parts = (M.sleepTypes + M.SLEEP_AWAKE).associateWith { t ->
            val rows = db.series(t, from, to)
            // choose the single best source for sleep to avoid double counting
            rows
        }
        val all = parts.values.flatten()
        if (all.isEmpty()) return null
        val bestSource = all.groupBy { it.source }.maxBy { (_, l) -> l.sumOf { it.value } }.key
        fun sum(t: String) = parts[t]!!.filter { it.source == bestSource }.sumOf { it.value }
        val chosen = all.filter { it.source == bestSource }
        return Sleep(
            sum(M.SLEEP_DEEP), sum(M.SLEEP_LIGHT), sum(M.SLEEP_REM), sum(M.SLEEP_AWAKE), sum(M.SLEEP_ASLEEP),
            chosen.minOf { it.ts }, chosen.maxOf { it.endTs },
            chosen.sortedBy { it.ts }.map { SleepSeg(it.type, it.ts, it.endTs) },
        )
    }

    fun fmtMinutes(min: Double): String {
        val m = Math.round(min).toInt()
        return "${m / 60}h ${m % 60}m"
    }
}
