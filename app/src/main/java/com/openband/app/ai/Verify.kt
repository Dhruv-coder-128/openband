package com.openband.app.ai

import kotlin.math.abs

/**
 * Guards against made-up figures: every measurement-like number in the "what the numbers show" part of an answer must
 * appear in the data that was sent.
 */
object Verify {

    /** Gujarati and Devanagari digits written as ordinary ones. */
    fun asciiDigits(s: String): String = buildString {
        for (ch in s) append(
            when (ch) {
                in '૦'..'૯' -> '0' + (ch - '૦')
                in '०'..'९' -> '0' + (ch - '०')
                else -> ch
            },
        )
    }

    private val NUMBER = Regex("(?<![\\d:.])\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?|(?<![\\d:.,])\\d+(?:\\.\\d+)?")

    private fun numbers(text: String): List<Double> {
        val t = asciiDigits(text)
        return NUMBER.findAll(t).filter { m ->
            // clock times and paces (6:30, 10:35 /km) are formats of their own and are checked as written in the data
            t.getOrNull(m.range.last + 1) != ':'
        }.mapNotNull { it.value.replace(",", "").toDoubleOrNull() }.toList()
    }

    /**
     * Numbers in [facts] that are nowhere in [data]. Small counts (under 20) and calendar years are ignored; a rounded
     * figure within 1 % of a data value is accepted.
     */
    fun unsupported(facts: String, data: String): List<String> {
        val known = numbers(data)
        return numbers(facts).distinct().filter { n ->
            // round targets such as "30 minutes" or "120 bpm" are advice, not claims about the data
            n >= 20 && n !in 2020.0..2035.0 && !(n <= 180 && n % 5 == 0.0) && known.none { k -> abs(k - n) <= maxOf(0.51, abs(k) * 0.01) }
        }.map { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    }

    /** The part of an answer between the facts heading and the advice heading (marked with [[F]] and [[A]]). */
    fun factsOf(answer: String): String {
        val f = answer.indexOf("[[F]]")
        val a = answer.indexOf("[[A]]")
        return when {
            f >= 0 && a > f -> answer.substring(f + 5, a)
            a > 0 -> answer.substring(0, a)
            else -> answer
        }
    }

    /** Removes the markers before showing the answer. */
    fun clean(answer: String) = answer.replace("[[F]]", "").replace("[[A]]", "").replace(Regex("\n{3,}"), "\n\n").trim()
}
