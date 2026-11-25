package com.openband.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.ble.BandService
import com.openband.app.ui.BandScreen
import com.openband.app.ui.HealthScreen
import com.openband.app.ui.HistoryScreen
import com.openband.app.ui.HomeScreen
import com.openband.app.ui.LabScreen
import com.openband.app.ui.LocalRace
import com.openband.app.ui.OpenBandTheme
import com.openband.app.ui.RaceBackground
import com.openband.app.ui.RaceIcons
import com.openband.app.ui.UiStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        runCatching { BandService.start(this) } // only does something in "always connected" mode
        val crash = CrashLog.read(this)
        setContent {
            OpenBandTheme {
                val lightUi = LocalRace.current.light
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).run {
                        isAppearanceLightStatusBars = lightUi
                        isAppearanceLightNavigationBars = lightUi
                    }
                }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var lab by rememberSaveable { mutableStateOf(false) }
                var detail by rememberSaveable { mutableStateOf<String?>(null) }
                var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
                var crashText by remember { mutableStateOf(crash) }
                BackHandler(enabled = lab) { lab = false }
                BackHandler(enabled = !lab && detail != null) { detail = null }
                BackHandler(enabled = !lab && detail == null && tab == 4 && settingsPage != null) { settingsPage = null }
                BackHandler(enabled = !lab && detail == null && tab != 0 && !(tab == 4 && settingsPage != null)) { tab = 0 }

                RaceBackground {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                            val m = Modifier
                            androidx.compose.runtime.CompositionLocalProvider(com.openband.app.ui.LocalOpenMetric provides { id: String -> detail = id }) {
                                if (lab) LabScreen(m, onBack = { lab = false })
                                else if (detail != null) com.openband.app.ui.MetricDetailScreen(detail!!, m, onBack = { detail = null })
                                else when (tab) {
                                    0 -> HomeScreen(m, onOpenBand = { settingsPage = "band"; tab = 4 }, onOpenHealth = { settingsPage = "health"; tab = 4 })
                                    1 -> com.openband.app.ui.SportsScreen(m)
                                    2 -> HistoryScreen(m)
                                    3 -> com.openband.app.ui.ProfileScreen(m)
                                    4 -> com.openband.app.ui.SettingsScreen(m, settingsPage, { settingsPage = it }, onOpenLab = { lab = true })
                                }
                            }
                        }
                        RaceNavBar(tab) { lab = false; detail = null; if (it != 4) settingsPage = null; tab = it }
                    }
                }

                crashText?.let { txt ->
                    AlertDialog(
                        onDismissRequest = { crashText = null; CrashLog.clear(this) },
                        title = { Text("The app crashed last time") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text("Please share this report with the developer.", fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(txt.take(4000), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                        },
                        confirmButton = { TextButton(onClick = { CrashLog.share(this); crashText = null }) { Text("Share") } },
                        dismissButton = { TextButton(onClick = { crashText = null; CrashLog.clear(this) }) { Text("Dismiss") } },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching { com.openband.app.ble.BandManager.setForeground(true) }
    }

    override fun onStop() {
        runCatching { com.openband.app.ble.BandManager.setForeground(false) }
        super.onStop()
    }

    @Composable
    private fun RaceNavBar(selected: Int, onSelect: (Int) -> Unit) {
        val look = LocalRace.current
        val n = look.names
        val labels = listOf(n.dash, n.sport, n.history, n.profile, n.settings)
        val icons: List<ImageVector> = when (look.style) {
            UiStyle.RACING, UiStyle.CYBER -> listOf(RaceIcons.Gauge, RaceIcons.Bolt, RaceIcons.Flag, RaceIcons.Helmet, RaceIcons.Wheel)
            else -> listOf(Icons.Filled.Home, RaceIcons.Bolt, Icons.Filled.DateRange, Icons.Filled.Person, Icons.Filled.Settings)
        }
        val barBg = when (look.style) {
            UiStyle.CLEAN -> Modifier.background(look.panel)
            UiStyle.RINGS -> Modifier.background(Color.Black)
            else -> Modifier.background(Brush.verticalGradient(listOf(look.panel.copy(alpha = 0.96f), Color.Black)))
        }
        Column(Modifier.fillMaxWidth().then(barBg)) {
            if (look.style == UiStyle.CYBER) Box(Modifier.fillMaxWidth().height(1.dp).background(look.primary.copy(alpha = 0.6f)))
            if (look.style == UiStyle.CLEAN) Box(Modifier.fillMaxWidth().height(1.dp).background(look.border))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
                labels.forEachIndexed { i, label ->
                    val sel = i == selected
                    Column(
                        Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (look.style) {
                            UiStyle.RACING, UiStyle.CYBER -> {
                                Box(Modifier.width(28.dp).height(3.dp).background(if (sel) look.primary else Color.Transparent))
                                Spacer(Modifier.height(5.dp))
                                Icon(icons[i], label, Modifier.size(24.dp), tint = if (sel) look.primary else look.dim)
                            }
                            else -> Box(
                                Modifier.clip(RoundedCornerShape(50)).background(if (sel) look.primary.copy(alpha = 0.18f) else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            ) { Icon(icons[i], label, Modifier.size(22.dp), tint = if (sel) look.primary else look.dim) }
                        }
                        Text(
                            if (look.upperTitles) label.uppercase() else label,
                            fontFamily = if (look.style == UiStyle.RACING) look.display else look.body,
                            fontStyle = if (look.italicTitles) FontStyle.Italic else FontStyle.Normal,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (look.style == UiStyle.RACING) 9.sp else 11.sp,
                            color = if (sel) look.text else look.dim, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
