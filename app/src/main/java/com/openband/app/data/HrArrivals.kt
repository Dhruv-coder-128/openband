package com.openband.app.data

/**
 * The times of the band's own automatic heart-rate readings. They sit exactly on five-minute marks counted from
 * midnight (that is how the band stores them), which tells them apart from live measurements.
 */
object HrArrivals {
    private const val GRID_MS = 5 * 60_000L

    /** [times] oldest first; [gapMinutes] between the last two. */
    data class Result(val times: List<Long>, val gapMinutes: Int?)

    fun of(rows: List<Sample>, midnight: Long, keep: Int = 5): Result? {
        val times = rows.filter { it.source == SOURCE_BAND && (it.ts - midnight) % GRID_MS == 0L }.map { it.ts }.distinct().sorted().takeLast(keep)
        if (times.isEmpty()) return null
        val gap = if (times.size < 2) null else ((times.last() - times[times.size - 2]) / 60_000).toInt()
        return Result(times, gap)
    }

    fun today(db: Db): Result? {
        val (from, to) = Stats.todayRange()
        return of(db.series(M.HR, from, to), from)
    }
}
