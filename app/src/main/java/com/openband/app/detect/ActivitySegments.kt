package com.openband.app.detect

/**
 * Turns the phone's "started walking / stopped walking" events into whole activities worth asking about: short pauses
 * (a signal, a shoe lace) are joined, brief movement is ignored.
 */
object ActivitySegments {
    const val WALK = "walk"
    const val RUN = "run"
    const val BIKE = "bike"

    /** A pause shorter than this does not end an activity. */
    const val JOIN_MS = 4 * 60_000L

    /** Shortest activity worth asking about. Walking is common, so it needs to be longer. */
    fun minimum(sport: String) = if (sport == WALK) 12 * 60_000L else 8 * 60_000L

    /** [end] = 0 while it is still going on. */
    data class Segment(val sport: String, val start: Long, val end: Long = 0) {
        val open get() = end == 0L
        val length get() = if (open) 0L else end - start
        fun encode() = "$sport,$start,$end"

        companion object {
            fun decode(s: String): Segment? {
                val f = s.split(',')
                if (f.size != 3) return null
                return Segment(f[0], f[1].toLongOrNull() ?: return null, f[2].toLongOrNull() ?: return null)
            }
        }
    }

    /** The activity being followed (open, or just ended and still able to continue) and those ready to ask about. */
    data class State(val current: Segment? = null, val ready: List<Segment> = emptyList())

    fun event(state: State, sport: String, entering: Boolean, at: Long): State {
        val s = settle(state, at)
        val c = s.current
        return if (entering) {
            when {
                c == null -> s.copy(current = Segment(sport, at))
                // the same activity again after a short pause: carry on
                c.sport == sport -> s.copy(current = c.copy(end = 0))
                else -> close(s, c, at).copy(current = Segment(sport, at))
            }
        } else {
            if (c != null && c.sport == sport && c.open) s.copy(current = c.copy(end = at)) else s
        }
    }

    /** Moves an ended activity to the ready list once the pause is too long for it to continue. */
    fun settle(state: State, now: Long): State {
        val c = state.current ?: return state
        // an activity that never reports an end (phone restarted) is dropped after 6 hours
        if (c.open) return if (now - c.start > 6 * 3_600_000L) state.copy(current = null) else state
        if (now - c.end < JOIN_MS) return state
        return close(state, c, c.end).copy(current = null)
    }

    private fun close(state: State, c: Segment, at: Long): State {
        val done = if (c.open) c.copy(end = at) else c
        return if (done.length >= minimum(done.sport)) state.copy(current = null, ready = state.ready + done) else state.copy(current = null)
    }

    /** True when [segment] mostly happened during something already recorded. */
    fun covered(segment: Segment, recorded: List<Pair<Long, Long>>): Boolean = recorded.any { (a, b) ->
        val overlap = minOf(b, segment.end) - maxOf(a, segment.start)
        overlap > segment.length / 3
    }
}
