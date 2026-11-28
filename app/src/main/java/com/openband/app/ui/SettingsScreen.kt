package com.openband.app.ui

import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private class SettingsItem(val id: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val title: String, val sub: String)

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
                SettingsItem("appearance", AppIcons.Palette, "Appearance", "Look, colours, fonts"),
                SettingsItem("dashboard", AppIcons.Layout, "Dashboard", "Choose what Home shows"),
                SettingsItem("widgets", AppIcons.Widgets, "Widgets", "15 home-screen widgets"),
            ),
            "Devices" to listOf(
                SettingsItem("band", AppIcons.Watch, "Band", band.deviceName?.let { "$it · " + if (band.ready) "connected" else band.status.lowercase() } ?: "Pair your band, connection log"),
                SettingsItem("health", AppIcons.Heart, "Health Connect", "Import and export health data"),
                SettingsItem("background", AppIcons.Bell, "Background & alerts", "Band, interval, vibration, alerts"),
            ),
            "You" to listOf(
                SettingsItem("goals", AppIcons.Target, "Goals & units", "Daily goals and units"),
                SettingsItem("ai", AppIcons.Coach, "AI coach", "Language and AI key"),
            ),
            "App" to listOf(
                SettingsItem("data", AppIcons.Folder, "Data & privacy", "Backup, export, privacy"),
                SettingsItem("about", AppIcons.Info, "About", "Logo and version"),
            ),
        )
        val open = LocalOpenMetric.current
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            ScreenTitle(look.names.settings, "Everything else lives here")
            groups.forEach { (title, items) ->
                Text(
                    if (look.isConsole) look.style.prompt + themedText(title.lowercase()) else if (look.upperTitles) title.uppercase() else title, style = MaterialTheme.typography.labelMedium, color = look.dim,
                    modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
                )
                Column(Modifier.fillMaxWidth().reveal().clip(look.tile(18)).background(look.panel).then(if (look.isConsole) Modifier.border(1.dp, look.border, look.panelShape) else Modifier)) {
                    items.forEachIndexed { i, it ->
                        Row(Modifier.fillMaxWidth().clickable { if (it.id == "ai") open("page:aisetup") else onPage(it.id) }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            MetricIcon(it.icon, look.text, 38.dp)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(it.title, fontWeight = FontWeight.SemiBold, color = look.text, fontSize = 15.sp)
                                Text(it.sub, style = MaterialTheme.typography.bodySmall, color = look.dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            androidx.compose.material3.Icon(AppIcons.Chevron, null, Modifier.size(20.dp), tint = look.dim)
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
            "appearance" -> "Appearance"; "dashboard" -> "Dashboard"; "widgets" -> "Widgets"; "goals" -> "Goals & units"; "data" -> "Data & privacy"; "about" -> "About"; "background" -> "Background & alerts"
            else -> ""
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton({ onPage(null) })
            Text(heading(if (title.isEmpty()) look.names.settings else title), fontFamily = look.display, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = look.text)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                "band" -> BandScreen(Modifier, onOpenLab = onOpenLab)
                "health" -> HealthScreen(Modifier)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                    when (page) {
                        "appearance" -> AppearanceContent()
                        "dashboard" -> DashboardContent()
                        "widgets" -> WidgetsContent()
                        "goals" -> GoalsContent()
                        "data" -> DataContent()
                        "background" -> BackgroundContent()
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
    BackupCard()
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
            "Your data stays on this phone. Map pictures (OpenStreetMap or Esri satellite) are fetched from the internet for GPS workouts. " +
                "Only if you set up the AI coach and agree, a summary of your numbers (no name, photo or location) goes to the AI service you chose, when you ask it something.",
            style = MaterialTheme.typography.bodySmall, color = look.dim,
        )
    }
}

@Composable
private fun WidgetsContent() {
    val look = LocalRace.current
    val ctx = LocalContext.current
    var data by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.openband.app.widget.WData?>(null) }
    var placed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptyList<com.openband.app.widget.WidgetUpdater.Placed>()) }
    var visits by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) visits++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val p = look.prefs
    androidx.compose.runtime.LaunchedEffect(p, visits) {
        data = withContext(Dispatchers.IO) { runCatching { com.openband.app.widget.loadWidgetData(ctx) }.getOrNull() }
        placed = withContext(Dispatchers.IO) { runCatching { com.openband.app.widget.WidgetUpdater.placed(ctx) }.getOrDefault(emptyList()) }
    }
    var msg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Text(
        "Every widget resizes from one cell to the full screen and rearranges itself to fit: long-press it on the home screen and drag the handles. " +
            "Choose what it shows, its theme, background and colour with Customise.",
        style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
    msg?.let { Text(it, color = look.warn, style = MaterialTheme.typography.bodySmall) }
    val d = data
    if (placed.isNotEmpty()) SectionCard("On your home screen") {
        placed.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(com.openband.app.widget.WidgetUpdater.label(w.cfg.kind), style = MaterialTheme.typography.bodyMedium, color = look.text)
                    Text("${w.wDp} × ${w.hDp} dp", style = MaterialTheme.typography.labelSmall, color = look.dim)
                }
                RaceButton("Customise", primary = false) {
                    ctx.startActivity(
                        android.content.Intent(ctx, com.openband.app.widget.WidgetConfigActivity::class.java)
                            .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, w.id),
                    )
                }
            }
        }
    }
    com.openband.app.widget.WidgetUpdater.catalog.forEach { info ->
        SectionCard(info.label) {
            Text(info.note, style = MaterialTheme.typography.bodySmall, color = look.dim)
            Spacer(Modifier.height(8.dp))
            if (d != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                val bmp = androidx.compose.runtime.remember(info.kind, d, p) { com.openband.app.widget.WidgetUpdater.preview(ctx, info, d).asImageBitmap() }
                androidx.compose.foundation.Image(
                    bmp, info.label,
                    Modifier.fillMaxWidth(if (info.wDp > 200) 1f else 0.55f).aspectRatio(info.wDp.toFloat() / info.hDp),
                )
            }
            Spacer(Modifier.height(8.dp))
            RaceButton("Add to home screen", Modifier.fillMaxWidth(), primary = false) {
                if (!com.openband.app.widget.WidgetUpdater.pin(ctx, info)) msg = "Your launcher can't add widgets from here. Long-press the home screen → Widgets → OpenBand."
            }
        }
    }
}
