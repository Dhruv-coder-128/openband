package com.openband.app.workout

/**
 * Calories burnt in a workout. MET values follow the Compendium of Physical Activities (Ainsworth et al. 2011);
 * the heart-rate formula is Keytel et al. 2005. Everything is an estimate; the method used is saved with the workout.
 *
 *  - Walking, running, cycling, swimming: intensity from the measured speed.
 *  - Strength training: MET by effort level. Heart-rate formulas overstate lifting, so they are not used.
 *  - Other cardio (HIIT, spinning, games...): heart-rate formula when enough readings exist.
 *  - Yoga, stretching, meditation: MET; heart-rate formulas do not hold at low effort.
 */
object Energy {

    enum class Kind { SPEED, STRENGTH, CARDIO, CALM }

    fun kind(sport: String): Kind = when (sport) {
        "run", "trail", "walk", "hike", "treadmill", "bike", "mtb", "skate", "swim" -> Kind.SPEED
        "strength", "compound" -> Kind.STRENGTH
        "yoga", "pilates", "stretch", "meditate" -> Kind.CALM
        else -> Kind.CARDIO
    }

    private fun table(x: Double, points: List<Pair<Double, Double>>): Double {
        if (x <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            if (x <= x1) {
                val (x0, y0) = points[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return points.last().second
    }

    private val WALK = listOf(2.7 to 2.3, 4.0 to 3.0, 4.8 to 3.5, 5.6 to 4.3, 6.4 to 5.0, 7.2 to 7.0, 8.0 to 8.3)
    private val RUN = listOf(6.4 to 6.0, 8.0 to 8.3, 9.7 to 9.8, 10.8 to 10.5, 11.3 to 11.0, 12.9 to 11.8, 14.5 to 12.8, 16.1 to 14.5, 17.7 to 16.0)
    private val BIKE = listOf(12.0 to 4.0, 17.5 to 6.8, 20.5 to 8.0, 24.0 to 10.0, 28.0 to 12.0, 33.0 to 15.8)
    /** Freestyle swimming, by speed in km/h (about 3:00 to 1:15 per 100 m). */
    private val SWIM = listOf(2.0 to 4.8, 2.7 to 5.8, 3.3 to 8.3, 4.0 to 10.0, 4.8 to 13.8)

    /**
     * MET for a sport. [kmh] is the average speed when known, [effort] the average heart rate as a share of maximum
     * (null when unknown), [base] the sport's typical value.
     */
    fun met(sport: String, base: Double, kmh: Double?, effort: Double?): Double {
        val v = kmh?.takeIf { it > 1.0 }
        return when {
            v != null && (sport == "walk" || sport == "hike") -> if (sport == "hike") maxOf(6.0, table(v, WALK)) else table(v, WALK)
            v != null && (sport == "run" || sport == "trail" || sport == "treadmill") -> if (v < 6.4) table(v, WALK) else table(v, RUN)
            v != null && sport == "bike" -> table(v, BIKE)
            v != null && sport == "swim" -> table(v, SWIM)
            sport == "strength" || sport == "compound" -> {
                // light / moderate / vigorous lifting; big compound lifts sit one step higher
                val level = when { effort == null -> 1; effort < 0.55 -> 0; effort < 0.70 -> 1; else -> 2 }
                if (sport == "compound") listOf(5.0, 6.0, 6.0)[level] else listOf(3.5, 5.0, 6.0)[level]
            }
            else -> base
        }
    }

    data class Estimate(val kcal: Double, val method: String)

    /**
     * @param total true = include the energy the body uses anyway (Whoop style); false = activity only (like Pebble).
     * @param bandKcal activity calories the band counted during the workout (only meaningful for step-based sports).
     * @param hrKcal heart-rate formula result (total energy) or null when readings are too thin.
     * @param restPerMin resting energy per minute.
     */
    fun estimate(
        sport: String, base: Double, steps: Boolean, seconds: Double, kg: Double, kmh: Double?, effort: Double?,
        total: Boolean, bandKcal: Double, hrKcal: Double?, restPerMin: Double,
    ): Estimate {
        val hours = seconds / 3600.0
        val m = met(sport, base, kmh, effort)
        val byMet = (if (total) m else (m - 1.0).coerceAtLeast(0.0)) * kg * hours
        val metWhy = if (kmh != null && kind(sport) == Kind.SPEED) "Speed-based (%.1f MET)".format(m) else "Activity table (%.1f MET)".format(m)
        val rest = restPerMin * seconds / 60.0
        val byHr = hrKcal?.let { if (total) it else (it - rest).coerceAtLeast(0.0) }
        return when (kind(sport)) {
            Kind.SPEED -> when {
                // the band's figure matches the Pebble app; ignore it when it clearly missed the activity (cycling, swimming)
                !total && steps && bandKcal >= 5 && bandKcal >= byMet * 0.5 -> Estimate(bandKcal, "Band (same basis as Pebble)")
                total && byHr != null -> Estimate(byHr, "Heart-rate formula")
                else -> Estimate(byMet, metWhy)
            }
            Kind.STRENGTH, Kind.CALM -> Estimate(byMet, metWhy)
            Kind.CARDIO -> if (byHr != null && (effort ?: 0.0) >= 0.5) Estimate(byHr, "Heart-rate formula") else Estimate(byMet, metWhy)
        }
    }
}
