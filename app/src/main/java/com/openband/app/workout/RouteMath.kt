package com.openband.app.workout

import com.openband.app.data.RoutePt
import kotlin.math.cos
import kotlin.math.floor

/** Pure GPS-track calculations used by records, route comparison and the heatmap. */
object RouteMath {

    /** Distance from the start to every point, in metres. */
    fun cumulative(route: List<RoutePt>): DoubleArray {
        val out = DoubleArray(route.size)
        for (i in 1 until route.size) out[i] = out[i - 1] + Laps.straight(route[i - 1], route[i])
        return out
    }

    /**
     * Fastest time (seconds) in which any continuous stretch of [meters] was covered, or null if the track never covered
     * that far. A jump faster than [maxSpeed] m/s is a GPS glitch and breaks the stretch.
     */
    fun bestEffort(route: List<RoutePt>, meters: Double, maxSpeed: Double): Double? {
        if (route.size < 2) return null
        val cum = cumulative(route)
        var best: Double? = null
        var from = 0                       // first point of the current glitch-free stretch
        var i = 0
        for (j in 1 until route.size) {
            val dt = (route[j].t - route[j - 1].t).coerceAtLeast(1)
            if ((cum[j] - cum[j - 1]) / dt > maxSpeed) { from = j; i = j; continue }
            if (i < from) i = from
            val start = cum[j] - meters
            if (start < cum[from]) continue
            while (i + 1 < j && cum[i + 1] <= start) i++
            val seg = cum[i + 1] - cum[i]
            val f = if (seg > 0) ((start - cum[i]) / seg).coerceIn(0.0, 1.0) else 0.0
            val t0 = route[i].t + (route[i + 1].t - route[i].t) * f
            val time = route[j].t - t0
            if (time > 0 && (best == null || time < best)) best = time
        }
        return best
    }

    /** Points every [step] metres along the track. */
    fun resample(route: List<RoutePt>, step: Double): List<RoutePt> {
        if (route.size < 2) return route
        val out = ArrayList<RoutePt>()
        var total = 0.0
        var next = 0.0
        for (i in 1 until route.size) {
            val a = route[i - 1]; val b = route[i]
            val seg = Laps.straight(a, b)
            if (seg <= 0.0 || seg > 500) continue
            while (next <= total + seg) {
                val f = (next - total) / seg
                out += RoutePt(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, (a.t + (b.t - a.t) * f).toInt())
                next += step
            }
            total += seg
        }
        return out
    }

    data class Cell(val x: Long, val y: Long)

    /** Grid cell of a point; [refLat] fixes the east-west scale so cells line up between tracks. */
    fun cell(p: RoutePt, size: Double, refLat: Double) =
        Cell(floor(p.lon * 111_320.0 * cos(Math.toRadians(refLat)) / size).toLong(), floor(p.lat * 110_540.0 / size).toLong())

    /** Share of track [a] that runs along track [b] (within roughly 30-60 m). */
    fun coverage(a: List<RoutePt>, b: List<RoutePt>): Double {
        if (a.size < 2 || b.size < 2) return 0.0
        val ref = a.first().lat
        val near = HashSet<Cell>()
        for (p in resample(b, 10.0)) {
            val c = cell(p, 30.0, ref)
            for (dx in -1L..1L) for (dy in -1L..1L) near += Cell(c.x + dx, c.y + dy)
        }
        val pts = resample(a, 10.0)
        return if (pts.isEmpty()) 0.0 else pts.count { cell(it, 30.0, ref) in near }.toDouble() / pts.size
    }

    /** Two workouts went the same way (the number of rounds may differ). */
    fun sameRoute(a: List<RoutePt>, b: List<RoutePt>): Boolean =
        a.size > 5 && b.size > 5 && Laps.straight(a.first(), b.first()) < 1500 && coverage(a, b) >= 0.75 && coverage(b, a) >= 0.75

    /** One drawn piece of the heatmap; [level] 1..4 = how often this stretch has been travelled. */
    class Heat(val level: Int, val points: List<RoutePt>)

    fun heat(routes: List<List<RoutePt>>): List<Heat> {
        val all = routes.filter { it.size > 1 }
        if (all.isEmpty()) return emptyList()
        val ref = all.first().first().lat
        val sampled = all.map { resample(it, 8.0) }
        val count = HashMap<Cell, Int>()
        for (track in sampled) {
            var last: Cell? = null
            for (p in track) {
                val c = cell(p, 22.0, ref)
                if (c != last) count[c] = (count[c] ?: 0) + 1
                last = c
            }
        }
        // a stretch counts as busy if the cells around a point have been visited often
        fun level(p: RoutePt): Int {
            val c = cell(p, 22.0, ref)
            var n = 0
            for (dx in -1L..1L) for (dy in -1L..1L) n = maxOf(n, count[Cell(c.x + dx, c.y + dy)] ?: 0)
            return when { n >= 8 -> 4; n >= 4 -> 3; n >= 2 -> 2; else -> 1 }
        }
        val out = ArrayList<Heat>()
        for (track in sampled) {
            var piece = ArrayList<RoutePt>()
            var lv = 0
            for (p in track) {
                val l = level(p)
                if (l != lv && piece.isNotEmpty()) {
                    piece.add(p); out += Heat(lv, piece); piece = ArrayList()
                }
                lv = l; piece.add(p)
            }
            if (piece.size > 1) out += Heat(lv, piece)
        }
        return out.sortedBy { it.level }
    }
}
