package com.openband.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.openband.app.ui.AppDropdown
import com.openband.app.ui.FilterChip
import com.openband.app.ui.LocalRace
import com.openband.app.ui.OpenBandTheme
import com.openband.app.ui.RaceBackground
import com.openband.app.ui.RaceButton
import com.openband.app.ui.ScreenTitle
import com.openband.app.ui.SectionCard
import com.openband.app.ui.Toggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings of one widget on the home screen: what it shows, theme, background, colour. */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val mgr = AppWidgetManager.getInstance(this)
        val provider = if (id == AppWidgetManager.INVALID_APPWIDGET_ID) null else mgr.getAppWidgetInfo(id)?.provider?.className
        val info = WidgetUpdater.catalog.firstOrNull { it.cls.name == provider }
        if (info == null) { finish(); return }
        // the widget stays even if the user just backs out
        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        enableEdgeToEdge()
        val size = WidgetUpdater.placed(this).firstOrNull { it.id == id }?.let { it.wDp to it.hDp } ?: (info.wDp to info.hDp)
        val start = WidgetConfigs.load(this, id, info.kind)
        setContent {
            OpenBandTheme {
                val light = LocalRace.current.light
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).run { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
                }
                RaceBackground {
                    ConfigScreen(start, size) { cfg ->
                        WidgetConfigs.save(this, id, cfg)
                        WidgetUpdater.refresh(this, null)
                        finish()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigScreen(start: WidgetConfig, size: Pair<Int, Int>, onSave: (WidgetConfig) -> Unit) {
    val look = LocalRace.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cfg by remember { mutableStateOf(start) }
    var data by remember { mutableStateOf<WData?>(null) }
    LaunchedEffect(Unit) { data = withContext(Dispatchers.IO) { runCatching { loadWidgetData(ctx) }.getOrNull() } }
    val catalog = WidgetUpdater.catalog

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp)) {
        ScreenTitle("Customise widget")
        // preview at the widget's real shape, on a neutral "wallpaper" so the background setting is visible
        Box(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF7D8590)).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val d = data
            val ratio = size.first.toFloat() / size.second.coerceAtLeast(1)
            if (d != null) {
                val bmp = remember(cfg, d) { WidgetUpdater.draw(ctx, cfg, size.first, size.second, d).asImageBitmap() }
                Image(
                    bmp, "Preview",
                    Modifier.heightIn(max = 220.dp).then(if (size.first >= 220) Modifier.fillMaxWidth() else Modifier.fillMaxWidth((size.first / 300f).coerceIn(0.28f, 0.75f))).aspectRatio(ratio),
                    contentScale = ContentScale.Fit,
                )
            } else Spacer(Modifier.height(160.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionCard("Shows") {
                AppDropdown(catalog.map { it.label }, catalog.indexOfFirst { it.kind == cfg.kind }.coerceAtLeast(0), { i -> cfg = cfg.copy(kind = catalog[i].kind) })
                if (cfg.kind == "vitals") {
                    Spacer(Modifier.height(8.dp))
                    val names = mapOf("hr" to "Heart rate", "spo2" to "SpO2", "hrv" to "HRV", "stress" to "Stress", "temp" to "Temperature", "battery" to "Band battery")
                    VITAL_TILES.forEach { t ->
                        Toggle(names.getValue(t), t in cfg.tiles) { on ->
                            val next = VITAL_TILES.filter { if (it == t) on else it in cfg.tiles }
                            if (next.isNotEmpty()) cfg = cfg.copy(tiles = next)
                        }
                    }
                }
            }
            SectionCard("Theme") {
                Chips(listOf("Same as app", "Light", "Dark"), cfg.theme) { cfg = cfg.copy(theme = it) }
            }
            SectionCard("Background") {
                val levels = listOf(100, 75, 40, 0)
                Chips(listOf("Solid", "75%", "40%", "None"), levels.indexOf(cfg.opacity).coerceAtLeast(0)) { cfg = cfg.copy(opacity = levels[it]) }
            }
            SectionCard("Colour") {
                val colors = listOf(
                    0, Color(0xFFD4501F).toArgb(), Color(0xFF2F6FD0).toArgb(), Color(0xFF23855A).toArgb(),
                    Color(0xFF7655AE).toArgb(), Color(0xFFC83A52).toArgb(), Color(0xFFD08A12).toArgb(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { c ->
                        val on = cfg.accent == c
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (c == 0) look.panelHi else Color(c))
                                .border(if (on) 3.dp else 1.dp, if (on) look.text else look.border, CircleShape).clickable { cfg = cfg.copy(accent = c) },
                            contentAlignment = Alignment.Center,
                        ) { if (c == 0) Text("A", style = MaterialTheme.typography.titleMedium, color = look.text) }
                    }
                }
                Text("A = each reading keeps its own colour.", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 8.dp))
            }
        }
        RaceButton("Save", Modifier.fillMaxWidth().padding(vertical = 10.dp)) { onSave(cfg) }
    }
}

@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(o) }) }
    }
}
