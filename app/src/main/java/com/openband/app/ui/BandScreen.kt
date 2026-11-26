package com.openband.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openband.app.ble.BandManager
import com.openband.app.ble.BandService
import com.openband.app.ble.LiveKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("BatteryLife")
@Composable
fun BandScreen(modifier: Modifier, onOpenLab: () -> Unit = {}) {
    val ctx = LocalContext.current
    val st by BandManager.state.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    val hasPerm = remember(permTick) { BandManager.hasPermissions(ctx) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        if (BandManager.hasPermissions(ctx)) {
            BandService.start(ctx)
        }
    }
    fun askPerms() {
        val list = BandManager.requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        val look = LocalRace.current
        ScreenTitle(look.names.garage, "Your band · connection · live measurements")

        if (!hasPerm) {
            SectionCard("Permission needed") {
                Text("Bluetooth permission is needed to find and connect to your band.")
                Button(onClick = { askPerms() }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow Bluetooth") }
            }
            return@Column
        }

        if (st.address != null) {
            SectionCard(look.pick("Car · ", "", "UNIT · ") + (st.deviceName ?: "Band")) {
                KeyValue("Status", st.status)
                KeyValue("Address", st.address ?: "")
                st.battery?.let { KeyValue("Battery", "$it%") }
                if (st.protocols.isNotEmpty()) KeyValue("Protocol", st.protocols.joinToString(" + "))
                st.info.forEach { (k, v) -> KeyValue(k, v) }
                if (st.lastSync > 0) KeyValue(
                    "Last sync", SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(st.lastSync))
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = st.ready && !st.syncing, onClick = { BandManager.requestSync() }) {
                        Text(if (st.syncing) "Syncing…" else "Sync now")
                    }
                    if (!st.connected) OutlinedButton(onClick = { BandManager.ensureConnected() }) { Text("Connect") }
                    TextButton(onClick = { BandManager.forget() }) { Text("Forget band") }
                }
            }

            SectionCard(look.pick("Shared garage · official app", "Works together with the Pebble app", "SHARED_LINK")) {
                if (st.waitingOfficial) Text("● Waiting for the Pebble app to connect the band. Open the Pebble app once - OpenBand joins its connection automatically.",
                    color = look.warn, style = MaterialTheme.typography.bodySmall)
                if (st.otherApp) Text("● The Pebble app is using the band right now - OpenBand is waiting.", color = look.warn, style = MaterialTheme.typography.bodySmall)
                Text(
                    "OpenBand never opens the band connection itself and never changes anything on the band: it only joins the Pebble app's " +
                        "connection and reads the data. Everything in the Pebble app (measure buttons, sync, settings) keeps working.",
                    style = MaterialTheme.typography.bodySmall, color = look.dim,
                )
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use without the Pebble app", color = look.text, fontWeight = FontWeight.SemiBold)
                        Text("Only if you don't use the Pebble app at all", style = MaterialTheme.typography.labelSmall, color = look.dim)
                    }
                    Switch(checked = st.soloAllowed, onCheckedChange = { BandManager.soloAllowed = it })
                }
                if (st.soloAllowed || !BandManager.officialInstalled()) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stay connected in background", color = look.text, fontWeight = FontWeight.SemiBold)
                        Text("Off = only while OpenBand is open", style = MaterialTheme.typography.labelSmall, color = look.dim)
                    }
                    Switch(checked = st.alwaysOn, onCheckedChange = { BandManager.alwaysOn = it })
                } else Text(
                    "OpenBand reads your band only while it is open on screen, and lets go the moment you leave. " +
                        "In the background it stays silent, so the Pebble app is never disturbed. The band stores 7 days, so nothing is lost.",
                    style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "For reliable background sync, allow OpenBand to ignore battery optimisation.",
                    style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 6.dp),
                )
                val pm = ctx.getSystemService(PowerManager::class.java)
                val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
                if (!ignoring) OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Allow background") }
                else Text("✓ Background allowed", color = MaterialTheme.colorScheme.primary)
            }
        }

        // no band paired yet: start looking right away, nothing to press
        androidx.compose.runtime.LaunchedEffect(st.address, hasPerm) { if (st.address == null && hasPerm && !st.scanning) BandManager.startScan() }
        SectionCard(if (st.address == null) "Find your Pebble band" else "Pair another band") {
            Text(
                "Keep the band close and tap it so it wakes up. Only Pebble / Qore bands in Bluetooth range are listed - the strongest signal first.",
                style = MaterialTheme.typography.bodySmall, color = look.dim,
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (st.scanning) OutlinedButton(onClick = { BandManager.stopScan() }) { Text("Stop") }
                else Button(onClick = { BandManager.startScan() }) { Text("Scan again") }
                if (st.scanning) Text("searching…", style = MaterialTheme.typography.labelSmall, color = look.dim)
            }
            if (st.found.isEmpty() && !st.scanning) Text("No Pebble band found nearby yet.", style = MaterialTheme.typography.bodySmall, color = look.warn, modifier = Modifier.padding(top = 8.dp))
            for (d in st.found) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val bars = when { d.rssi >= -60 -> "▂▄▆█"; d.rssi >= -75 -> "▂▄▆"; d.rssi > -100 -> "▂▄"; else -> "" }
                Row(
                    Modifier.fillMaxWidth().clickable { BandManager.pair(d.address, d.name) }.padding(vertical = 10.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.Bold)
                        Text(d.address + if (d.rssi > -100) "  ·  ${d.rssi} dBm" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(bars, color = look.primary)
                    Text("  Connect", color = look.primary, fontWeight = FontWeight.Bold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Also show other Bluetooth devices", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = look.dim)
                Switch(checked = st.scanAll, onCheckedChange = { BandManager.setScanAll(it) })
            }
        }

        SectionCard(look.names.lab) {
            Text("Protocol lab: teach the app your band's private protocol, raw packets, commands.", style = MaterialTheme.typography.bodySmall, color = look.dim)
            RaceButton(look.pick("Open diagnostics", "Open protocol lab"), Modifier.padding(top = 8.dp), primary = false, onClick = onOpenLab)
        }

        SectionCard(look.pick("Team radio · connection log", "Connection log")) {
            if (st.log.isEmpty()) Text("—")
            st.log.take(40).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = look.dim) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
