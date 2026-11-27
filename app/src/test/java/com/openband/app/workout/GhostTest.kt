package com.openband.app.workout

import com.openband.app.data.RoutePt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GhostTest {
    /** 1 km due north at a steady 2 m/s. */
    private val track = (0..100).map { RoutePt(21.5 + it * 10.0 / 110_540.0, 70.45, it * 5) }
    private val ghost = Ghost.Choice("Last time", track, 1000.0, null, 1)

    @Test
    fun ghostTimeAndPlaceMatchTheRecording() {
        assertEquals(250.0, ghost.timeAt(500.0)!!, 2.0)
        assertEquals(500.0, ghost.at(250.0).first, 4.0)
    }

    @Test
    fun ghostStopsAtItsFinish() {
        assertNull(ghost.timeAt(1500.0))
        assertEquals(1000.0, ghost.at(9999.0).first, 1.0)
    }

    @Test
    fun targetPaceGhost() {
        val t = Ghost.target(600.0)
        assertEquals(300.0, t.timeAt(500.0)!!, 0.001)
        assertEquals(1000.0, t.at(600.0).first, 0.001)
    }
}
