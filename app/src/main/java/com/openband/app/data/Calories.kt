package com.openband.app.data

import com.openband.app.ui.RaceSettings
import com.openband.app.ui.RacePrefs
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily energy the way Whoop / Noise / Apple Health present it:
 *   total = resting energy (BMR, spread over the day) + active energy (measured by the band).
 * The band's own kcal figure is activity only, so the resting part is added from the profile (Mifflin-St Jeor).
 */
object Calories {
    /** Resting kcal for a whole day. sex: 0 = not set (average of both), 1 = male, 2 = female. */
    fun bmrPerDay(p: RacePrefs): Double {
        val base = 10.0 * p.weightKg + 6.25 * p.heightCm - 5.0 * p.age
        return when (p.sex) { 1 -> base + 5; 2 -> base - 161; else -> base - 78 }.coerceAtLeast(900.0)
    }

    /** Resting kcal burnt between the start of [date] and [now] (the whole day for past dates). */
    fun restingSoFar(p: RacePrefs, date: LocalDate, now: Long = System.currentTimeMillis()): Double {
        val from = Stats.dayStart(date)
        val to = Stats.dayStart(date.plusDays(1))
        val frac = ((now.coerceIn(from, to) - from).toDouble() / (to - from))
        return bmrPerDay(p) * frac
    }

    /** Total kcal for a day. [active] is what the band measured (may be null = nothing recorded). */
    fun total(active: Double?, date: LocalDate, p: RacePrefs = RaceSettings.prefs.value): Double =
        restingSoFar(p, date) + (active ?: 0.0)

    /** The figure the user chose to see: activity only (same as the Pebble app) or including resting energy. */
    fun shown(active: Double?, date: LocalDate, p: RacePrefs = RaceSettings.prefs.value): Double? =
        if (p.calTotal) total(active, date, p) else active

    /** kcal per minute from heart rate (Keytel et al. 2005); only valid for moderate/high exercise HR. */
    fun perMinuteFromHr(hr: Double, p: RacePrefs): Double {
        val w = p.weightKg.toDouble(); val a = p.age.toDouble()
        val kj = when (p.sex) {
            1 -> -55.0969 + 0.6309 * hr + 0.1988 * w + 0.2017 * a
            2 -> -20.4022 + 0.4472 * hr - 0.1263 * w + 0.074 * a
            else -> (-55.0969 + 0.6309 * hr + 0.1988 * w + 0.2017 * a + -20.4022 + 0.4472 * hr - 0.1263 * w + 0.074 * a) / 2
        }
        return (kj / 4.184).coerceAtLeast(0.0)
    }

    /** Energy of a workout from its heart-rate series (seconds, bpm); null if there is not enough HR data. */
    fun fromHr(hr: List<Pair<Int, Int>>, durationSec: Int, p: RacePrefs): Double? {
        if (hr.size < 3 || durationSec < 60) return null
        val pts = hr.sortedBy { it.first }
        var kcal = 0.0
        var covered = 0.0
        for (i in pts.indices) {
            val next = if (i + 1 < pts.size) pts[i + 1].first else (pts[i].first + 10).coerceAtMost(durationSec)
            val sec = (next - pts[i].first).coerceIn(0, 60)
            val bpm = pts[i].second.toDouble().coerceAtLeast(p.maxHr * 0.4)
            kcal += perMinuteFromHr(bpm, p) * sec / 60.0
            covered += sec
        }
        if (covered < durationSec * 0.5) return null
        // scale the covered part up to the whole session
        return kcal * (durationSec / covered).coerceAtMost(1.5)
    }

    fun dayStartMs(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
