package com.openband.app

import com.openband.app.data.Insights
import com.openband.app.data.WeeklyRecap
import com.openband.app.detect.ActivitySegments
import com.openband.app.detect.ActivitySegments.Segment
import com.openband.app.detect.ActivitySegments.State
import com.openband.app.notify.Buzz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DetectRecapBuzzTest {

    private fun min(m: Int) = 1_000_000L + m * 60_000L

    private fun run(vararg events: Triple<String, Boolean, Int>): State =
        events.fold(State()) { s, (sport, entering, at) -> ActivitySegments.event(s, sport, entering, min(at)) }

    @Test fun aRideIsReadyAfterThePause() {
        val s = run(Triple("bike", true, 0), Triple("bike", false, 20))
        assertTrue(s.ready.isEmpty())
        val done = ActivitySegments.settle(s, min(25))
        assertEquals(listOf(Segment("bike", min(0), min(20))), done.ready)
        assertNull(done.current)
    }

    @Test fun shortPausesAreJoined() {
        val s = run(Triple("walk", true, 0), Triple("walk", false, 8), Triple("walk", true, 10), Triple("walk", false, 18))
        val done = ActivitySegments.settle(s, min(30))
        assertEquals(listOf(Segment("walk", min(0), min(18))), done.ready)
    }

    @Test fun shortMovementIsIgnored() {
        val s = run(Triple("walk", true, 0), Triple("walk", false, 6))
        assertTrue(ActivitySegments.settle(s, min(20)).ready.isEmpty())
    }

    @Test fun changingSportClosesThePreviousOne() {
        val s = run(Triple("walk", true, 0), Triple("run", true, 15), Triple("run", false, 30))
        val done = ActivitySegments.settle(s, min(40))
        assertEquals(listOf("walk", "run"), done.ready.map { it.sport })
    }

    @Test fun segmentsSurviveSaving() {
        val a = Segment("bike", 5, 0)
        assertEquals(a, Segment.decode(a.encode()))
        assertNull(Segment.decode("nonsense"))
    }

    @Test fun recordedWorkoutsAreNotAskedAgain() {
        val s = Segment("walk", min(0), min(30))
        assertTrue(ActivitySegments.covered(s, listOf(min(2) to min(28))))
        assertFalse(ActivitySegments.covered(s, listOf(min(27) to min(60))))
    }

    private fun period(from: LocalDate, steps: Double, sleep: Double) = Insights.Period(
        from, from.plusDays(6),
        (0..6).map { Insights.Day(from.plusDays(it.toLong()), steps + it * 100, 200.0, steps * 0.7, sleep, 60.0, 40.0, 97.0, 40.0) },
        emptyList(),
    )

    @Test fun recapComparesWithTheWeekBefore() {
        val monday = LocalDate.of(2026, 11, 23)
        val r = WeeklyRecap.build(period(monday, 9800.0, 480.0), period(monday.minusDays(7), 7000.0, 430.0), 10000, 480)!!
        assertEquals(70700, r.totalSteps)
        assertEquals(5, r.goalDays)
        assertEquals(monday.plusDays(6), r.bestDay!!.first)
        assertTrue(r.stepsChange!! > 30)
        assertEquals(50.0, r.sleepChangeMin!!, 0.001)
        assertEquals("A much more active week", r.headline)
        assertTrue(r.good.isNotEmpty())
    }

    @Test fun anEmptyWeekHasNoRecap() {
        val monday = LocalDate.of(2026, 11, 23)
        val empty = Insights.Period(monday, monday.plusDays(6), (0..6).map { Insights.Day(monday.plusDays(it.toLong()), null, null, null, null, null, null, null, null) }, emptyList())
        assertNull(WeeklyRecap.build(empty, empty, 10000, 480))
    }

    @Test fun recapIsDueOnSundayNightAndCaughtUpOnMondayMorning() {
        val sunday = LocalDate.of(2026, 11, 29)
        assertNull(WeeklyRecap.due(sunday.atTime(19, 59)))
        assertEquals(sunday, WeeklyRecap.due(sunday.atTime(20, 0)))
        assertEquals(sunday, WeeklyRecap.due(sunday.plusDays(1).atTime(9, 0)))
        assertNull(WeeklyRecap.due(sunday.plusDays(1).atTime(13, 0)))
        assertNull(WeeklyRecap.due(sunday.minusDays(2).atTime(21, 0)))
    }

    @Test fun theNextAlarmIsTheComingSundayEvening() {
        val friday = LocalDateTime.of(2026, 11, 27, 17, 0)
        assertEquals(LocalDateTime.of(2026, 11, 29, 20, 30), WeeklyRecap.nextAlarm(friday))
        assertEquals(LocalDateTime.of(2026, 12, 6, 20, 30), WeeklyRecap.nextAlarm(LocalDateTime.of(2026, 11, 29, 20, 31)))
        assertEquals(LocalDate.of(2026, 11, 22), WeeklyRecap.shownWeek(friday))
        assertEquals(LocalDate.of(2026, 11, 29), WeeklyRecap.shownWeek(LocalDateTime.of(2026, 11, 29, 21, 0)))
    }

    @Test fun patternsSendTheRightNumberOfAlerts() {
        assertEquals(0, Buzz.steps(Buzz.Pattern.OFF, "X").size)
        for ((pattern, count) in listOf(Buzz.Pattern.ONE to 1, Buzz.Pattern.TWO to 2, Buzz.Pattern.THREE to 3)) {
            val steps = Buzz.steps(pattern, "X")
            assertEquals(count * 2, steps.size)
            // every ring is stopped again, so a short buzz never keeps ringing
            steps.chunked(2).forEach { (ring, stop) -> assertEquals(0, ring.messageType); assertTrue(Buzz.stops(stop)) }
        }
        assertNull(Buzz.steps(Buzz.Pattern.FIND, "X").single().messageType)
        assertEquals(0, Buzz.steps(Buzz.Pattern.CALL, "Call").single().messageType)
    }

    @Test fun repeatsAndBurstsAreSkipped() {
        val g = Buzz.Gate()
        assertTrue(g.allow("a", "one", 0))
        assertFalse(g.allow("a", "one", 30_000))     // the same text again
        assertFalse(g.allow("a", "two", 2_000))      // too soon for this app
        assertTrue(g.allow("b", "three", 2_000))     // another app is separate
        assertTrue(g.allow("a", "four", 10_000))
        assertTrue(g.allow("a", "one", 70_000))      // an old text counts again
    }

    @Test fun recapShortLine() {
        val monday = LocalDate.of(2026, 11, 23)
        val r = WeeklyRecap.build(period(monday, 5000.0, 400.0), period(monday.minusDays(7), 5000.0, 400.0), 10000, 480)
        assertNotNull(r)
        assertTrue(r!!.short.contains("0 workouts"))
    }
}
