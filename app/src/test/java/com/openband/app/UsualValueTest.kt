package com.openband.app

import com.openband.app.data.Sample
import com.openband.app.ui.usualValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsualValueTest {

    private fun at(hour: Int, second: Int, v: Double) = Sample(hour * 3_600_000L + second * 1000L, "hr", v, "band")

    @Test fun aBusyHourCountsOnlyOnce() {
        val quiet = (0 until 20).map { at(it, 0, 70.0) }
        val workout = (0 until 900).map { at(30, it, 160.0) }
        assertEquals(70, usualValue(quiet + workout))
    }

    @Test fun needsTenHours() {
        assertNull(usualValue((0 until 9).map { at(it, 0, 70.0) }))
        assertNull(usualValue((0 until 500).map { at(1, it, 70.0) }))
    }
}
