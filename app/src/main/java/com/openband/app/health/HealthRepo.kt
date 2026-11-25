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
import com.openband.app.data.Db
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
    suspend fun import(fullDays: Int? = null): String {
        if (!available()) return "Health Connect not available"
        val granted = granted()
        if (granted.isEmpty()) return "No Health Connect permission granted"
        val end = Instant.now()
        val start = when {
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

        lastImport = end.toEpochMilli()
        val report = buildString {
            append(if (counts.isEmpty()) "No new data" else counts.entries.joinToString(", ") { "${it.key}: ${it.value}" })
            if (errors.isNotEmpty()) append("\nErrors: ").append(errors.joinToString("; "))
        }
        lastReport = report
        return report
    }

    private fun minutes(a: Instant, b: Instant) = Duration.between(a, b).seconds / 60.0

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
