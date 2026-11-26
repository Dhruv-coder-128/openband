package com.openband.app.workout

import com.openband.app.data.RoutePt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class LapsTest {
    private val lat0 = 23.0
    private val lon0 = 72.5
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_540.0

    /** Builds a track from metre offsets, one fix every 3 s, with a little GPS noise. */
    private fun track(pts: List<Pair<Double, Double>>, noise: Double): List<RoutePt> {
        val r = Random(7)
        return pts.mapIndexed { i, (x, y) ->
            RoutePt(lat0 + (y + r.nextDouble(-noise, noise + 1e-9)) / ky, lon0 + (x + r.nextDouble(-noise, noise + 1e-9)) / kx, i * 3)
        }
    }

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double, n: Int) =
        (0 until n).map { (x0 + (x1 - x0) * it / n) to (y0 + (y1 - y0) * it / n) }

    private fun circle(laps: Double, radius: Double) =
        (0..(laps * 90).toInt()).map { val a = 2 * PI * it / 90; radius * cos(a) to radius * sin(a) }

    @Test
    fun countsCirclesFromTheStart() {
        val res = Laps.detect(track(circle(5.05, 50.0), 3.0))!!
        assertEquals(5, res.count)
        assertEquals(314.0, res.avgMeters, 35.0)
    }

    @Test
    fun countsCirclesAfterWalkingThere() {
        val walkIn = line(250.0, 0.0, 50.0, 0.0, 60)
        val walkOut = line(50.0, 0.0, 250.0, 0.0, 60)
        val res = Laps.detect(track(walkIn + circle(4.0, 50.0) + walkOut, 2.0))!!
        assertEquals(4, res.count)
    }

    @Test
    fun twoBigRoundsWithGpsDriftAtTheStart() {
        // two rounds of a ~1.4 km lake; the first fixes are 35 m off while the GPS settles
        val r = 224.0
        val pts = (0..(2.02 * 420).toInt()).map {
            val a = 2 * PI * it / 420
            val off = if (it < 25) 35.0 else 0.0
            (r + off) * cos(a) to (r + off) * sin(a)
        }
        val res = Laps.detect(track(pts, 3.0))!!
        assertEquals(2, res.count)
        assertEquals(1408.0, res.avgMeters, 110.0)
    }

    @Test
    fun oneRoundIsOneRound() {
        assertEquals(1, Laps.detect(track(circle(1.03, 120.0), 2.0))!!.count)
    }

    @Test
    fun straightWalkThereAndBackHasNoLaps() {
        val there = line(0.0, 0.0, 600.0, 0.0, 200)
        val back = line(600.0, 0.0, 0.0, 0.0, 200)
        assertNull(Laps.detect(track(there + back, 2.0)))
    }
}
