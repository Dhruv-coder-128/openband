package com.openband.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyTest {
    private val data = "Thu 26 Nov | 6309 | 271 | 6h 39m | 61 | 36 | 98\nwalk, 2.87 km, pace 11:06/km, HR avg 117"

    @Test
    fun figuresFromTheDataPass() {
        assertTrue(Verify.unsupported("You walked 6,309 steps with 98% oxygen and heart rate 117. Pace 11:06.", data).isEmpty())
    }

    @Test
    fun inventedFiguresAreCaught() {
        assertEquals(listOf("9500", "142"), Verify.unsupported("You walked 9,500 steps at 142 bpm.", data))
    }

    @Test
    fun gujaratiDigitsAreChecked() {
        assertTrue(Verify.unsupported("તમે ૬૩૦૯ પગલાં ચાલ્યા અને હાર્ટ રેટ ૧૧૭ હતો", data).isEmpty())
        assertEquals(listOf("8000"), Verify.unsupported("તમે ૮૦૦૦ પગલાં ચાલ્યા", data))
    }

    @Test
    fun smallCountsAndAdviceSectionAreIgnored() {
        val answer = "Summary\n[[F]]\n• 2 walks, 117 bpm\n[[A]]\n• Walk 9,000 steps"
        assertTrue(Verify.unsupported(Verify.factsOf(answer), data).isEmpty())
        assertTrue("[[" !in Verify.clean(answer))
    }
}
