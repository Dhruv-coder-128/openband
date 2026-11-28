package com.openband.app

import androidx.compose.foundation.layout.fillMaxHeight
import com.openband.app.ui.studio
import com.openband.app.ui.pressable
import com.openband.app.ui.AppIcons
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.togetherWith
import android.os.Bundle
import kotlinx.coroutines.launch
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
import com.openband.app.ui.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.openband.app.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

    companion object {
        /** "home" or a page id: where a tapped notification should lead. */
        const val EXTRA_OPEN = "open"
    }

    private val openRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        runCatching { BandService.start(this) } // only does something in "always connected" mode
        // every time the app comes to the front, pick up what the Pebble app has saved since last time
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            private var last = 0L
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                if (System.currentTimeMillis() - last < 60_000) return
                last = System.currentTimeMillis()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching {
                        com.openband.app.health.HealthRepo(applicationContext).import(quick = true)
                        com.openband.app.widget.WidgetUpdater.updateAll(applicationContext)
                    }
                }
            }
        })
        runCatching { com.openband.app.live.LiveService.createChannels(this) }
        // Android 13+: the always-on notification needs this permission (asked once)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val asked = getSharedPreferences("live", MODE_PRIVATE)
            if (!asked.getBoolean("asked_notif", false)) {
                asked.edit().putBoolean("asked_notif", true).apply()
                registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
                    runCatching { com.openband.app.live.LiveService.sync(this) }
                }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        runCatching { com.openband.app.live.LiveService.sync(this) }
        val crash = CrashLog.read(this)
        // only on a fresh start: a rotated or restored screen keeps where the user is
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN)?.let { openRequest.value = it }
        setContent {
            OpenBandTheme {
                val lightUi = LocalRace.current.light
                val immersiveNow = com.openband.app.ui.Immersive.dark
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).run {
                        isAppearanceLightStatusBars = lightUi && !immersiveNow
                        isAppearanceLightNavigationBars = lightUi && !immersiveNow
                    }
                }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var lab by rememberSaveable { mutableStateOf(false) }
                var detail by rememberSaveable { mutableStateOf<String?>(null) }
                var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
                var crashText by remember { mutableStateOf(crash) }
                val wanted by openRequest.collectAsState()
                androidx.compose.runtime.LaunchedEffect(wanted) {
                    val w = wanted ?: return@LaunchedEffect
                    lab = false
                    if (w == "home") { detail = null; tab = 0 } else detail = w
                    openRequest.value = null
                }
                BackHandler(enabled = lab) { lab = false }
                BackHandler(enabled = !lab && detail != null) { detail = detail?.substringBeforeLast('|', "")?.ifEmpty { null } }
                BackHandler(enabled = !lab && detail == null && tab == 4 && settingsPage != null) { settingsPage = null }
                BackHandler(enabled = !lab && detail == null && tab != 0 && !(tab == 4 && settingsPage != null)) { tab = 0 }

                RaceBackground {
                    val immersive = com.openband.app.ui.Immersive.dark
                    Column(Modifier.fillMaxSize().background(if (immersive) Color(0xFF070A0C) else Color.Transparent)) {
                        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
                            val m = Modifier
                            // pages stack on each other ("a|b"); Back removes the top one
                            androidx.compose.runtime.CompositionLocalProvider(com.openband.app.ui.LocalOpenMetric provides { id: String -> detail = detail?.let { "$it|$id" } ?: id }) {
                                // keeps each screen's tab choice and scroll position while a page is open over it
                                val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                                // screens fade + slide in; the outgoing one fades out using the state it was shown with
                                val screenKey = listOf(lab, detail, tab, if (tab == 4) settingsPage else null)
                                androidx.compose.animation.AnimatedContent(
                                    targetState = screenKey,
                                    transitionSpec = {
                                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240, delayMillis = 60)) +
                                            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(300)) { it / 28 }) togetherWith
                                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                                    },
                                    label = "screen",
                                ) { key ->
                                    val kLab = key[0] as Boolean
                                    val kDetail = key[1] as String?
                                    val kTab = key[2] as Int
                                    holder.SaveableStateProvider(key.joinToString("/") { it.toString() }) {
                                    val top = kDetail?.substringAfterLast('|')
                                    val pop = { detail = kDetail?.substringBeforeLast('|', "")?.ifEmpty { null } }
                                    if (kLab) LabScreen(m, onBack = { lab = false })
                                    else if (top != null && top.startsWith("page:")) com.openband.app.ui.PageScreen(top, m, onBack = pop)
                                    else if (top != null) com.openband.app.ui.MetricDetailScreen(top, m, onBack = pop)
                                    else when (kTab) {
                                        0 -> HomeScreen(m, onOpenBand = { settingsPage = "band"; tab = 4 }, onOpenHealth = { settingsPage = "health"; tab = 4 })
                                        1 -> com.openband.app.ui.SportsScreen(m)
                                        2 -> HistoryScreen(m)
                                        3 -> com.openband.app.ui.ProfileScreen(m)
                                        4 -> com.openband.app.ui.SettingsScreen(m, key[3] as String?, { settingsPage = it }, onOpenLab = { lab = true })
                                    }
                                    }
                                }
                            }
                        }
                        // the coach chat and a measurement use the whole screen
                        val top = detail?.substringAfterLast('|')
                        val full = top == "page:coach" || top == "page:recap" || top?.startsWith("page:measure") == true || top?.startsWith("page:story") == true
                        if (lab || !full) RaceNavBar(tab) { lab = false; detail = null; if (it != 4) settingsPage = null; tab = it }
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

    /** Standard bottom bar: five equal tabs, icon + label always visible, selected tab in the accent colour. */
    @Composable
    private fun StudioNavBar(labels: List<String>, icons: List<ImageVector>, selected: Int, onSelect: (Int) -> Unit) {
        val look = LocalRace.current
        if (look.style == UiStyle.TERMINAL) {
            // tabs written as folders
            Column(Modifier.fillMaxWidth().background(look.panel)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(look.border))
                Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    labels.forEachIndexed { i, label ->
                        val sel = i == selected
                        Box(Modifier.weight(if (i == 1 || i == 4) 1.2f else 1f).fillMaxHeight().pressable { onSelect(i) }, contentAlignment = Alignment.Center) {
                            Text(
                                label.lowercase(), maxLines = 1, softWrap = false, fontFamily = look.body, fontSize = 11.5.sp,
                                color = if (sel) look.bg else look.dim, modifier = Modifier.background(if (sel) look.primary else look.panel).padding(horizontal = 5.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            return
        }
        Column(Modifier.fillMaxWidth().background(look.panel)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(look.border))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
                labels.forEachIndexed { i, label ->
                    val sel = i == selected
                    val col by androidx.compose.animation.animateColorAsState(if (sel) look.primary else look.dim, androidx.compose.animation.core.tween(160), label = "tab")
                    Column(
                        Modifier.weight(1f).fillMaxHeight().pressable { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.width(if (sel) 28.dp else 0.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(look.primary))
                        Spacer(Modifier.height(if (sel) 5.dp else 8.dp))
                        Icon(icons[i], label, Modifier.size(24.dp), tint = col)
                        Text(if (look.style == UiStyle.HACKER) (if (sel) "[${label.uppercase()}]" else label.uppercase()) else label, color = col, fontSize = if (look.style == UiStyle.HACKER) 10.sp else 11.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun RaceNavBar(selected: Int, onSelect: (Int) -> Unit) {
        val look = LocalRace.current
        val n = look.names
        val labels = listOf(n.dash, n.sport, n.history, n.profile, n.settings)
        if (look.style.studio) {
            StudioNavBar(labels, listOf(AppIcons.Home, AppIcons.Activity, AppIcons.Trends, AppIcons.Profile, AppIcons.Settings), selected, onSelect)
            return
        }
        val icons: List<ImageVector> = when (look.family) {
            UiStyle.RACING, UiStyle.CYBER -> listOf(RaceIcons.Gauge, RaceIcons.Bolt, RaceIcons.Flag, RaceIcons.Helmet, RaceIcons.Wheel)
            else -> listOf(Icons.Filled.Home, RaceIcons.Bolt, Icons.Filled.DateRange, Icons.Filled.Person, Icons.Filled.Settings)
        }
        val barBg = when (look.family) {
            UiStyle.CLEAN -> Modifier.background(look.panel)
            UiStyle.RINGS -> Modifier.background(look.bg)
            else -> Modifier.background(Brush.verticalGradient(listOf(look.panel.copy(alpha = 0.96f), Color.Black)))
        }
        Column(Modifier.fillMaxWidth().then(barBg)) {
            if (look.family == UiStyle.CYBER) Box(Modifier.fillMaxWidth().height(1.dp).background(look.primary.copy(alpha = 0.6f)))
            if (look.family == UiStyle.CLEAN) Box(Modifier.fillMaxWidth().height(1.dp).background(look.border))
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
                labels.forEachIndexed { i, label ->
                    val sel = i == selected
                    Column(
                        Modifier.weight(1f).clickable { onSelect(i) }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        when (look.family) {
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
                            fontFamily = if (look.family == UiStyle.RACING) look.display else look.body,
                            fontStyle = if (look.italicTitles) FontStyle.Italic else FontStyle.Normal,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            fontSize = if (look.family == UiStyle.RACING) 9.sp else 11.sp,
                            color = if (sel) look.text else look.dim, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
