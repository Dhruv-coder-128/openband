package com.openband.app

import com.openband.app.data.Backup
import com.openband.app.data.BackupException
import com.openband.app.data.BackupFormat
import com.openband.app.data.BackupSetting
import com.openband.app.data.RoutePt
import com.openband.app.data.Sample
import com.openband.app.data.Workout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {

    private val samples = listOf(Sample(1000, "hr", 72.0, "band"), Sample(2000, "steps", 350.5, "health", 2900))
    private val workouts = listOf(
        Workout(7, "walk", 5000, 9000, 100, 2510.25, 3100, 140.5, 120, 150, 90, listOf(0 to 90, 5 to 101), listOf(RoutePt(23.012345, 72.512345, 0, 55.5), RoutePt(23.012400, 72.512400, 3, 56.0)), true),
        Workout(8, "compound", 10000, 20000, 0, 0.0, 0, 200.0, 0, 0, 0, emptyList(), emptyList(), false, "Pebble", "st:10000", "{\"note\":\"a\\tb\"}"),
    )
    private val settings = listOf(
        BackupSetting("race_ui", "stepGoal", 'i', "10000"),
        BackupSetting("ai", "chat", 's', "line one\nline two\twith tab \\ slash \r ગુજરાતી \\N"),
        BackupSetting("race_ui", "driver", 's', ""),
    )

    private fun bytes(b: Backup) = ByteArrayOutputStream().also { BackupFormat.write(it, b) }.toByteArray()
    private fun read(data: ByteArray) = BackupFormat.read(ByteArrayInputStream(data))
    private fun failure(data: ByteArray): String = try { read(data); "" } catch (e: BackupException) { e.message!! }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> files.forEach { (n, text) -> z.putNextEntry(ZipEntry(n)); z.write(text.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun everythingComesBackExactly() {
        val photo = byteArrayOf(1, 2, 3, 0, -1)
        val b = read(bytes(Backup(123456, "6.10.0", samples, workouts, settings, photo)))
        assertEquals(123456L, b.createdAt)
        assertEquals("6.10.0", b.appVersion)
        assertEquals(samples, b.samples)
        // row ids belong to one database and are not carried over
        assertEquals(workouts.map { it.copy(id = 0) }, b.workouts)
        assertEquals(settings, b.settings)
        assertArrayEquals(photo, b.photo)
    }

    @Test fun anEmptyBackupWorks() {
        val b = read(bytes(Backup(1, "x", emptyList(), emptyList(), emptyList(), null)))
        assertTrue(b.samples.isEmpty() && b.workouts.isEmpty() && b.settings.isEmpty())
        assertNull(b.photo)
    }

    @Test fun stringSetsRoundTrip() {
        assertEquals(setOf("a", "b c"), BackupFormat.decodeSet(BackupFormat.encodeSet(setOf("a", "b c"))))
        assertEquals(emptySet<String>(), BackupFormat.decodeSet(BackupFormat.encodeSet(emptySet())))
    }

    @Test fun refusesOtherFiles() {
        assertTrue(failure("just some text".toByteArray()).isNotEmpty())
        assertTrue(failure(zip("photo.txt" to "hello")).isNotEmpty())
        assertTrue(failure(zip("meta.tsv" to "app\tsomething\nformat\t1\n")).contains("not an OpenBand"))
    }

    @Test fun refusesANewerFormat() {
        assertTrue(failure(zip("meta.tsv" to "app\topenband\nformat\t99\n")).contains("newer"))
    }

    @Test fun refusesACutOffFile() {
        val whole = bytes(Backup(1, "x", samples, workouts, settings, null))
        assertTrue(failure(whole.copyOf(whole.size / 2)).isNotEmpty())
    }

    @Test fun refusesWrongCounts() {
        val data = zip(
            "meta.tsv" to "app\topenband\nformat\t1\ncreated\t1\nsamples\t2\nworkouts\t0\nsettings\t0\n",
            "samples.tsv" to "1\t1\thr\t70.0\tband\n", "workouts.tsv" to "", "settings.tsv" to "",
        )
        assertTrue(failure(data).contains("incomplete"))
    }
}
