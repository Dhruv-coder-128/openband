package com.openband.app.workout

import com.openband.app.data.RoutePt
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Finds rounds in a GPS track: walking or running circles around a park, a ground or a society.
 *
 * The track is resampled every few metres. Each point along it is tried as a "lap line": a lap is counted whenever the
 * track comes back past that point, travelling the same way, after covering a believable distance. The lap line that
 * produces the most laps of similar length wins, so it works even when the circles start somewhere in the middle of the
 * walk. Walking out and coming back the same road is not counted, because the direction is opposite.
 */
object Laps {

    data class Lap(val number: Int, val meters: Double, val seconds: Int)

    data class Result(val laps: List<Lap>, /** Where laps are counted from. */ val line: RoutePt, val startAtSec: Int, val endAtSec: Int) {
        val count get() = laps.size
        val avgMeters get() = laps.sumOf { it.meters } / laps.size
        val avgSeconds get() = laps.sumOf { it.seconds } / laps.size
        val fastest get() = laps.minBy { it.seconds / it.meters }
    }

    private class P(val x: Double, val y: Double, val d: Double, val t: Double, val lat: Double, val lon: Double)

    private const val STEP = 4.0          // metres between samples
    private const val NEAR = 18.0         // how close counts as "passing the line" (phone GPS wanders 5-15 m)
    private const val MIN_LAP = 90.0      // shorter loops than this are GPS jitter

    fun detect(route: List<RoutePt>): Result? {
        if (route.size < 8) return null
        val pts = resample(route)
        if (pts.size < 2 || pts.last().d < MIN_LAP * 1.5) return null

        // direction of travel at each sample, taken over ~24 m so single noisy fixes don't matter
        val hx = DoubleArray(pts.size); val hy = DoubleArray(pts.size)
        for (i in pts.indices) {
            val a = pts[(i - 3).coerceAtLeast(0)]; val b = pts[(i + 3).coerceAtMost(pts.size - 1)]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len > 0.5) { hx[i] = (b.x - a.x) / len; hy[i] = (b.y - a.y) / len }
        }

        var best: List<Int> = emptyList()
        var anchor = 0
        val stride = maxOf(3, pts.size / 150)
        var i = 0
        while (i < pts.size) {
            val passes = passes(pts, hx, hy, i)
            if (passes.size > best.size) { best = passes; anchor = i }
            i += stride
        }
        if (best.size < 2) return null

        // keep laps of a believable, similar length (rejects a one-off crossing of the same spot)
        val lengths = (1 until best.size).map { pts[best[it]].d - pts[best[it - 1]].d }
        val median = lengths.sorted()[lengths.size / 2]
        val laps = ArrayList<Lap>()
        var first = -1; var last = -1
        for (k in 1 until best.size) {
            val len = lengths[k - 1]
            if (len < median * 0.65 || len > median * 1.5) continue
            laps += Lap(laps.size + 1, len, (pts[best[k]].t - pts[best[k - 1]].t).toInt().coerceAtLeast(1))
            if (first < 0) first = best[k - 1]
            last = best[k]
        }
        if (laps.isEmpty()) return null

        // The walk usually starts and stops a little way off the line, or GPS drifts while it settles, so one round can be
        // missed by the line crossing alone. Measure how far the track stayed on this loop and count the rounds in that.
        val lapLen = laps.map { it.meters }.sorted()[laps.size / 2]
        val ref = pts.filter { it.d >= pts[first].d && it.d <= pts[first].d + lapLen }
        fun onLoop(p: P) = ref.any { hypot(it.x - p.x, it.y - p.y) <= NEAR * 1.5 }
        var s = first; while (s > 0 && onLoop(pts[s - 1])) s--
        var e = last; while (e < pts.size - 1 && onLoop(pts[e + 1])) e++
        val rounds = ((pts[e].d - pts[s].d) / lapLen + 0.12).toInt()
        if (rounds > laps.size) {
            fun indexAt(d: Double) = ((d / STEP).toInt()).coerceIn(0, pts.size - 1)
            val whole = (1..rounds).map { k ->
                val from = pts[indexAt(pts[s].d + (k - 1) * lapLen)]
                val to = pts[indexAt(minOf(pts[s].d + k * lapLen, pts[e].d))]
                Lap(k, to.d - from.d, (to.t - from.t).toInt().coerceAtLeast(1))
            }
            val start = pts[s]
            return Result(whole, RoutePt(start.lat, start.lon, start.t.toInt()), start.t.toInt(), pts[indexAt(minOf(pts[s].d + rounds * lapLen, pts[e].d))].t.toInt())
        }
        // a single "lap" only means something when it is a loop back to where the walk began
        if (laps.size == 1 && pts[anchor].d > 40) return null
        val a = pts[anchor]
        return Result(laps, RoutePt(a.lat, a.lon, a.t.toInt()), pts[first].t.toInt(), pts[last].t.toInt())
    }

    /** Indices where the track goes past sample [anchor] in the same direction, one per lap. */
    private fun passes(pts: List<P>, hx: DoubleArray, hy: DoubleArray, anchor: Int): List<Int> {
        val a = pts[anchor]
        val out = ArrayList<Int>()
        var bestInVisit = -1
        var bestDist = Double.MAX_VALUE
        var i = anchor
        while (i < pts.size) {
            val p = pts[i]
            val dist = hypot(p.x - a.x, p.y - a.y)
            val sameWay = hx[i] * hx[anchor] + hy[i] * hy[anchor] > 0.4
            if (dist <= NEAR && sameWay) {
                if (dist < bestDist) { bestDist = dist; bestInVisit = i }
            } else if (dist > NEAR * 1.4 && bestInVisit >= 0) {
                if (out.isEmpty() || pts[bestInVisit].d - pts[out.last()].d >= MIN_LAP) out += bestInVisit
                bestInVisit = -1; bestDist = Double.MAX_VALUE
            }
            i++
        }
        if (bestInVisit >= 0 && (out.isEmpty() || pts[bestInVisit].d - pts[out.last()].d >= MIN_LAP)) out += bestInVisit
        return out
    }

    private fun resample(route: List<RoutePt>): List<P> {
        val lat0 = route.first().lat; val lon0 = route.first().lon
        val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 110_540.0
        val xs = DoubleArray(route.size) { (route[it].lon - lon0) * kx }
        val ys = DoubleArray(route.size) { (route[it].lat - lat0) * ky }
        // light smoothing
        fun avg(v: DoubleArray, i: Int): Double {
            val from = (i - 1).coerceAtLeast(0); val to = (i + 1).coerceAtMost(v.size - 1)
            var s = 0.0
            for (k in from..to) s += v[k]
            return s / (to - from + 1)
        }
        val sx = DoubleArray(route.size) { avg(xs, it) }; val sy = DoubleArray(route.size) { avg(ys, it) }
        val out = ArrayList<P>()
        var total = 0.0
        var next = 0.0
        for (i in 1 until route.size) {
            val seg = hypot(sx[i] - sx[i - 1], sy[i] - sy[i - 1])
            if (seg <= 0.0 || abs(seg) > 500) continue
            while (next <= total + seg) {
                val f = (next - total) / seg
                val x = sx[i - 1] + (sx[i] - sx[i - 1]) * f; val y = sy[i - 1] + (sy[i] - sy[i - 1]) * f
                val t = route[i - 1].t + (route[i].t - route[i - 1].t) * f
                out += P(x, y, next, t, lat0 + y / ky, lon0 + x / kx)
                next += STEP
            }
            total += seg
        }
        return out
    }

    /** Straight-line distance between two points in metres. */
    fun straight(a: RoutePt, b: RoutePt): Double =
        hypot((b.lon - a.lon) * 111_320.0 * cos(Math.toRadians(a.lat)), (b.lat - a.lat) * 110_540.0)
}
