package com.openband.app.workout

import com.openband.app.data.RoutePt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class RouteMathTest {
    private val lat0 = 21.5
    private val lon0 = 70.45
    private fun pt(x: Double, y: Double, t: Int) =
        RoutePt(lat0 + y / 110_540.0, lon0 + x / (111_320.0 * cos(Math.toRadians(lat0))), t)

    /** 3 km east: 2 m/s, except the middle kilometre at 4 m/s. */
    private fun varied(): List<RoutePt> {
        val out = ArrayList<RoutePt>()
        var x = 0.0; var t = 0
        while (x <= 3000.0) {
            out += pt(x, 0.0, t)
            x += if (x >= 1000 && x < 2000) 20.0 else 10.0
            t += 5
        }
        return out
    }

    @Test
    fun fastestKilometreIsTheQuickMiddleOne() {
        assertEquals(250.0, RouteMath.bestEffort(varied(), 1000.0, 12.0)!!, 8.0)
    }

    @Test
    fun noRecordForADistanceNeverCovered() {
        assertNull(RouteMath.bestEffort(varied(), 5000.0, 12.0))
    }

    @Test
    fun gpsJumpDoesNotCreateARecord() {
        val r = varied().take(60).toMutableList()
        r.add(30, pt(2900.0, 0.0, r[29].t + 1))   // one wild fix
        assertNull(RouteMath.bestEffort(r, 1000.0, 12.0))
    }

    private fun loop(laps: Double, r: Double, cx: Double = 0.0) =
        (0..(laps * 120).toInt()).map { val a = 2 * PI * it / 120; pt(cx + r * cos(a), r * sin(a), it * 3) }

    @Test
    fun sameLakeWithMoreRoundsIsTheSameRoute() {
        assertTrue(RouteMath.sameRoute(loop(1.0, 220.0), loop(3.0, 224.0)))
    }

    @Test
    fun aDifferentLoopIsNotTheSameRoute() {
        assertFalse(RouteMath.sameRoute(loop(1.0, 220.0), loop(1.0, 220.0, cx = 800.0)))
    }

    @Test
    fun heatGrowsWithRepeatedPasses() {
        val h = RouteMath.heat(listOf(loop(4.0, 200.0)))
        assertTrue(h.maxOf { it.level } >= 3)
        assertEquals(1, RouteMath.heat(listOf(varied())).maxOf { it.level })
    }
}
