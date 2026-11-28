package com.openband.app

import com.openband.app.ui.Slosh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SloshTest {

    @Test fun settlesOnTheTargetWithASmallOvershoot() {
        val s = Slosh()
        var peak = 0f
        repeat(360) { peak = maxOf(peak, s.step(0.5f, 1f / 120f)) }
        assertEquals(0.5f, s.position, 0.005f)
        assertTrue("overshoot $peak", peak > 0.5f && peak < 0.62f)
    }

    @Test fun movesTheSameAt60And120FramesASecond() {
        val a = Slosh()
        val b = Slosh()
        repeat(30) { a.step(1f, 1f / 60f) }
        repeat(60) { b.step(1f, 1f / 120f) }
        assertEquals(a.position, b.position, 0.01f)
    }

    @Test fun neverJumpsAfterALongPause() {
        val s = Slosh()
        s.step(1f, 5f)
        assertTrue(abs(s.position) < 1.3f)
    }

    @Test fun eachFrameMovesOnlyALittle() {
        val s = Slosh()
        var before = 0f
        repeat(240) {
            val now = s.step(1f, 1f / 120f)
            assertTrue(abs(now - before) < 0.05f)
            before = now
        }
    }
}
