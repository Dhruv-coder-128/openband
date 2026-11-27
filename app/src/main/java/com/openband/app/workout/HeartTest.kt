package com.openband.app.workout

import android.content.Context
import com.openband.app.ble.BandManager
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Sample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Heart-rate recovery: how far the heart rate falls in the first one and two minutes after you stop exercising.
 * A quick fall is a sign of good fitness. Needs the band's live heart rate.
 */
object HeartTest {

    enum class Phase { IDLE, WAITING, READY, MEASURING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val bpm: Int? = null,
        val startBpm: Int? = null,
        val secondsLeft: Int = 120,
        val drop1: Int? = null,
        val drop2: Int? = null,
        val message: String = "",
    )

    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var measureFrom = 0L

    fun rating(drop1: Int) = when { drop1 >= 30 -> "Excellent" to 0; drop1 >= 20 -> "Good" to 0; drop1 >= 12 -> "Fair" to 1; else -> "Slow" to 2 }

    /**
     * Opens the band's live stream. With [startBpm] (straight after a workout) the two minutes begin now; otherwise the
     * test waits for [stopped] to be tapped.
     */
    fun begin(ctx: Context, startBpm: Int? = null) {
        job?.cancel()
        val immediate = startBpm != null
        measureFrom = if (immediate) System.currentTimeMillis() else 0L
        state.value = State(if (immediate) Phase.MEASURING else Phase.WAITING, startBpm = startBpm, bpm = startBpm)
        BandManager.setWorkoutActive(true)
        val db = Db.get(ctx.applicationContext)
        job = scope.launch {
            val readings = ArrayList<Pair<Long, Int>>()
            var seen = 0L
            val opened = System.currentTimeMillis()
            while (true) {
                delay(1000)
                WorkoutManager.keepHeartRateAlive()
                val now = System.currentTimeMillis()
                val at = BandManager.liveHrAt
                val bpm = BandManager.state.value.liveHr
                if (bpm != null && at != seen && now - at < 4000 && bpm in 30..230) { seen = at; readings += at to bpm }
                val fresh = readings.lastOrNull()?.takeIf { now - it.first < 8000 }?.second
                val s = state.value
                when (s.phase) {
                    Phase.WAITING, Phase.READY -> {
                        if (fresh != null) state.value = s.copy(phase = Phase.READY, bpm = fresh)
                        else if (readings.isEmpty() && now - opened > 90_000) { fail("No heart rate from the band. Wear it snugly and check it is connected."); return@launch }
                        else state.value = s.copy(bpm = null)
                    }
                    Phase.MEASURING -> {
                        val sec = ((now - measureFrom) / 1000).toInt()
                        // the middle reading of a 16-second window around each mark
                        fun around(mark: Int): Int? = readings.filter { (it.first - measureFrom) / 1000 in (mark - 8)..(mark + 8) }
                            .map { it.second }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
                        val start = s.startBpm ?: fresh
                        val d1 = if (sec >= 62 && start != null) around(60)?.let { start - it } else null
                        state.value = s.copy(bpm = fresh, startBpm = start, secondsLeft = (120 - sec).coerceAtLeast(0), drop1 = s.drop1 ?: d1)
                        if (sec >= 128) {
                            val first = state.value.drop1
                            val d2 = if (start != null) around(120)?.let { start - it } else null
                            if (first == null || start == null) fail("The band lost the heart rate during the test. Try again and keep your wrist still.")
                            else {
                                withContext(Dispatchers.IO) {
                                    db.insertSamples(
                                        listOfNotNull(
                                            Sample(measureFrom, M.HRR1, first.toDouble(), "app"),
                                            d2?.let { Sample(measureFrom, M.HRR2, it.toDouble(), "app") },
                                            Sample(measureFrom, M.HRR_START, start.toDouble(), "app"),
                                        ),
                                    )
                                }
                                state.value = state.value.copy(phase = Phase.DONE, drop2 = d2, secondsLeft = 0)
                                release()
                            }
                            return@launch
                        }
                    }
                    else -> return@launch
                }
            }
        }
    }

    /** The "I have stopped" button. */
    fun stopped() {
        val s = state.value
        if (s.phase != Phase.READY) return
        measureFrom = System.currentTimeMillis()
        state.value = s.copy(phase = Phase.MEASURING, startBpm = s.bpm, secondsLeft = 120, drop1 = null, drop2 = null)
    }

    private fun fail(why: String) {
        state.value = state.value.copy(phase = Phase.FAILED, message = why)
        release()
    }

    /** Hands the band back, unless a workout still needs it. */
    private fun release() {
        if (WorkoutManager.active.value != null) return
        BandManager.stopLive()
        BandManager.setWorkoutActive(false)
        scope.launch(Dispatchers.IO) { runCatching { if (BandManager.foreground) BandManager.pollNow(false) } }
    }

    fun cancel() {
        val running = state.value.phase in listOf(Phase.WAITING, Phase.READY, Phase.MEASURING)
        job?.cancel(); job = null
        state.value = State()
        if (running) release()
    }
}
