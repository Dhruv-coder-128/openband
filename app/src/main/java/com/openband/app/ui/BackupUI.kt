package com.openband.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openband.app.data.Backup
import com.openband.app.data.BackupException
import com.openband.app.data.BackupStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun day(ms: Long) = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))
private fun count(n: Int, one: String) = "%,d %s%s".format(n, one, if (n == 1) "" else "s")

/** Save everything to a file the user picks (Downloads, Drive…), and bring it back later or on another phone. */
@Composable
fun BackupCard() {
    val look = LocalRace.current
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // ok? to text
    var found by remember { mutableStateOf<Backup?>(null) }
    var last by remember { mutableStateOf(BackupStore.lastSaved(ctx)) }

    fun perform(work: suspend () -> String) {
        busy = true; message = null
        scope.launch {
            message = try {
                true to withContext(Dispatchers.IO) { work() }
            } catch (e: BackupException) {
                false to e.message!!
            } catch (e: Exception) {
                false to "That did not work: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) perform {
            val b = BackupStore.save(ctx, uri)
            last = b.createdAt
            "Saved ${count(b.samples.size, "reading")} and ${count(b.workouts.size, "workout")}, with your settings."
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform { found = BackupStore.load(ctx, uri); "" }
    }

    SectionCard("Backup and restore") {
        Text(
            "One file with all your readings, workouts, routes, settings, coach chat and profile photo. Keep it in Downloads or " +
                "Google Drive, then restore it after reinstalling or on a new phone. It is not password protected, so keep it private. " +
                "Your AI key is not included.",
            style = MaterialTheme.typography.bodySmall, color = look.dim,
        )
        Spacer(Modifier.height(10.dp))
        RaceButton("Save a backup", Modifier.fillMaxWidth(), enabled = !busy) {
            save.launch("OpenBand-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip")
        }
        Spacer(Modifier.height(8.dp))
        RaceButton("Restore from a backup", Modifier.fillMaxWidth(), primary = false, enabled = !busy) { pick.launch(arrayOf("*/*")) }
        val status = when {
            busy -> true to "Working…"
            else -> message?.takeIf { it.second.isNotEmpty() }
        }
        status?.let { (ok, text) ->
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (ok) look.ok else look.warn, modifier = Modifier.padding(top = 10.dp))
        }
        if (last > 0) Text("Last saved ${day(last)}", style = MaterialTheme.typography.labelSmall, color = look.dim, modifier = Modifier.padding(top = 8.dp))
    }

    found?.let { b ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Saved ${day(b.createdAt)} with ${count(b.samples.size, "reading")} and ${count(b.workouts.size, "workout")}.\n\n" +
                        "Anything missing on this phone is added. Nothing already here is deleted. Settings and profile photo " +
                        "change to the ones in this file.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    found = null
                    perform {
                        val added = BackupStore.restore(ctx, b)
                        "Restored. Added ${count(added.samples, "reading")} and ${count(added.workouts, "workout")}; settings applied."
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { found = null }) { Text("Cancel") } },
        )
    }
}
