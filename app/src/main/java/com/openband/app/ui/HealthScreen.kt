package com.openband.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.openband.app.data.Db
import com.openband.app.data.M
import com.openband.app.health.HealthRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HealthScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = remember { HealthRepo(ctx) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(repo.lastReport) }
    var writeBack by remember { mutableStateOf(repo.writeBack) }
    val ver by Db.changes.collectAsState()
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var sources by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val status = remember(tick) { repo.sdkStatus() }

    LaunchedEffect(tick) { granted = repo.granted() }
    LaunchedEffect(ver) {
        withContext(Dispatchers.IO) {
            val db = Db.get(ctx)
            counts = db.counts(); sources = db.sourceCounts()
        }
    }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        tick++
        if (it.isNotEmpty()) scope.launch {
            busy = true
            report = withContext(Dispatchers.IO) { repo.import() }
            busy = false
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        ScreenTitle(LocalRace.current.names.health, "Health Connect data feed")

        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE -> SectionCard("Not supported") {
                Text("Health Connect is not available on this phone (needs Android 9 or newer).")
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SectionCard("Install / update Health Connect") {
                Text("Health Connect needs to be installed or updated.")
                Button(onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${HealthRepo.PROVIDER}&url=healthconnect%3A%2F%2Fonboarding"))
                            .setPackage("com.android.vending")
                    )
                }) { Text("Open Play Store") }
                OutlinedButton(onClick = { tick++ }) { Text("Check again") }
            }
            else -> {
                SectionCard("Permissions") {
                    val total = HealthRepo.PERMISSIONS.size
                    val have = HealthRepo.PERMISSIONS.count { it in granted }
                    Text("$have of $total permissions granted")
                    Text(
                        "Tip: choose \"Allow all\" so every data type (SpO2, HRV, sleep stages, temperature…) and " +
                            "background reading are included.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { launcher.launch(HealthRepo.PERMISSIONS) }) { Text(if (have < total) "Grant access" else "Review access") }
                    }
                }

                SectionCard("Auto import · every 15 min") {
                    Text(
                        "Every 15 minutes OpenBand reads all new data from Health Connect (from the official band app, " +
                            "Google Fit, Samsung Health, etc.).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (repo.lastImport > 0) KeyValue(
                        "Last import", SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(repo.lastImport))
                    )
                    if (report.isNotEmpty()) Text(report, style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = {
                            scope.launch { busy = true; report = withContext(Dispatchers.IO) { repo.import() }; busy = false; tick++ }
                        }) { Text(if (busy) "Importing…" else "Import now") }
                        OutlinedButton(enabled = !busy, onClick = {
                            scope.launch { busy = true; report = withContext(Dispatchers.IO) { repo.import(fullDays = 365) }; busy = false; tick++ }
                        }) { Text("Re-import 1 year") }
                    }
                }

                SectionCard("Write band data to Health Connect") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Data read directly from the band (SpO2, HRV, temperature, heart rate…) is also saved to " +
                                "Health Connect so other apps can use it.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        )
                        Switch(checked = writeBack, onCheckedChange = { writeBack = it; repo.writeBack = it })
                    }
                }
            }
        }

        SectionCard("Data stored in the app") {
            if (counts.isEmpty()) Text("Nothing yet")
            counts.entries.sortedBy { it.key }.forEach { (k, v) -> KeyValue(M.label(k), "$v") }
            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Sources", style = MaterialTheme.typography.labelLarge)
                sources.forEach { (k, v) -> KeyValue(k, "$v") }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val f = withContext(Dispatchers.IO) { Export.csv(ctx) }
                        Export.shareCsv(ctx, f)
                    }
                }) { Text("Export CSV") }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
