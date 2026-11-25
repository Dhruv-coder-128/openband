package com.openband.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ble.BandManager
import com.openband.app.workout.WorkoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class SettingsItem(val id: String, val emoji: String, val title: String, val sub: String)

/**
 * One home for everything that is not daily use. Each section opens its own page, so nothing here clutters the main tabs.
 */
@Composable
fun SettingsScreen(modifier: Modifier, page: String?, onPage: (String?) -> Unit, onOpenLab: () -> Unit) {
    val look = LocalRace.current
    val band by BandManager.state.collectAsState()

    if (page == null) {
        val groups = listOf(
            "Look" to listOf(
                SettingsItem("appearance", "🎨", "Appearance", "App style, colours, fonts, effects"),
                SettingsItem("dashboard", "🧩", "Dashboard", "Choose and reorder what Home shows"),
            ),
            "Devices" to listOf(
                SettingsItem("band", "⌚", "Band", band.deviceName?.let { "$it · " + if (band.ready) "connected" else band.status.lowercase() } ?: "Pair your band, connection log"),
                SettingsItem("health", "❤️", "Health Connect", "Import and export health data"),
            ),
            "You" to listOf(
                SettingsItem("goals", "🎯", "Goals & units", "Steps, calories, sleep · km / mi · °C / °F"),
            ),
            "App" to listOf(
                SettingsItem("data", "🗂", "Data & privacy", "Export CSV, demo workout, privacy"),
                SettingsItem("about", "ℹ️", "About", "Logo and version"),
            ),
        )
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            ScreenTitle(look.names.settings, "Everything else lives here")
            groups.forEach { (title, items) ->
                Text(
                    if (look.upperTitles) title.uppercase() else title, style = MaterialTheme.typography.labelMedium, color = look.dim,
                    modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                )
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(look.panel)) {
                    items.forEachIndexed { i, it ->
                        Row(Modifier.fillMaxWidth().clickable { onPage(it.id) }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(look.panelHi), contentAlignment = Alignment.Center) { Text(it.emoji, fontSize = 18.sp) }
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(it.title, fontWeight = FontWeight.SemiBold, color = look.text, fontSize = 15.sp)
                                Text(it.sub, style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1)
                            }
                            Text("›", color = look.dim, fontSize = 22.sp)
                        }
                        if (i < items.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(look.panelHi))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        val title = when (page) {
            "appearance" -> "Appearance"; "dashboard" -> "Dashboard"; "goals" -> "Goals & units"; "data" -> "Data & privacy"; "about" -> "About"
            else -> ""
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 34.sp, color = look.text, modifier = Modifier.clip(CircleShape).clickable { onPage(null) }.padding(horizontal = 14.dp))
            Text(if (title.isEmpty()) look.names.settings else title, fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = look.text)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                "band" -> BandScreen(Modifier, onOpenLab = onOpenLab)
                "health" -> HealthScreen(Modifier)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                    when (page) {
                        "appearance" -> AppearanceContent()
                        "dashboard" -> DashboardContent()
                        "goals" -> GoalsContent()
                        "data" -> DataContent()
                        "about" -> AboutContent()
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun DataContent() {
    val look = LocalRace.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    SectionCard("Export") {
        Text("All measurements stored in the app as a spreadsheet (CSV).", style = MaterialTheme.typography.bodySmall, color = look.dim)
        Spacer(Modifier.height(8.dp))
        RaceButton("Export all data (CSV)", Modifier.fillMaxWidth(), primary = false) {
            scope.launch { val f = withContext(Dispatchers.IO) { Export.csv(ctx) }; Export.shareCsv(ctx, f) }
        }
    }
    SectionCard("Demo") {
        Text("Adds a made-up 5 km run with a GPS route so you can see the map, splits and charts. Delete it from the workout page.", style = MaterialTheme.typography.bodySmall, color = look.dim)
        Spacer(Modifier.height(8.dp))
        RaceButton("Add a demo run", Modifier.fillMaxWidth(), primary = false) { scope.launch(Dispatchers.IO) { WorkoutManager.insertDemo() } }
    }
    SectionCard("Privacy") {
        Text(
            "Everything stays on this phone. The only things fetched from the internet are map pictures (OpenStreetMap) for workouts with GPS. " +
                "Your photo, name, health data and workouts are never uploaded.",
            style = MaterialTheme.typography.bodySmall, color = look.dim,
        )
    }
}
