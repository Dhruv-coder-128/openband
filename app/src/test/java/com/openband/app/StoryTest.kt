package com.openband.app

import com.openband.app.data.HrArrivals
import com.openband.app.data.Insights
import com.openband.app.data.Sample
import com.openband.app.data.Story
import com.openband.app.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StoryTest {

    private val monday = LocalDate.of(2026, 11, 16)

    private fun period(from: LocalDate, days: Int, steps: Double?, sleep: Double?, workouts: List<Workout> = emptyList()) = Insights.Period(
        from, from.plusDays(days - 1L),
        (0 until days).map { i -> Insights.Day(from.plusDays(i.toLong()), steps?.plus(i * 10), steps?.let { 250.0 }, steps?.times(0.75), sleep, sleep?.let { 62.0 }, sleep?.let { 41.0 }, sleep?.let { 97.0 }, sleep?.let { 38.0 + i % 7 }) },
        workouts,
    )

    private fun walk(date: LocalDate) = date.atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli().let {
        Workout(1, "walk", it, it + 2_400_000, 0, 3000.0, 4000, 150.0, 110, 130, 90, emptyList(), emptyList(), false)
    }

    private val goals = Story.Goals(10000, 480)

    private fun build(now: Insights.Period, before: Insights.Period, monthly: Boolean = false, today: LocalDate = now.to.plusDays(1)) =
        Story.build(now, before, monthly, goals, listOf(now.from to 70), 22, today)

    @Test fun aFullWeekHasACardForEachSubject() {
        val deck = build(period(monday, 7, 8000.0, 420.0, listOf(walk(monday))), period(monday.minusDays(7), 7, 6000.0, 400.0))!!
        assertEquals(Story.Kind.entries.toList(), deck.slides.map { it.kind })
        val steps = deck.slides.first { it.kind == Story.Kind.STEPS }
        assertEquals("56,210", steps.big)
        assertTrue(steps.note, steps.note.contains("Up 3"))
        assertEquals(7, steps.bars.size)
        assertTrue(deck.facts.contains("Average steps a day: 8030"))
        assertTrue(deck.slides.first { it.kind == Story.Kind.SCORE }.lines.size == 4)
    }

    @Test fun missingSubjectsAreLeftOut() {
        val deck = build(period(monday, 7, 3000.0, null), period(monday.minusDays(7), 7, null, null))!!
        val kinds = deck.slides.map { it.kind }
        assertTrue(Story.Kind.SLEEP !in kinds && Story.Kind.HEART !in kinds && Story.Kind.BODY !in kinds)
        assertEquals("No workouts recorded", deck.slides.first { it.kind == Story.Kind.WORKOUTS }.title)
        // nothing earlier to compare with, so no comparison is claimed
        assertTrue(!deck.slides.first { it.kind == Story.Kind.STEPS }.note.contains("before"))
    }

    @Test fun anEmptyPeriodHasNoStory() {
        assertNull(build(period(monday, 7, null, null), period(monday.minusDays(7), 7, null, null)))
    }

    @Test fun aMonthLabelsOnlySomeDays() {
        val first = LocalDate.of(2026, 11, 1)
        val deck = build(period(first, 30, 7000.0, 450.0), period(first.minusMonths(1), 31, 7000.0, 450.0), monthly = true)!!
        val bars = deck.slides.first { it.kind == Story.Kind.STEPS }.bars
        assertEquals(30, bars.size)
        assertEquals(listOf("1", "5", "10", "15", "20", "25", "30"), bars.map { it.label }.filter { it.isNotEmpty() })
        assertEquals("November 2026", deck.slides.first().note)
    }

    @Test fun aRunningWeekSaysSo() {
        val deck = build(period(monday, 7, 5000.0, 400.0), period(monday.minusDays(7), 7, 5000.0, 400.0), today = monday.plusDays(2))!!
        assertEquals("This week so far", deck.slides.first().kicker)
        assertTrue(deck.facts.contains("3 days so far"))
    }

    @Test fun automaticReadingsAreTheOnesOnFiveMinuteMarks() {
        val mid = 1_000_000_000L
        fun hr(minute: Int, extraMs: Long = 0, source: String = "band") = Sample(mid + minute * 60_000L + extraMs, "hr", 80.0, source)
        val rows = listOf(hr(600), hr(610), hr(613, 4321), hr(620), hr(625, 0, "com.pebble.ring.app"), hr(630))
        val r = HrArrivals.of(rows, mid)!!
        assertEquals(listOf(600, 610, 620, 630), r.times.map { ((it - mid) / 60_000).toInt() })
        assertEquals(10, r.gapMinutes)
        assertNull(HrArrivals.of(listOf(hr(1, 5)), mid))
    }
}
