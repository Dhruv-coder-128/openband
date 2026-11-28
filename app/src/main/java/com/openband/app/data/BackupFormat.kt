package com.openband.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One saved setting. [type] is b(oolean), i(nt), l(ong), f(loat), s(tring) or S (set of strings). */
data class BackupSetting(val file: String, val key: String, val type: Char, val value: String)

class Backup(
    val createdAt: Long,
    val appVersion: String,
    val samples: List<Sample>,
    val workouts: List<Workout>,
    val settings: List<BackupSetting>,
    val photo: ByteArray?,
)

/** Thrown with a message that can be shown to the user. */
class BackupException(message: String) : Exception(message)

/**
 * The backup file: a zip of tab-separated text tables plus the profile photo. A file is read completely and checked
 * before anything is returned, so a damaged one never gets half restored.
 */
object BackupFormat {
    const val VERSION = 1
    private const val APP = "openband"
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val SET_SEPARATOR = "\u001F"

    fun write(out: OutputStream, b: Backup) {
        ZipOutputStream(out).use { zip ->
            fun table(name: String, rows: Sequence<List<String?>>) {
                zip.putNextEntry(ZipEntry(name))
                val w = zip.writer(Charsets.UTF_8)
                for (r in rows) { w.write(row(r)); w.write("\n") }
                w.flush()
                zip.closeEntry()
            }
            table(
                "meta.tsv",
                sequenceOf(
                    listOf("app", APP), listOf("format", "$VERSION"), listOf("created", "${b.createdAt}"), listOf("version", b.appVersion),
                    listOf("samples", "${b.samples.size}"), listOf("workouts", "${b.workouts.size}"), listOf("settings", "${b.settings.size}"),
                ),
            )
            table("samples.tsv", b.samples.asSequence().map { listOf("${it.ts}", "${it.endTs}", it.type, "${it.value}", it.source) })
            table(
                "workouts.tsv",
                b.workouts.asSequence().map {
                    listOf(
                        it.sport, "${it.start}", "${it.end}", "${it.pausedMs}", "${it.distanceM}", "${it.steps}", "${it.kcal}",
                        "${it.avgHr}", "${it.maxHr}", "${it.minHr}", encodeHr(it.hr), encodeRoute(it.route), if (it.gps) "1" else "0",
                        it.source, it.extId, it.extra,
                    )
                },
            )
            table("settings.tsv", b.settings.asSequence().map { listOf(it.file, it.key, it.type.toString(), it.value) })
            b.photo?.let { zip.putNextEntry(ZipEntry("profile.jpg")); zip.write(it); zip.closeEntry() }
        }
    }

    fun read(input: InputStream): Backup {
        val files = HashMap<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw BackupException("This file is too large to be an OpenBand backup.")
                        bytes.write(buffer, 0, n)
                    }
                    // names are only looked up, never used as paths
                    files[entry.name] = bytes.toByteArray()
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("This file is damaged or is not an OpenBand backup.")
        }

        fun table(name: String): List<List<String?>> {
            val bytes = files[name] ?: throw BackupException("This file is not a complete OpenBand backup.")
            return String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }.map { parse(it) }
        }

        val meta = table("meta.tsv").associate { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        if (meta["app"] != APP) throw BackupException("This file is not an OpenBand backup.")
        val format = meta["format"]?.toIntOrNull() ?: throw BackupException("This file is not an OpenBand backup.")
        if (format > VERSION) throw BackupException("This backup was made by a newer OpenBand. Update the app first.")

        try {
            val samples = table("samples.tsv").map { Sample(it[0]!!.toLong(), it[2]!!, it[3]!!.toDouble(), it[4]!!, it[1]!!.toLong()) }
            val workouts = table("workouts.tsv").map {
                Workout(
                    0, it[0]!!, it[1]!!.toLong(), it[2]!!.toLong(), it[3]!!.toLong(), it[4]!!.toDouble(), it[5]!!.toInt(), it[6]!!.toDouble(),
                    it[7]!!.toInt(), it[8]!!.toInt(), it[9]!!.toInt(), decodeHr(it[10]!!), decodeRoute(it[11]!!), it[12] == "1",
                    it[13]!!, it[14], it[15]!!,
                )
            }
            val settings = table("settings.tsv").map { BackupSetting(it[0]!!, it[1]!!, it[2]!!.single(), it[3]!!) }
            if (samples.size.toString() != meta["samples"] || workouts.size.toString() != meta["workouts"] || settings.size.toString() != meta["settings"])
                throw BackupException("This backup is incomplete.")
            return Backup(meta["created"]!!.toLong(), meta["version"] ?: "", samples, workouts, settings, files["profile.jpg"])
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("This backup is damaged and cannot be read.")
        }
    }

    fun encodeSet(values: Set<String>) = values.joinToString(SET_SEPARATOR)
    fun decodeSet(value: String): Set<String> = if (value.isEmpty()) emptySet() else value.split(SET_SEPARATOR).toSet()

    /** One line: fields split by tabs, with tabs and line breaks inside a field escaped; \N means "no value". */
    private fun row(fields: List<String?>) = fields.joinToString("\t") { f ->
        f?.replace("\\", "\\\\")?.replace("\t", "\\t")?.replace("\n", "\\n")?.replace("\r", "\\r") ?: "\\N"
    }

    private fun parse(line: String): List<String?> = line.split('\t').map { f ->
        if (f == "\\N") return@map null
        if ('\\' !in f) return@map f
        buildString {
            var i = 0
            while (i < f.length) {
                val c = f[i]
                if (c == '\\' && i + 1 < f.length) {
                    append(when (f[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> f[i + 1] })
                    i += 2
                } else {
                    append(c); i++
                }
            }
        }
    }
}
