package com.openband.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.openband.app.ui.ProfilePhoto
import com.openband.app.ui.RaceSettings

/** Moves the app's data into a [Backup] and back. */
object BackupStore {
    /**
     * What is saved from each settings file; null = everything. From the AI settings only these are taken: keys are locked
     * to this phone and are secret, and consent is asked again on a new phone. Band pairing, Health Connect state and
     * widget placement belong to one phone and are left out.
     */
    private val SAVED: Map<String, Set<String>?> = mapOf(
        "race_ui" to null,
        "ghost" to null,
        "buzz" to null,
        "ai" to setOf("provider", "url", "language", "profile", "chat"),
    )

    data class Added(val samples: Int, val workouts: Int)

    private fun allowed(file: String, key: String) = file in SAVED && (SAVED[file]?.contains(key) ?: true)

    fun collect(ctx: Context): Backup {
        val db = Db.get(ctx)
        val samples = ArrayList<Sample>()
        db.forEachSample { samples.add(it) }
        val settings = SAVED.keys.flatMap { file ->
            ctx.getSharedPreferences(file, Context.MODE_PRIVATE).all.mapNotNull { (key, v) ->
                if (!allowed(file, key)) return@mapNotNull null
                when (v) {
                    is Boolean -> BackupSetting(file, key, 'b', "$v")
                    is Int -> BackupSetting(file, key, 'i', "$v")
                    is Long -> BackupSetting(file, key, 'l', "$v")
                    is Float -> BackupSetting(file, key, 'f', "$v")
                    is String -> BackupSetting(file, key, 's', v)
                    is Set<*> -> BackupSetting(file, key, 'S', BackupFormat.encodeSet(v.filterIsInstance<String>().toSet()))
                    else -> null
                }
            }
        }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
        return Backup(System.currentTimeMillis(), version, samples, db.workouts(Int.MAX_VALUE), settings, ProfilePhoto.bytes(ctx))
    }

    /** Writes a backup to the file the user chose. */
    fun save(ctx: Context, uri: Uri): Backup {
        val backup = collect(ctx)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("That place cannot be written to.")
        out.use { BackupFormat.write(it, backup) }
        ctx.getSharedPreferences("backup", Context.MODE_PRIVATE).edit().putLong("last", backup.createdAt).apply()
        return backup
    }

    fun lastSaved(ctx: Context): Long = ctx.getSharedPreferences("backup", Context.MODE_PRIVATE).getLong("last", 0)

    fun load(ctx: Context, uri: Uri): Backup {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw BackupException("That file cannot be opened.")
        return input.use { BackupFormat.read(it) }
    }

    /**
     * Adds the readings and workouts this phone does not have yet (nothing here is deleted or overwritten), then replaces
     * the settings and profile photo with the saved ones.
     */
    fun restore(ctx: Context, b: Backup): Added {
        val (samples, workouts) = Db.get(ctx).addMissing(b.samples, b.workouts)
        b.settings.groupBy { it.file }.forEach { (file, list) ->
            if (file !in SAVED) return@forEach
            val edit = ctx.getSharedPreferences(file, Context.MODE_PRIVATE).edit()
            if (SAVED[file] == null) edit.clear()
            list.filter { allowed(file, it.key) }.forEach { put(edit, it) }
            edit.commit()
        }
        b.photo?.let { ProfilePhoto.restore(ctx, it) }
        RaceSettings.init(ctx)
        runCatching { com.openband.app.widget.WidgetUpdater.request(ctx) }
        runCatching { com.openband.app.live.LiveService.sync(ctx) }
        return Added(samples, workouts)
    }

    private fun put(e: SharedPreferences.Editor, s: BackupSetting) {
        when (s.type) {
            'b' -> e.putBoolean(s.key, s.value.toBoolean())
            'i' -> s.value.toIntOrNull()?.let { e.putInt(s.key, it) }
            'l' -> s.value.toLongOrNull()?.let { e.putLong(s.key, it) }
            'f' -> s.value.toFloatOrNull()?.let { e.putFloat(s.key, it) }
            's' -> e.putString(s.key, s.value)
            'S' -> e.putStringSet(s.key, BackupFormat.decodeSet(s.value))
        }
    }
}
