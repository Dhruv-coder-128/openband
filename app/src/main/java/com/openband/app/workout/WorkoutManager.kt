package com.openband.app.workout

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openband.app.ble.BandManager
import com.openband.app.ble.LiveKind
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.data.Stats
import com.openband.app.data.RoutePt
import com.openband.app.data.Workout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class Gps { NONE, NEEDED }

/** One sport. [met] = metabolic equivalent for the calorie estimate, [gps] = outdoor distance needs the phone's GPS. */
data class SportDef(
    val id: String, val name: String, val emoji: String, val category: String, val gps: Gps, val met: Double,
    val steps: Boolean = false, val note: String = "", val maxSpeed: Double = 12.0, val paceMode: Boolean = false,
)

object Sports {
    const val RUN_WALK = "Run & walk"
    const val CYCLE = "Cycle"
    const val WATER = "Water"
    const val GYM = "Gym & studio"
    const val BALL = "Ball & racket"
    const val OTHER = "More"

    val all = listOf(
        SportDef("run", "Run", "🏃", RUN_WALK, Gps.NEEDED, 9.8, steps = true, paceMode = true),
        SportDef("trail", "Trail run", "⛰️", RUN_WALK, Gps.NEEDED, 9.0, steps = true, paceMode = true),
        SportDef("treadmill", "Treadmill", "🏃", RUN_WALK, Gps.NONE, 9.0, steps = true, paceMode = true, note = "Distance comes from your band's step count."),
        SportDef("walk", "Walk", "🚶", RUN_WALK, Gps.NEEDED, 3.5, steps = true, paceMode = true, maxSpeed = 4.0),
        SportDef("hike", "Hike", "🥾", RUN_WALK, Gps.NEEDED, 6.0, steps = true, paceMode = true, maxSpeed = 5.0),
        SportDef("bike", "Ride", "🚴", CYCLE, Gps.NEEDED, 7.5, maxSpeed = 25.0),
        SportDef("mtb", "Mountain bike", "🚵", CYCLE, Gps.NEEDED, 8.5, maxSpeed = 22.0),
        SportDef("spin", "Indoor cycling", "🚲", CYCLE, Gps.NONE, 6.8),
        SportDef("swim", "Swim", "🏊", WATER, Gps.NONE, 8.0, note = "Wrist heart-rate can read low or drop out in water. Distance isn't tracked."),
        SportDef("row", "Rowing", "🚣", WATER, Gps.NONE, 7.0),
        SportDef("strength", "Strength", "🏋️", GYM, Gps.NONE, 5.0),
        SportDef("hiit", "HIIT", "⚡", GYM, Gps.NONE, 8.5),
        SportDef("crossfit", "Crossfit", "🔥", GYM, Gps.NONE, 8.0),
        SportDef("yoga", "Yoga", "🧘", GYM, Gps.NONE, 2.5),
        SportDef("pilates", "Pilates", "🤸", GYM, Gps.NONE, 3.0),
        SportDef("stretch", "Stretching", "🙆", GYM, Gps.NONE, 2.3),
        SportDef("stairs", "Stair climbing", "🪜", GYM, Gps.NONE, 8.8, steps = true),
        SportDef("elliptical", "Elliptical", "🌀", GYM, Gps.NONE, 5.0),
        SportDef("aerobics", "Aerobics", "🤸", GYM, Gps.NONE, 6.5, steps = true),
        SportDef("dance", "Dance", "💃", GYM, Gps.NONE, 5.5, steps = true),
        SportDef("skip", "Skipping rope", "🪢", GYM, Gps.NONE, 11.0, steps = true),
        SportDef("boxing", "Boxing", "🥊", GYM, Gps.NONE, 7.8),
        SportDef("martial", "Martial arts", "🥋", GYM, Gps.NONE, 10.0),
        SportDef("football", "Football", "⚽", BALL, Gps.NONE, 7.0, steps = true, note = "Turn GPS on in settings if you want distance - it is optional."),
        SportDef("cricket", "Cricket", "🏏", BALL, Gps.NONE, 4.8, steps = true),
        SportDef("badminton", "Badminton", "🏸", BALL, Gps.NONE, 5.5, steps = true),
        SportDef("tennis", "Tennis", "🎾", BALL, Gps.NONE, 7.3, steps = true),
        SportDef("tabletennis", "Table tennis", "🏓", BALL, Gps.NONE, 4.0),
        SportDef("basketball", "Basketball", "🏀", BALL, Gps.NONE, 6.5, steps = true),
        SportDef("volleyball", "Volleyball", "🏐", BALL, Gps.NONE, 4.0),
        SportDef("kabaddi", "Kabaddi", "🤼", BALL, Gps.NONE, 7.0, steps = true),
        SportDef("golf", "Golf", "⛳", BALL, Gps.NONE, 4.8, steps = true),
        SportDef("skate", "Skating", "⛸️", OTHER, Gps.NEEDED, 7.0, maxSpeed = 15.0),
        SportDef("climb", "Climbing", "🧗", OTHER, Gps.NONE, 8.0),
        SportDef("meditate", "Meditation", "🕉️", OTHER, Gps.NONE, 1.3),
        SportDef("other", "Other workout", "💪", OTHER, Gps.NONE, 5.0),
    )

    val categories = listOf(RUN_WALK, CYCLE, WATER, GYM, BALL, OTHER)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.last()
}

data class GpsState(
    val hasPermission: Boolean = false,      // precise (fine) location granted
    val coarseOnly: Boolean = false,         // user picked "approximate" - not enough for tracking
    val enabled: Boolean = false,            // GPS switched on in the phone
    val accuracy: Float? = null,
    val satellites: Int = 0,                 // satellites in view
    val usedSats: Int = 0,                   // satellites used for the current fix
    val tracking: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    /** Good enough to start: accuracy better than 40 m. */
    val fix get() = accuracy != null && accuracy <= 40f
}

data class ActiveState(
    val sport: SportDef,
    val startMs: Long,
    val paused: Boolean,
    val elapsedMs: Long,
    val hr: Int?,
    val avgHr: Int?,
    val maxHr: Int?,
    val distanceM: Double,
    val steps: Int,
    val kcal: Double,
    val gpsUsed: Boolean,
    val paceSecPerKm: Double?,
    val speedKmh: Double?,
    val zones: List<Int>,
    val hrSeries: List<Pair<Int, Int>>,
    val route: List<RoutePt>,
    val elevGain: Double = 0.0,
)

/**
 * Runs a workout: timer, live heart rate from the band, GPS from the phone, steps/calories from the band.
 * It only talks to the band while OpenBand is on screen (same rule as everything else), and keeps the timer
 * running when you leave - the band's own heart-rate log fills the gaps afterwards.
 */
@SuppressLint("MissingPermission")
object WorkoutManager {
    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db get() = Db.get(ctx)

    val gps = MutableStateFlow(GpsState())
    val active = MutableStateFlow<ActiveState?>(null)
    val lastFinished = MutableStateFlow<Workout?>(null)

    private var lm: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var loop: Job? = null
    private var prepJob: Job? = null

    // running data
    private var sport: SportDef? = null
    private var startMs = 0L
    private var pauseStart = 0L
    private var pausedTotal = 0L
    private val hr = ArrayList<Pair<Int, Int>>()
    private val route = ArrayList<RoutePt>()
    private var distance = 0.0
    private var lastLoc: Location? = null
    private var stepsAtStart = 0.0
    private var kcalAtStart = 0.0
    private var distAtStart = 0.0
    private var useGps = false
    private val recent = ArrayDeque<Pair<Long, Double>>() // (time, cumulative distance) for current pace

    fun init(c: Context) {
        ctx = c.applicationContext
        lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        refreshGpsPermission()
    }

    fun hasLocationPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun refreshGpsPermission() {
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = hasLocationPermission()
        gps.update { it.copy(hasPermission = fine, coarseOnly = coarse && !fine, enabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) }
    }

    // ------------------------------------------------------------------ GPS

    private var gnss: android.location.GnssStatus.Callback? = null

    fun startGps() {
        refreshGpsPermission()
        if (gpsListener != null || !hasLocationPermission()) return
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                gps.update { it.copy(accuracy = loc.accuracy, tracking = true, enabled = true, lat = loc.latitude, lon = loc.longitude) }
                onLocation(loc)
            }
            override fun onProviderEnabled(provider: String) { refreshGpsPermission() }
            override fun onProviderDisabled(provider: String) { refreshGpsPermission(); gps.update { it.copy(accuracy = null) } }
            @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        gpsListener = l
        runCatching { lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper()) }
        // show something useful immediately: the last position the phone knew
        runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { k -> gps.update { it.copy(lat = k.latitude, lon = k.longitude) } } }
        // satellites in view / used - helps to see why there is no fix yet
        val cb = object : android.location.GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
                gps.update { it.copy(satellites = status.satelliteCount, usedSats = used) }
            }
        }
        gnss = cb
        runCatching { lm?.registerGnssStatusCallback(cb, android.os.Handler(Looper.getMainLooper())) }
        gps.update { it.copy(tracking = true) }
    }

    fun stopGps() {
        gpsListener?.let { l -> runCatching { lm?.removeUpdates(l) } }
        gpsListener = null
        gnss?.let { c -> runCatching { lm?.unregisterGnssStatusCallback(c) } }
        gnss = null
        gps.update { it.copy(tracking = false, accuracy = null, satellites = 0, usedSats = 0) }
    }

    private fun onLocation(loc: Location) {
        val sp = sport ?: return
        if (active.value == null || pauseStart != 0L || !useGps) return
        if (loc.accuracy > 35f) return
        val prev = lastLoc
        val tSec = ((System.currentTimeMillis() - startMs - pausedTotal) / 1000).toInt()
        if (prev == null) { lastLoc = loc; route += RoutePt(loc.latitude, loc.longitude, tSec, loc.altitude); return }
        val d = prev.distanceTo(loc).toDouble()
        val dt = (loc.time - prev.time) / 1000.0
        if (d < 2.5) return                                   // standing still / GPS noise
        if (dt > 0 && d / dt > sp.maxSpeed) return            // jump that no human could do
        distance += d
        lastLoc = loc
        if (route.size < 20000) route += RoutePt(loc.latitude, loc.longitude, tSec, loc.altitude)
        recent.addLast(System.currentTimeMillis() to distance)
        while (recent.size > 2 && System.currentTimeMillis() - recent.first().first > 30_000) recent.removeFirst()
    }

    // ------------------------------------------------------------------ prepare (sensor check)

    /** Starts what the sensor check needs: live heart rate from the band and (for outdoor sports) GPS. */
    fun startPrep(def: SportDef) {
        prepJob?.cancel()
        refreshGpsPermission()
        if (def.gps == Gps.NEEDED) startGps()
        lastKick = 0L
        prepJob = scope.launch {
            while (true) { keepHeartRateAlive(); delay(1000) }
        }
    }

    private var lastKick = 0L

    /**
     * Keeps the band in its continuous heart-rate mode while a workout (or the sensor check) runs. It is started once and
     * left alone; it is only started again if no reading has arrived for 15 s. Restarting more often makes the band buzz.
     */
    private fun keepHeartRateAlive() {
        val st = BandManager.state.value
        if (!st.ready || !(BandManager.foreground || BandManager.workoutActive)) return
        val now = System.currentTimeMillis()
        val since = now - lastKick
        val silent = now - maxOf(BandManager.liveHrAt, lastKick)
        if ((st.liveKind != LiveKind.REALTIME_HR && since > 3_000) || (silent > 15_000 && since > 15_000)) {
            lastKick = now
            BandManager.startLive(LiveKind.REALTIME_HR, soft = st.liveKind == LiveKind.REALTIME_HR)
        }
    }

    fun stopPrep() {
        prepJob?.cancel(); prepJob = null
        if (active.value == null) {
            stopGps()
            BandManager.stopLive()
        }
    }

    // ------------------------------------------------------------------ workout

    private val totalsCache = HashMap<String, Pair<Long, Double>>()

    /** Today's total of a cumulative metric, cached for 4 s so the 1 Hz loop doesn't hammer the database. */
    private fun todayTotal(type: String, fresh: Boolean = false): Double {
        val now = System.currentTimeMillis()
        totalsCache[type]?.let { (t, v) -> if (!fresh && now - t < 4_000) return v }
        val (a, b) = Stats.todayRange()
        val v = Stats.total(db, type, a, b) ?: 0.0
        totalsCache[type] = now to v
        return v
    }

    fun start(def: SportDef, withGps: Boolean) {
        prepJob?.cancel(); prepJob = null
        sport = def
        startMs = System.currentTimeMillis()
        pauseStart = 0L; pausedTotal = 0L
        hr.clear(); route.clear(); recent.clear()
        distance = 0.0; lastLoc = null
        useGps = withGps && def.gps == Gps.NEEDED && hasLocationPermission() && lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        if (useGps) startGps() else if (!hasLocationPermission() || def.gps == Gps.NONE) stopGps()
        BandManager.setWorkoutActive(true)
        WorkoutService.start(ctx)
        scope.launch(Dispatchers.IO) {
            BandManager.pollNow(false)
            stepsAtStart = todayTotal(M.STEPS, true); kcalAtStart = todayTotal(M.CALORIES, true); distAtStart = todayTotal(M.DISTANCE, true)
        }
        publish()
        loop?.cancel()
        loop = scope.launch {
            lastKick = 0L
            var lastPoll = System.currentTimeMillis()
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                if (pauseStart == 0L) {
                    val st = BandManager.state.value
                    val age = now - BandManager.liveHrAt
                    val bpm = st.liveHr
                    if (bpm != null && age < 10_000 && bpm in 30..230) hr += ((now - startMs - pausedTotal) / 1000).toInt() to bpm
                    keepHeartRateAlive()
                    // steps / calories every minute (live HR is already running, so this is a short extra read)
                    if (now - lastPoll > 60_000) {
                        lastPoll = now
                        launch(Dispatchers.IO) { BandManager.pollNow(false) }
                    }
                }
                publish()
            }
        }
    }

    fun pause() { if (active.value != null && pauseStart == 0L) { pauseStart = System.currentTimeMillis(); publish() } }
    fun resume() { if (pauseStart != 0L) { pausedTotal += System.currentTimeMillis() - pauseStart; pauseStart = 0L; publish() } }

    private fun elapsed(now: Long = System.currentTimeMillis()): Long {
        val p = if (pauseStart != 0L) now - pauseStart else 0L
        return (now - startMs - pausedTotal - p).coerceAtLeast(0)
    }

    private fun weightKg(): Double = db.latest(M.WEIGHT)?.value ?: com.openband.app.ui.RaceSettings.prefs.value.weightKg.toDouble()

    private fun metKcal(def: SportDef, ms: Long) = def.met * weightKg() * ms / 3_600_000.0

    private fun stepsNow(fresh: Boolean = false) = (todayTotal(M.STEPS, fresh) - stepsAtStart).coerceAtLeast(0.0).toInt()

    private fun publish() {
        val def = sport ?: return
        val ms = elapsed()
        val bpms = hr.map { it.second }
        val max = ctxMaxHr()
        val zones = IntArray(5)
        for (i in hr.indices) {
            val next = if (i + 1 < hr.size) hr[i + 1].first else hr[i].first + 1
            val secs = (next - hr[i].first).coerceIn(0, 10)
            val pct = hr[i].second.toDouble() / max
            val z = when { pct >= 0.9 -> 4; pct >= 0.8 -> 3; pct >= 0.7 -> 2; pct >= 0.6 -> 1; pct >= 0.5 -> 0; else -> -1 }
            if (z >= 0) zones[z] += secs
        }
        var pace: Double? = null
        var speed: Double? = null
        if (useGps && recent.size >= 2) {
            val dt = (recent.last().first - recent.first().first) / 1000.0
            val dd = recent.last().second - recent.first().second
            if (dt > 5 && dd > 5) { speed = dd / dt * 3.6; pace = 1000.0 / (dd / dt) }
        }
        val dist = if (useGps) distance else (todayTotal(M.DISTANCE) - distAtStart).coerceAtLeast(0.0)
        val steps = stepsNow()
        active.value = ActiveState(
            def, startMs, pauseStart != 0L, ms, if (BandManager.liveHrAt > 0 && System.currentTimeMillis() - BandManager.liveHrAt < 20_000) BandManager.state.value.liveHr else null,
            bpms.takeIf { it.isNotEmpty() }?.average()?.toInt(), bpms.maxOrNull(), dist, steps,
            estimateKcal(def, ms), useGps, pace, speed, zones.toList(), hr.toList(), route.toList(), elevationGain(route),
        )
    }

    private fun estimateKcal(def: SportDef, ms: Long): Double {
        val band = (todayTotal(M.CALORIES) - kcalAtStart).coerceAtLeast(0.0)
        return if (band >= 5.0) band else metKcal(def, ms)
    }

    private fun ctxMaxHr() = com.openband.app.ui.RaceSettings.prefs.value.maxHr

    /** Ends the workout, saves it and returns it. */
    suspend fun finish(save: Boolean = true): Workout? {
        val def = sport ?: return null
        loop?.cancel(); loop = null
        if (pauseStart != 0L) resume()
        val end = System.currentTimeMillis()
        BandManager.stopLive()
        withContext(Dispatchers.IO) { BandManager.pollNow(false) }
        val ms = elapsed(end)
        // fall back to the band's own heart-rate log if the live stream had gaps
        val logHr = withContext(Dispatchers.IO) { db.series(M.HR, startMs, end).map { it.value.toInt() }.filter { it in 30..230 } }
        val live = hr.map { it.second }
        val all = if (live.size >= 10) live else live + logHr
        val steps = withContext(Dispatchers.IO) { stepsNow(true) }
        val dist = if (useGps) distance else withContext(Dispatchers.IO) { (todayTotal(M.DISTANCE, true) - distAtStart).coerceAtLeast(0.0) }
        val kcal = withContext(Dispatchers.IO) { estimateKcal(def, ms) }
        val step = (hr.size / 1200).coerceAtLeast(1)
        val w = Workout(
            0, def.id, startMs, end, pausedTotal, dist, steps, kcal,
            all.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0, all.maxOrNull() ?: 0, all.minOrNull() ?: 0,
            hr.filterIndexed { i, _ -> i % step == 0 }, route.filterIndexed { i, _ -> i % ((route.size / 1500).coerceAtLeast(1)) == 0 }, useGps,
        )
        stopGps()
        sport = null
        active.value = null
        WorkoutService.stop(ctx)
        BandManager.setWorkoutActive(false)
        if (!save) return null
        val id = withContext(Dispatchers.IO) { db.insertWorkout(w) }
        return w.copy(id = id).also { lastFinished.value = it }
    }

    fun discard() {
        loop?.cancel(); loop = null
        BandManager.stopLive()
        stopGps()
        sport = null
        active.value = null
        WorkoutService.stop(ctx)
        BandManager.setWorkoutActive(false)
    }

    /** Current number of consecutive days (ending today or yesterday) with a workout or the step goal reached. */
    fun streak(stepGoal: Int): Int {
        val d = db
        val workoutDays = d.workouts(400).map { java.time.Instant.ofEpochMilli(it.start).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.toSet()
        var day = LocalDate.now()
        fun ok(x: LocalDate) = x in workoutDays || (Stats.dayValue(d, M.STEPS, x) ?: 0.0) >= stepGoal
        if (!ok(day)) day = day.minusDays(1)          // today is still in progress
        var n = 0
        while (n < 400 && ok(day)) { n++; day = day.minusDays(1) }
        return n
    }

    /** Total climb in metres (altitude smoothed over 5 points so GPS jitter is not counted as hills). */
    fun elevationGain(r: List<RoutePt>): Double {
        if (r.size < 6) return 0.0
        val sm = r.indices.map { i -> (maxOf(0, i - 2)..minOf(r.lastIndex, i + 2)).map { r[it].alt }.average() }
        var g = 0.0
        for (i in 1 until sm.size) { val d = sm[i] - sm[i - 1]; if (d > 0.3) g += d }
        return g
    }

    data class Split(val index: Int, val meters: Double, val seconds: Int, val avgHr: Int, val elevDelta: Double)

    /** Per-kilometre (or per-mile) splits, with the heart rate and climb of each. Needs the GPS route. */
    fun splits(w: Workout, unitMeters: Double): List<Split> {
        val r = w.route
        if (r.size < 2) return emptyList()
        val out = ArrayList<Split>()
        var cum = 0.0
        var splitStartT = r.first().t.toDouble()
        var splitStartAlt = r.first().alt
        var nextAt = unitMeters
        val res = FloatArray(1)
        for (i in 1 until r.size) {
            Location.distanceBetween(r[i - 1].lat, r[i - 1].lon, r[i].lat, r[i].lon, res)
            val seg = res[0].toDouble()
            if (seg <= 0) continue
            val tPrev = r[i - 1].t.toDouble()
            while (cum + seg >= nextAt) {
                val frac = (nextAt - cum) / seg
                val t = tPrev + (r[i].t - tPrev) * frac
                val hrs = w.hr.filter { it.first >= splitStartT && it.first <= t }.map { it.second }
                out += Split(out.size + 1, unitMeters, (t - splitStartT).toInt(), hrs.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0, r[i].alt - splitStartAlt)
                splitStartT = t; splitStartAlt = r[i].alt; nextAt += unitMeters
            }
            cum += seg
        }
        val rest = cum - (nextAt - unitMeters)
        if (rest > unitMeters * 0.1) {
            val tEnd = r.last().t.toDouble()
            val hrs = w.hr.filter { it.first >= splitStartT }.map { it.second }
            out += Split(out.size + 1, rest, (tEnd - splitStartT).toInt(), hrs.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0, r.last().alt - splitStartAlt)
        }
        return out
    }

    /** A made-up 5 km run in Ahmedabad so the map / splits / charts can be seen without running. */
    fun insertDemo(): Long {
        val rnd = java.util.Random(7)
        val pts = ArrayList<RoutePt>()
        var lat = 23.0258; var lon = 72.5873
        var t = 0
        var heading = 0.0
        var alt = 52.0
        val total = 5200.0
        var d = 0.0
        while (d < total) {
            heading += (rnd.nextGaussian() * 0.08) + if ((d / 700).toInt() % 2 == 0) 0.015 else -0.012
            val step = 6.0 + rnd.nextDouble() * 1.2
            lat += step * Math.cos(heading) / 111_320.0
            lon += step * Math.sin(heading) / (111_320.0 * Math.cos(Math.toRadians(lat)))
            alt += Math.sin(d / 400.0) * 0.4 + rnd.nextGaussian() * 0.15
            d += step; t += (step / 3.1).toInt().coerceAtLeast(1) + (if (rnd.nextInt(5) == 0) 1 else 0)
            pts += RoutePt(lat, lon, t, alt)
        }
        val hrs = ArrayList<Pair<Int, Int>>()
        var s0 = 0
        while (s0 <= t) { hrs += s0 to (112 + (s0 * 55 / t) + (Math.sin(s0 / 90.0) * 6).toInt() + rnd.nextInt(3)); s0 += 5 }
        val now = System.currentTimeMillis() - 3 * 3600_000L
        val w = Workout(0, "run", now - t * 1000L, now, 0, d, (d / 0.8).toInt(), 330.0, hrs.map { it.second }.average().toInt(), hrs.maxOf { it.second }, hrs.minOf { it.second }, hrs, pts, true)
        return db.insertWorkout(w)
    }
}
