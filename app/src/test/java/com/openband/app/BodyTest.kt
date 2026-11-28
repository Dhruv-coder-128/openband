package com.openband.app

import com.openband.app.data.BodyAge
import com.openband.app.data.BodyEnergy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyTest {

    private fun slots(n: Int, hr: Double? = 70.0, steps: Double = 0.0, stress: Double? = null, asleep: Boolean = false) =
        (0 until n).map { BodyEnergy.Slot(it * BodyEnergy.SLOT_MS, hr, steps, stress, asleep) }

    @Test fun morningFollowsSleepAndRecovery() {
        val good = BodyEnergy.morningLevel(85, 480.0, 480)!!
        val poor = BodyEnergy.morningLevel(30, 240.0, 480)!!
        assertTrue("good $good poor $poor", good > 85 && poor < 60)
        assertNull(BodyEnergy.morningLevel(null, null, 480))
        assertNotNull(BodyEnergy.morningLevel(null, 400.0, 480))
    }

    @Test fun aQuietSixteenHourDayLeavesAboutHalf() {
        val r = BodyEnergy.compute(90, slots(64), 62.0, 195)
        assertTrue("left ${r.now}", r.now in 35..60)
        assertEquals(0, r.usedByActivity)
    }

    @Test fun effortAndStressCostMore() {
        val quiet = BodyEnergy.compute(90, slots(20), 62.0, 195).now
        val run = BodyEnergy.compute(90, slots(20, hr = 150.0), 62.0, 195)
        val walk = BodyEnergy.compute(90, slots(20, steps = 1200.0), 62.0, 195)
        val tense = BodyEnergy.compute(90, slots(20, stress = 90.0), 62.0, 195)
        assertTrue(run.now < walk.now && walk.now < quiet && tense.now < quiet)
        assertTrue(run.usedByActivity > 30 && tense.usedByStress > 10)
    }

    @Test fun restAndNapsGiveSomeBack() {
        val nap = BodyEnergy.compute(40, slots(4, asleep = true), 62.0, 195)
        assertEquals(52, nap.now)
        val calm = BodyEnergy.compute(60, slots(12, hr = 62.0, stress = 20.0), 62.0, 195)
        assertTrue("calm ${calm.now}", calm.now > 60)
    }

    @Test fun staysBetweenZeroAndHundred() {
        assertEquals(0, BodyEnergy.compute(25, slots(60, hr = 185.0), 55.0, 190).now)
        assertEquals(100, BodyEnergy.compute(98, slots(10, asleep = true), 55.0, 190).now)
        assertEquals(80, BodyEnergy.compute(80, emptyList(), null, 190).now)
    }

    private fun input(age: Int = 30, rhr: Double? = 65.0, hrv: Double? = 47.0, sleep: Double? = 8.0, steps: Double? = 7500.0, exercise: Double? = 80.0, weight: Int = 70) =
        BodyAge.Input(age, 175, weight, rhr, hrv, sleep, steps, exercise)

    @Test fun healthyHabitsMakeYouYoungerAndPoorOnesOlder() {
        val fit = BodyAge.compute(input(rhr = 52.0, hrv = 70.0, steps = 12000.0, exercise = 220.0))!!
        val unfit = BodyAge.compute(input(rhr = 82.0, hrv = 22.0, sleep = 5.0, steps = 2000.0, exercise = 0.0, weight = 100))!!
        assertTrue("fit ${fit.bodyAge}", fit.bodyAge in 18..25)
        assertTrue("unfit ${unfit.bodyAge}", unfit.bodyAge in 40..45)
        assertNotNull(unfit.focus)
    }

    @Test fun averageHabitsStayNearYourAge() {
        val r = BodyAge.compute(input())!!
        assertTrue("${r.bodyAge}", r.bodyAge in 26..31)
    }

    @Test fun needsEnoughReadings() {
        assertNull(BodyAge.compute(input(rhr = null, hrv = null, sleep = null)))
        assertNotNull(BodyAge.compute(input(rhr = null, hrv = null, exercise = 60.0)))
    }

    @Test fun usualVariabilityFallsWithAge() {
        assertEquals(60.0, BodyAge.usualHrv(18), 0.001)
        assertEquals(42.0, BodyAge.usualHrv(35), 0.001)
        assertEquals(20.0, BodyAge.usualHrv(80), 0.001)
    }
}
