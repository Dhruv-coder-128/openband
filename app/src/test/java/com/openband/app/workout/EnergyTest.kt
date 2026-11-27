package com.openband.app.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyTest {

    private fun run(
        sport: String, base: Double, steps: Boolean = false, min: Double = 30.0, kmh: Double? = null, effort: Double? = null,
        total: Boolean = false, band: Double = 0.0, hr: Double? = null,
    ) = Energy.estimate(sport, base, steps, min * 60, 70.0, kmh, effort, total, band, hr, 1.1)

    @Test
    fun walkingMetFollowsSpeed() {
        assertEquals(3.5, Energy.met("walk", 3.5, 4.8, null), 0.01)
        assertEquals(5.0, Energy.met("walk", 3.5, 6.4, null), 0.01)
        assertEquals(9.8, Energy.met("run", 9.8, 9.7, null), 0.01)
    }

    @Test
    fun totalEnergyIsMetTimesWeightTimesHours() {
        // 5 km/h-ish walking, 30 min, 70 kg
        assertEquals(3.5 * 70 * 0.5, run("walk", 3.5, kmh = 4.8, total = true).kcal, 0.5)
        // activity only leaves out the 1 MET the body uses at rest
        assertEquals(2.5 * 70 * 0.5, run("walk", 3.5, kmh = 4.8).kcal, 0.5)
    }

    @Test
    fun stepSportUsesTheBandFigureWhenItIsPlausible() {
        assertEquals(80.0, run("walk", 3.5, steps = true, kmh = 4.8, band = 80.0).kcal, 0.01)
        // a band figure far below the activity (it missed it) is not used
        assertTrue(run("walk", 3.5, steps = true, kmh = 4.8, band = 6.0).kcal > 60)
    }

    @Test
    fun liftingIgnoresTheHeartRateFormula() {
        val e = run("strength", 5.0, effort = 0.62, total = true, hr = 600.0)
        assertEquals(5.0 * 70 * 0.5, e.kcal, 0.5)
        assertEquals(6.0, Energy.met("compound", 6.0, null, 0.8), 0.001)
    }

    @Test
    fun cardioUsesHeartRateOnlyAtRealEffort() {
        assertEquals(300.0, run("hiit", 8.5, effort = 0.75, total = true, hr = 300.0).kcal, 0.01)
        assertEquals(300.0 - 33.0, run("hiit", 8.5, effort = 0.75, hr = 300.0).kcal, 0.01)
        assertEquals(8.5 * 70 * 0.5, run("hiit", 8.5, effort = 0.4, total = true, hr = 100.0).kcal, 0.5)
    }

    @Test
    fun meditationBurnsAlmostNothingExtra() {
        assertTrue(run("meditate", 1.3).kcal < 15)
    }

    @Test
    fun oneRepMaxAndVolume() {
        val s = Strength.SetEntry("bench", 60.0, 8, 0, 70.0)
        assertEquals(480.0, s.volume, 0.001)
        assertEquals(76.0, s.oneRepMax!!, 0.001)
        assertEquals(100.0, Strength.SetEntry("squat", 100.0, 1, 0, 70.0).oneRepMax!!, 0.001)
        assertNull(Strength.SetEntry("squat", 40.0, 20, 0, 70.0).oneRepMax)
    }

    @Test
    fun bodyweightMovementsCountBodyWeight() {
        assertEquals(70.0 + 10.0, Strength.SetEntry("pullup", 10.0, 5, 0, 70.0).load, 0.001)
        assertEquals(0.64 * 70, Strength.SetEntry("pushup", 0.0, 10, 0, 70.0).load, 0.001)
    }

    @Test
    fun muscleSetsCountHelpersAsHalf() {
        val m = Strength.muscleSets(listOf(Strength.SetEntry("bench", 60.0, 8, 0, 70.0), Strength.SetEntry("bench", 60.0, 8, 0, 70.0))).toMap()
        assertEquals(2.0, m.getValue("Chest"), 0.001)
        assertEquals(1.0, m.getValue("Triceps"), 0.001)
    }
}
