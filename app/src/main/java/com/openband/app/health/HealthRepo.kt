package com.openband.app.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import com.openband.app.data.Calories
import com.openband.app.data.Db
import com.openband.app.data.Workout
import com.openband.app.data.M
import com.openband.app.data.SOURCE_BAND
import com.openband.app.data.Sample
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Health Connect bridge.
 *  - import(): pulls EVERY supported record type written by any app (official band app, Google Fit, ...)
 *    into the local database. Runs automatically from WorkManager.
 *  - exportBand(): pushes data read directly from the band (SpO2, HRV, temperature...) into Health Connect.
 */
class HealthRepo(private val ctx: Context) {

    companion object {
        private const val TAG = "HealthRepo"
        const val PROVIDER = "com.google.android.apps.healthdata"

        val readTypes: List<KClass<out Record>> = listOf(
            StepsRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
            DistanceRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class,
            OxygenSaturationRecord::class, HeartRateVariabilityRmssdRecord::class, BodyTemperatureRecord::class,
            RespiratoryRateRecord::class, SleepSessionRecord::class, BloodPressureRecord::class,
            WeightRecord::class, Vo2MaxRecord::class, ExerciseSessionRecord::class,
        )
        val writeTypes: List<KClass<out Record>> = listOf(
            StepsRecord::class, HeartRateRecord::class, OxygenSaturationRecord::class,
            HeartRateVariabilityRmssdRecord::class, BodyTemperatureRecord::class,
            ActiveCaloriesBurnedRecord::class, DistanceRecord::class, SleepSessionRecord::class,
        )

        val PERMISSIONS: Set<String> = buildSet {
            readTypes.forEach { add(HealthPermission.getReadPermission(it)) }
            writeTypes.forEach { add(HealthPermission.getWritePermission(it)) }
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    private val prefs = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)
    private val db = Db.get(ctx)
    private val zone = ZoneId.systemDefault()

    var lastImport: Long
        get() = prefs.getLong("last_import", 0)
        private set(v) = prefs.edit().putLong("last_import", v).apply()

    var lastReport: String
        get() = prefs.getString("last_report", "") ?: ""
        private set(v) = prefs.edit().putString("last_report", v).apply()

    var writeBack: Boolean
        get() = prefs.getBoolean("write_back", true)
        set(v) = prefs.edit().putBoolean("write_back", v).apply()

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(ctx, PROVIDER)
    fun available(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }

    suspend fun granted(): Set<String> =
        if (available()) runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet()) else emptySet()

    private suspend fun <T : Record> readAll(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = 5000, pageToken = token)
            )
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    private fun src(r: Record): String = r.metadata.dataOrigin.packageName
    private fun Instant.ms() = toEpochMilli()

    /** Imports all data. First run: 30 days back (or 365 if history permission). Later: since last run - 2 days. */
    suspend fun import(fullDays: Int? = null, quick: Boolean = false): String {
        if (!available()) return "Health Connect not available"
        val granted = granted()
        if (granted.isEmpty()) return "No Health Connect permission granted"
        val end = Instant.now()
        val start = when {
            quick -> end.minus(Duration.ofHours(12))
            fullDays != null -> end.minus(Duration.ofDays(fullDays.toLong()))
            lastImport == 0L -> end.minus(
                Duration.ofDays(if (HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted) 365 else 30)
            )
            else -> Instant.ofEpochMilli(lastImport).minus(Duration.ofDays(2))
        }
        val own = ctx.packageName
        val counts = LinkedHashMap<String, Int>()
        val errors = ArrayList<String>()

        suspend fun <T : Record> step(name: String, type: KClass<T>, map: (T) -> List<Sample>) {
            if (HealthPermission.getReadPermission(type) !in granted) return
            try {
                val recs = readAll(type, start, end).filter { src(it) != own }
                val samples = recs.flatMap(map)
                db.insertSamples(samples)
                if (samples.isNotEmpty()) counts[name] = samples.size
            } catch (e: Exception) {
                Log.w(TAG, "read $name failed", e)
                errors += "$name: ${e.javaClass.simpleName}"
            }
        }

        step("Steps", StepsRecord::class) { listOf(Sample(it.startTime.ms(), M.STEPS, it.count.toDouble(), src(it), it.endTime.ms())) }
        step("Active kcal", ActiveCaloriesBurnedRecord::class) {
            listOf(Sample(it.startTime.ms(), M.CALORIES, it.energy.inKilocalories, src(it), it.endTime.ms()))
        }
        step("Total kcal", TotalCaloriesBurnedRecord::class) {
            listOf(Sample(it.startTime.ms(), M.TOTAL_CAL, it.energy.inKilocalories, src(it), it.endTime.ms()))
        }
        step("Distance", DistanceRecord::class) {
            listOf(Sample(it.startTime.ms(), M.DISTANCE, it.distance.inMeters, src(it), it.endTime.ms()))
        }
        step("Heart rate", HeartRateRecord::class) { r ->
            r.samples.map { Sample(it.time.ms(), M.HR, it.beatsPerMinute.toDouble(), src(r)) }
        }
        step("Resting HR", RestingHeartRateRecord::class) { listOf(Sample(it.time.ms(), M.RESTING_HR, it.beatsPerMinute.toDouble(), src(it))) }
        step("SpO2", OxygenSaturationRecord::class) { listOf(Sample(it.time.ms(), M.SPO2, it.percentage.value, src(it))) }
        step("HRV", HeartRateVariabilityRmssdRecord::class) {
            listOf(Sample(it.time.ms(), M.HRV, it.heartRateVariabilityMillis, src(it)))
        }
        step("Temperature", BodyTemperatureRecord::class) { listOf(Sample(it.time.ms(), M.TEMP, it.temperature.inCelsius, src(it))) }
        step("Resp. rate", RespiratoryRateRecord::class) { listOf(Sample(it.time.ms(), M.RESP, it.rate, src(it))) }
        step("Blood pressure", BloodPressureRecord::class) {
            listOf(
                Sample(it.time.ms(), M.BP_SYS, it.systolic.inMillimetersOfMercury, src(it)),
                Sample(it.time.ms(), M.BP_DIA, it.diastolic.inMillimetersOfMercury, src(it)),
            )
        }
        step("Weight", WeightRecord::class) { listOf(Sample(it.time.ms(), M.WEIGHT, it.weight.inKilograms, src(it))) }
        step("VO2 max", Vo2MaxRecord::class) { listOf(Sample(it.time.ms(), M.VO2, it.vo2MillilitersPerMinuteKilogram, src(it))) }
        step("Exercise", ExerciseSessionRecord::class) {
            listOf(Sample(it.startTime.ms(), M.EXERCISE, Duration.between(it.startTime, it.endTime).toMinutes().toDouble(), src(it), it.endTime.ms()))
        }
        step("Sleep", SleepSessionRecord::class) { s ->
            if (s.stages.isEmpty()) {
                listOf(Sample(s.startTime.ms(), M.SLEEP_ASLEEP, minutes(s.startTime, s.endTime), src(s), s.endTime.ms()))
            } else s.stages.map { st ->
                val type = when (st.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> M.SLEEP_DEEP
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> M.SLEEP_LIGHT
                    SleepSessionRecord.STAGE_TYPE_REM -> M.SLEEP_REM
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> M.SLEEP_AWAKE
                    else -> M.SLEEP_ASLEEP
                }
                Sample(st.startTime.ms(), type, minutes(st.startTime, st.endTime), src(s), st.endTime.ms())
            }
        }

        runCatching { importExternalWorkouts(if (quick) 3 else 30) }.onFailure { Log.w(TAG, "workouts", it) }
        runCatching { alignWorkoutCalories() }.onFailure { Log.w(TAG, "workout calories", it) }
        if (quick) return counts.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        lastImport = end.toEpochMilli()
        val report = buildString {
            append(if (counts.isEmpty()) "No new data" else counts.entries.joinToString(", ") { "${it.key}: ${it.value}" })
            if (errors.isNotEmpty()) append("\nErrors: ").append(errors.joinToString("; "))
        }
        lastReport = report
        return report
    }

    private fun minutes(a: Instant, b: Instant) = Duration.between(a, b).seconds / 60.0

    /**
     * In "like Pebble" mode, workouts that were saved with the heart-rate formula (much higher than the Pebble app) get
     * the band's own basis instead: their share of that day's activity calories, by steps.
     */
    private fun alignWorkoutCalories() {
        val p = com.openband.app.ui.RaceSettings.prefs.value
        if (p.calTotal) return
        for (w in db.workouts(100)) {
            // workouts saved with a recorded calorie method are already on the right basis
            if (w.imported || w.steps < 500 || w.extra.isNotEmpty()) continue
            val formula = Calories.fromHr(w.hr, (w.movingMs / 1000).toInt(), p) ?: continue
            if (Math.abs(w.kcal - formula) > formula * 0.05) continue
            val date = Instant.ofEpochMilli(w.start).atZone(zone).toLocalDate()
            val from = com.openband.app.data.Stats.dayStart(date); val to = com.openband.app.data.Stats.dayStart(date.plusDays(1))
            val daySteps = com.openband.app.data.Stats.total(db, M.STEPS, from, to) ?: continue
            val dayKcal = com.openband.app.data.Stats.total(db, M.CALORIES, from, to) ?: continue
            if (daySteps < w.steps) continue
            val share = dayKcal * w.steps / daySteps
            if (share > 20) db.updateWorkoutKcal(w.id, share)
        }
    }

    private class Cand(val ext: String, val sport: String, val start: Long, val end: Long, val src: String, val steps: Int?)

    private fun sportOf(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "run"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "treadmill"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hike"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "bike"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "spin"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "row"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "stretch"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "stairs"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dance"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "boxing"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "martial"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN, ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "football"
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "cricket"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "badminton"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "tabletennis"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "basketball"
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "volleyball"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "golf"
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "climb"
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "skate"
        else -> "other"
    }

    /**
     * Brings workouts done with another app (the Pebble app) into the workout history. Two sources:
     *  1. exercise sessions the app wrote to Health Connect;
     *  2. a step record with a free-running start/end (not on a whole minute) lasting 8 min - 4 h at walking/running pace,
     *     which is how the Pebble app stores a session recorded on the band.
     * Everything is derived from data already in the local database; nothing is sent to the band.
     */
    suspend fun importExternalWorkouts(days: Int = 30): Int {
        if (!available()) return 0
        val g = granted()
        val own = ctx.packageName
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(days.toLong()))
        val cands = ArrayList<Cand>()
        // only the Pebble app's own sessions: Google Fit & co. auto-detect "rides" while you sit in a car, which would be noise
        fun pebble(pkg: String) = pkg.contains("pebble", ignoreCase = true)
        db.deleteImportedExcept("Pebble")
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in g) {
            for (r in readAll(ExerciseSessionRecord::class, start, end).filter { src(it) != own && pebble(src(it)) }) {
                if (Duration.between(r.startTime, r.endTime).toMinutes() < 3) continue
                cands += Cand("hc:${r.metadata.id}", sportOf(r.exerciseType), r.startTime.ms(), r.endTime.ms(), src(r), null)
            }
        }
        if (HealthPermission.getReadPermission(StepsRecord::class) in g) {
            for (r in readAll(StepsRecord::class, start, end)) {
                if (src(r) == own || !pebble(src(r))) continue
                val mins = Duration.between(r.startTime, r.endTime).seconds / 60.0
                if (mins < 8 || mins > 240 || r.count < 300) continue
                val z = r.startTime.atZone(zone); val ze = r.endTime.atZone(zone)
                if (z.second == 0 && ze.second == 0) continue            // regular bins start and end on whole minutes
                val spm = r.count / mins
                if (spm < 50) continue
                if (cands.any { it.start < r.endTime.ms() && it.end > r.startTime.ms() }) continue
                cands += Cand("st:${r.startTime.ms()}", if (spm >= 150) "run" else "walk", r.startTime.ms(), r.endTime.ms(), src(r), r.count.toInt())
            }
        }
        if (cands.isEmpty()) return 0
        val have = db.workouts(500)
        val p = com.openband.app.ui.RaceSettings.prefs.value
        var added = 0
        /** Calories the Pebble app recorded for this time span (records that only partly overlap count pro rata). */
        fun pebbleKcal(from: Long, to: Long): Double? =
            db.series(M.TOTAL_CAL, from - 12 * 3_600_000L, to).filter { it.source.contains("pebble", true) && it.endTs > from }
                .sumOf { r ->
                    val len = (r.endTs - r.ts).coerceAtLeast(1)
                    val over = (minOf(r.endTs, to) - maxOf(r.ts, from)).coerceAtLeast(0)
                    r.value * over / len
                }.takeIf { it > 5 }
        for (c in cands) {
            val old = have.firstOrNull { it.extId == c.ext }
            if (old != null) {
                val k = if (p.calTotal) Calories.fromHr(old.hr, ((old.end - old.start) / 1000).toInt(), p) else pebbleKcal(old.start, old.end)
                if (k != null && Math.abs(k - old.kcal) > 1.5) db.updateWorkoutKcal(old.id, k)
                continue
            }
            if (have.any { !it.imported && Math.abs(it.start - c.start) < 10 * 60_000L }) continue   // recorded in OpenBand itself
            val steps = c.steps ?: db.series(M.STEPS, c.start - 3_600_000L, c.end).filter { it.source == c.src && it.ts >= c.start - 1000 && it.endTs <= c.end + 1000 }.sumOf { it.value }.toInt()
            val hrRows = db.series(M.HR, c.start, c.end).filter { it.value in 30.0..230.0 }
            val step = (hrRows.size / 1200).coerceAtLeast(1)
            val hr = hrRows.filterIndexed { i, _ -> i % step == 0 }.map { ((it.ts - c.start) / 1000).toInt() to it.value.toInt() }
            val bpm = hrRows.map { it.value.toInt() }
            val durSec = ((c.end - c.start) / 1000).toInt()
            val def = com.openband.app.workout.Sports.byId(c.sport)
            val dist = db.series(M.DISTANCE, c.start - 1000, c.end).filter { it.source == c.src }.sumOf { it.value }.takeIf { it > 50 }
                ?: if (def.steps && steps > 0) steps * (p.heightCm / 100.0) * (if (c.sport == "run" || c.sport == "trail") 0.65 else 0.415) else 0.0
            val kcal = (if (p.calTotal) Calories.fromHr(hr, durSec, p) else pebbleKcal(c.start, c.end))
                ?: db.series(M.CALORIES, c.start - 3_600_000L, c.end).filter { it.source == c.src && it.ts >= c.start - 1000 }.sumOf { it.value }.takeIf { it > 5 }
                ?: (def.met * p.weightKg * durSec / 3600.0)
            val pretty = when (c.src) { "com.pebble.ring.app", "com.pebble.plus" -> "Pebble"; else -> c.src.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
            db.insertWorkout(
                Workout(
                    0, c.sport, c.start, c.end, 0, dist, steps, kcal,
                    bpm.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0, bpm.maxOrNull() ?: 0, bpm.minOrNull() ?: 0, hr, emptyList(), false, pretty, c.ext,
                )
            )
            added++
        }
        if (added > 0) Log.i(TAG, "imported $added workout(s) from other apps")
        return added
    }

    /** One-time fix for v3.1: remove the bogus 32.0 °C band readings from the app and Health Connect. */
    suspend fun cleanupBogusTemperature() {
        if (prefs.getBoolean("temp_fix_1", false)) return
        val ts = db.deleteBandValue(M.TEMP, 32.0)
        if (ts.isNotEmpty() && available()) runCatching {
            for (chunk in ts.map { "ob-${M.TEMP}-$it" }.chunked(100)) {
                client.deleteRecords(BodyTemperatureRecord::class, emptyList(), chunk)
            }
        }.onFailure { Log.w(TAG, "temp cleanup", it) }
        prefs.edit().putBoolean("temp_fix_1", true).apply()
    }

    // ------------------------------------------------------------------ band -> Health Connect

    private fun meta(type: String, ts: Long): Metadata = Metadata.autoRecorded(
        device = Device(type = Device.TYPE_FITNESS_BAND, manufacturer = "Pebble", model = "Qore 2"),
        clientRecordId = "ob-$type-$ts",
        clientRecordVersion = 1,
    )

    /** Writes samples that came directly from the band into Health Connect (deduplicated by clientRecordId). */
    suspend fun exportBand(samples: List<Sample>): Int {
        if (!writeBack || !available() || samples.isEmpty()) return 0
        val granted = granted()
        fun can(k: KClass<out Record>) = HealthPermission.getWritePermission(k) in granted
        val out = ArrayList<Record>()
        for (s in samples.filter { it.source == SOURCE_BAND }) {
            val t = Instant.ofEpochMilli(s.ts)
            val e = Instant.ofEpochMilli(if (s.endTs > s.ts) s.endTs else s.ts + 1000)
            val off = zone.rules.getOffset(t)
            val offE = zone.rules.getOffset(e)
            try {
                when (s.type) {
                    M.HR -> if (can(HeartRateRecord::class) && s.value in 25.0..250.0) out += HeartRateRecord(
                        startTime = t, startZoneOffset = off, endTime = e, endZoneOffset = offE,
                        samples = listOf(HeartRateRecord.Sample(t, s.value.toLong())), metadata = meta(s.type, s.ts)
                    )
                    M.SPO2 -> if (can(OxygenSaturationRecord::class) && s.value in 50.0..100.0) out += OxygenSaturationRecord(
                        time = t, zoneOffset = off, percentage = Percentage(s.value), metadata = meta(s.type, s.ts)
                    )
                    M.HRV -> if (can(HeartRateVariabilityRmssdRecord::class) && s.value in 1.0..200.0) out += HeartRateVariabilityRmssdRecord(
                        time = t, zoneOffset = off, heartRateVariabilityMillis = s.value, metadata = meta(s.type, s.ts)
                    )
                    M.TEMP -> if (can(BodyTemperatureRecord::class) && s.value in 30.0..45.0) out += BodyTemperatureRecord(
                        time = t, zoneOffset = off, temperature = Temperature.celsius(s.value), metadata = meta(s.type, s.ts)
                    )
                    M.STEPS -> if (can(StepsRecord::class) && s.value >= 1) out += StepsRecord(
                        startTime = t, startZoneOffset = off, endTime = e, endZoneOffset = offE,
                        count = s.value.toLong(), metadata = meta(s.type, s.ts)
                    )
                    M.CALORIES -> if (can(ActiveCaloriesBurnedRecord::class) && s.value > 0) out += ActiveCaloriesBurnedRecord(
                        startTime = t, startZoneOffset = off, endTime = e, endZoneOffset = offE,
                        energy = Energy.kilocalories(s.value), metadata = meta(s.type, s.ts)
                    )
                    M.DISTANCE -> if (can(DistanceRecord::class) && s.value > 0) out += DistanceRecord(
                        startTime = t, startZoneOffset = off, endTime = e, endZoneOffset = offE,
                        distance = Length.meters(s.value), metadata = meta(s.type, s.ts)
                    )
                }
            } catch (ex: Exception) {
                Log.w(TAG, "skip ${s.type}", ex)
            }
        }
        var written = 0
        for (chunk in out.chunked(500)) {
            try {
                client.insertRecords(chunk); written += chunk.size
            } catch (ex: Exception) {
                Log.w(TAG, "insert failed", ex)
            }
        }
        return written
    }
}
