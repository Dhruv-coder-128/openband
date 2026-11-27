package com.openband.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow

/** One measurement. [ts]/[endTs] are epoch millis; for instant metrics endTs == ts. */
data class Sample(
    val ts: Long,
    val type: String,
    val value: Double,
    val source: String,
    val endTs: Long = ts,
)

data class RawPacket(
    val id: Long,
    val ts: Long,
    val dir: String,
    val handle: Int,
    val uuid: String,
    val hex: String,
)

const val SOURCE_BAND = "band"

/** One GPS point: position, seconds since the workout started, altitude in metres. */
data class RoutePt(val lat: Double, val lon: Double, val t: Int = 0, val alt: Double = 0.0)

/** A finished workout. [hr] = (seconds since start, bpm). */
data class Workout(
    val id: Long, val sport: String, val start: Long, val end: Long, val pausedMs: Long, val distanceM: Double, val steps: Int,
    val kcal: Double, val avgHr: Int, val maxHr: Int, val minHr: Int, val hr: List<Pair<Int, Int>>, val route: List<RoutePt>, val gps: Boolean,
    /** Where it came from: "" = recorded in OpenBand, otherwise the app name (e.g. "Pebble"). */
    val source: String = "", val extId: String? = null,
    /** JSON: calorie method, lifting sets, pool lengths, interval rounds. */
    val extra: String = "",
) {
    val imported get() = source.isNotEmpty()
    val movingMs get() = (end - start - pausedMs).coerceAtLeast(0)
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "openband.db", null, 4) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }

        /** Bumped whenever samples change, so UI can refresh. */
        val changes = MutableStateFlow(0L)

        /** Bumped whenever raw packets change. */
        val rawChanges = MutableStateFlow(0L)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples(ts INTEGER NOT NULL, end_ts INTEGER NOT NULL, type TEXT NOT NULL, " +
                "value REAL NOT NULL, source TEXT NOT NULL, PRIMARY KEY(type, ts, source))"
        )
        db.execSQL("CREATE INDEX idx_samples_ts ON samples(type, ts)")
        db.execSQL(
            "CREATE TABLE raw(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, dir TEXT NOT NULL, " +
                "handle INTEGER NOT NULL, uuid TEXT NOT NULL, hex TEXT NOT NULL)"
        )
        createWorkouts(db)
    }

    private fun createWorkouts(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS workouts(id INTEGER PRIMARY KEY AUTOINCREMENT, sport TEXT NOT NULL, start_ts INTEGER NOT NULL, " +
                "end_ts INTEGER NOT NULL, paused_ms INTEGER NOT NULL, distance_m REAL NOT NULL, steps INTEGER NOT NULL, kcal REAL NOT NULL, " +
                "avg_hr INTEGER NOT NULL, max_hr INTEGER NOT NULL, min_hr INTEGER NOT NULL, hr TEXT NOT NULL, route TEXT NOT NULL, gps INTEGER NOT NULL, " +
                "source TEXT NOT NULL DEFAULT '', ext_id TEXT, extra TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_workouts_ext ON workouts(ext_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createWorkouts(db)
        if (oldVersion in 2..2) {
            db.execSQL("ALTER TABLE workouts ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE workouts ADD COLUMN ext_id TEXT")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_workouts_ext ON workouts(ext_id)")
        }
        // versions 2 and 3 already have a workouts table; older ones just created it complete
        if (oldVersion in 2..3) db.execSQL("ALTER TABLE workouts ADD COLUMN extra TEXT NOT NULL DEFAULT ''")
    }

    // ---------------------------------------------------------------- workouts

    fun insertWorkout(w: Workout): Long {
        val cv = ContentValues().apply {
            put("sport", w.sport); put("start_ts", w.start); put("end_ts", w.end); put("paused_ms", w.pausedMs)
            put("distance_m", w.distanceM); put("steps", w.steps); put("kcal", w.kcal)
            put("avg_hr", w.avgHr); put("max_hr", w.maxHr); put("min_hr", w.minHr)
            put("hr", w.hr.joinToString(",") { "${it.first}:${it.second}" })
            put("route", w.route.joinToString(";") { "%.6f,%.6f,%d,%.1f".format(java.util.Locale.US, it.lat, it.lon, it.t, it.alt) })
            put("gps", if (w.gps) 1 else 0)
            put("source", w.source); put("ext_id", w.extId); put("extra", w.extra)
        }
        val id = writableDatabase.insertWithOnConflict("workouts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        changes.value = changes.value + 1
        return id
    }

    fun workouts(limit: Int = 200): List<Workout> =
        readableDatabase.rawQuery(
            "SELECT id, sport, start_ts, end_ts, paused_ms, distance_m, steps, kcal, avg_hr, max_hr, min_hr, hr, route, gps, source, ext_id, extra FROM workouts ORDER BY start_ts DESC LIMIT $limit", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Workout(
                        c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getDouble(5), c.getInt(6), c.getDouble(7),
                        c.getInt(8), c.getInt(9), c.getInt(10),
                        c.getString(11).split(',').mapNotNull { t -> t.split(':').takeIf { it.size == 2 }?.let { (a, b) -> (a.toIntOrNull() ?: return@mapNotNull null) to (b.toIntOrNull() ?: return@mapNotNull null) } },
                        c.getString(12).split(';').mapNotNull { t ->
                            val f = t.split(',')
                            if (f.size < 2) return@mapNotNull null
                            RoutePt(f[0].toDoubleOrNull() ?: return@mapNotNull null, f[1].toDoubleOrNull() ?: return@mapNotNull null, f.getOrNull(2)?.toIntOrNull() ?: 0, f.getOrNull(3)?.toDoubleOrNull() ?: 0.0)
                        },
                        c.getInt(13) == 1, c.getString(14) ?: "", c.getString(15), c.getString(16) ?: "",
                    )
                )
            }
        }

    /** Removes imported workouts that did not come from [source] (e.g. leftovers of other apps' auto-detected sessions). */
    fun deleteImportedExcept(source: String) {
        val n = writableDatabase.delete("workouts", "source<>'' AND source<>?", arrayOf(source))
        if (n > 0) changes.value = changes.value + 1
    }

    fun updateWorkoutKcal(id: Long, kcal: Double) {
        writableDatabase.update("workouts", ContentValues().apply { put("kcal", kcal) }, "id=?", arrayOf(id.toString()))
        changes.value = changes.value + 1
    }

    fun deleteWorkout(id: Long) {
        writableDatabase.delete("workouts", "id=?", arrayOf(id.toString()))
        changes.value = changes.value + 1
    }

    // ---------------------------------------------------------------- samples

    fun insertSamples(list: List<Sample>) {
        if (list.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for (s in list) {
                if (s.value.isNaN() || s.value.isInfinite()) continue
                cv.clear()
                cv.put("ts", s.ts)
                cv.put("end_ts", s.endTs)
                cv.put("type", s.type)
                cv.put("value", s.value)
                cv.put("source", s.source)
                db.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changes.value = changes.value + 1
    }

    fun series(type: String, from: Long, to: Long): List<Sample> =
        readableDatabase.rawQuery(
            "SELECT ts, end_ts, value, source FROM samples WHERE type=? AND ts>=? AND ts<? ORDER BY ts",
            arrayOf(type, from.toString(), to.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Sample(c.getLong(0), type, c.getDouble(2), c.getString(3), c.getLong(1)))
            }
        }

    fun latest(type: String): Sample? =
        readableDatabase.rawQuery(
            "SELECT ts, end_ts, value, source FROM samples WHERE type=? ORDER BY ts DESC LIMIT 1",
            arrayOf(type)
        ).use { c -> if (c.moveToFirst()) Sample(c.getLong(0), type, c.getDouble(2), c.getString(3), c.getLong(1)) else null }

    /** type -> number of rows, for the "what data do I have" overview. */
    fun counts(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT type, COUNT(*) FROM samples GROUP BY type", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    fun sourceCounts(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT source, COUNT(*) FROM samples GROUP BY source", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }

    fun forEachSample(block: (Sample) -> Unit) {
        readableDatabase.rawQuery("SELECT ts, end_ts, type, value, source FROM samples ORDER BY ts", null).use { c ->
            while (c.moveToNext()) block(Sample(c.getLong(0), c.getString(2), c.getDouble(3), c.getString(4), c.getLong(1)))
        }
    }

    /** Removes band samples of [type] with exactly [value]; returns their timestamps. */
    fun deleteBandValue(type: String, value: Double): List<Long> {
        val ts = readableDatabase.rawQuery(
            "SELECT ts FROM samples WHERE type=? AND source=? AND value=?", arrayOf(type, SOURCE_BAND, value.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        if (ts.isNotEmpty()) {
            writableDatabase.delete("samples", "type=? AND source=? AND value=?", arrayOf(type, SOURCE_BAND, value.toString()))
            changes.value = changes.value + 1
        }
        return ts
    }

    fun deleteAllSamples() {
        writableDatabase.delete("samples", null, null)
        changes.value = changes.value + 1
    }

    // ---------------------------------------------------------------- raw packets

    fun insertRaw(dir: String, handle: Int, uuid: String, hex: String, ts: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("ts", ts); put("dir", dir); put("handle", handle); put("uuid", uuid); put("hex", hex)
        }
        writableDatabase.insert("raw", null, cv)
        rawChanges.value = rawChanges.value + 1
    }

    fun insertRawBatch(rows: List<RawPacket>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues()
            for (r in rows) {
                cv.clear()
                cv.put("ts", r.ts); cv.put("dir", r.dir); cv.put("handle", r.handle); cv.put("uuid", r.uuid); cv.put("hex", r.hex)
                db.insert("raw", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        rawChanges.value = rawChanges.value + 1
    }

    fun recentRaw(limit: Int, dirPrefix: String? = null): List<RawPacket> {
        val where = if (dirPrefix != null) "WHERE dir LIKE ?" else ""
        val args = if (dirPrefix != null) arrayOf("$dirPrefix%") else null
        return readableDatabase.rawQuery(
            "SELECT id, ts, dir, handle, uuid, hex FROM raw $where ORDER BY id DESC LIMIT $limit", args
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(RawPacket(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getString(4), c.getString(5)))
            }
        }
    }

    /** Writes the official app sent to the band (imported from an HCI snoop log), in original order. */
    fun learnedWrites(): List<Pair<Int, String>> =
        readableDatabase.rawQuery("SELECT handle, hex FROM raw WHERE dir='SNOOP_TX' ORDER BY id", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0) to c.getString(1)) }
        }

    fun clearRaw(dirPrefix: String? = null) {
        if (dirPrefix == null) writableDatabase.delete("raw", null, null)
        else writableDatabase.delete("raw", "dir LIKE ?", arrayOf("$dirPrefix%"))
        rawChanges.value = rawChanges.value + 1
    }

    /** Keeps the live packet log bounded (snoop imports are kept). */
    fun trimRaw(keep: Int = 20000) {
        writableDatabase.execSQL(
            "DELETE FROM raw WHERE dir NOT LIKE 'SNOOP%' AND id NOT IN " +
                "(SELECT id FROM raw WHERE dir NOT LIKE 'SNOOP%' ORDER BY id DESC LIMIT $keep)"
        )
    }
}
