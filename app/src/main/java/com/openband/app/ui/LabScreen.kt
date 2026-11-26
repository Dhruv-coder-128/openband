package com.openband.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ble.BandManager
import com.openband.app.ble.CharInfo
import com.openband.app.ble.SnoopImporter
import com.openband.app.data.Db
import com.openband.app.data.RawPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LabScreen(modifier: Modifier, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val st by BandManager.state.collectAsState()
    val rawVer by Db.rawChanges.collectAsState()
    val scope = rememberCoroutineScope()
    var packets by remember { mutableStateOf<List<RawPacket>>(emptyList()) }
    var learned by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<CharInfo?>(null) }
    var hex by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(rawVer) {
        withContext(Dispatchers.IO) {
            packets = db.recentRaw(150)
            learned = db.learnedWrites().size
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            msg = "Importing…"
            msg = withContext(Dispatchers.IO) {
                try {
                    val name = uri.lastPathSegment ?: ""
                    val isZip = ctx.contentResolver.getType(uri)?.contains("zip") == true || name.endsWith(".zip", true)
                    val res = ctx.contentResolver.openInputStream(uri)!!.use { SnoopImporter.import(it, isZip, st.address) }
                    db.clearRaw("SNOOP")
                    db.insertRawBatch(res.packets)
                    "Imported ${res.writes} commands and ${res.notifies} responses" +
                        (res.chosenAddress?.let { " from $it" } ?: "") + " (${res.connections} BLE connections in log)." +
                        if (res.writes > 0) "\nThey will be replayed on every sync." else ""
                } catch (e: Exception) {
                    "Import failed: ${e.message}"
                }
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Text("◀ " + LocalRace.current.names.garage, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp).clickable(onClick = onBack))
        ScreenTitle(LocalRace.current.names.lab, "Teach the app your band's protocol")
        Text(
            "Tools to teach OpenBand the band's private protocol, so it can read data the official app keeps to itself.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionCard("1 · Learn from the official app") {
            Text(
                "a) Phone Settings → Developer options → enable \"Bluetooth HCI snoop log\", then turn Bluetooth off and on.\n" +
                    "b) Open the official band app and do a full sync (and open each screen: SpO2, HRV, stress, sleep…).\n" +
                    "c) Developer options → \"Take bug report\" (full) and save the .zip (or copy btsnoop_hci.log).\n" +
                    "d) Import it here. Then close the official app and connect the band in OpenBand.",
                style = MaterialTheme.typography.bodySmall,
            )
            KeyValue("Learned commands", "$learned")
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import snoop log / bug report") }
                if (learned > 0) TextButton(onClick = { scope.launch(Dispatchers.IO) { db.clearRaw("SNOOP") } }) { Text("Forget learned") }
            }
        }

        SectionCard("2 · Share a report") {
            Text(
                "Creates a text file with the band's services, the learned commands and all captured packets. " +
                    "Share it with a developer to add a full decoder for your band.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    val f = withContext(Dispatchers.IO) { Export.labReport(ctx) }
                    Export.shareText(ctx, f)
                }
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Export lab report") }
        }

        SectionCard("3 · GATT services") {
            if (st.services.isEmpty()) Text("Connect the band to see its services.", style = MaterialTheme.typography.bodySmall)
            st.services.forEach { s ->
                Text("Service ${s.uuid}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                s.chars.forEach { c ->
                    val sel = selected?.handle == c.handle
                    Text(
                        "  0x%04x  %s  [%s]".format(c.handle, c.uuid.toString().take(8), c.propsText),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clickable { selected = c },
                    )
                }
            }
        }

        SectionCard("4 · Send command") {
            val c = selected
            Text(
                if (c == null) "Tap a characteristic above to select it." else "Target 0x%04x %s [%s]".format(c.handle, c.uuid, c.propsText),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = hex, onValueChange = { hex = it }, label = { Text("Hex bytes, e.g. 03 00 00") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true,
            )
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = c != null && c.writable && st.ready, onClick = { BandManager.rawWrite(c!!.handle, hex) }) { Text("Write") }
                OutlinedButton(enabled = c != null && st.ready, onClick = { BandManager.rawRead(c!!.handle) }) { Text("Read") }
            }
        }

        SectionCard("5 · Packet log (newest first)") {
            val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
            if (packets.isEmpty()) Text("No packets yet", style = MaterialTheme.typography.bodySmall)
            packets.forEach { p ->
                Text(
                    "${tf.format(Date(p.ts))} %-8s 0x%04x %s".format(p.dir, p.handle, p.hex),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                )
            }
            TextButton(onClick = { scope.launch(Dispatchers.IO) { db.clearRaw("RX"); db.clearRaw("TX"); db.clearRaw("READ") } }) { Text("Clear live log") }
        }
        Spacer(Modifier.height(16.dp))
    }
}
