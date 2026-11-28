package com.openband.app.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.openband.app.data.M
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Whole-app looks. Each one changes colours, fonts, shapes, navigation names and the dashboard layout. */
enum class UiStyle(val label: String, val blurb: String) {
    RACING("Racing", "F1 cockpit · gauges · shift lights"),
    CYBER("Cyberpunk", "Neon HUD · glitch · terminal"),
    RINGS("Athlete rings", "Black · sleep, steps & calorie rings"),
    CLEAN("Clean", "Simple friendly cards · light or dark"),
    PAPER("Paper", "Editorial · warm paper · serif type"),
    STUDIO("OpenBand", "Clean, calm and clear · follows light / dark"),
    MIDNIGHT("Midnight", "True black · calm blue · easy at night"),
    BOLD("Bold", "Solid colour hero · big numbers"),
    MONO("Mono", "Black and white · sharp edges · one red dot"),
    HACKER("Hacker", "Digital rain · system-monitor HUD"),
    TERMINAL("Terminal", "Your day as command-line output");

    /** The dashboard layout this look is built on (the four originals). */
    val family: UiStyle
        get() = when (this) {
            PAPER, STUDIO, MIDNIGHT, BOLD, MONO, HACKER, TERMINAL -> CLEAN
            else -> this
        }
}

enum class GaugeStyle(val label: String) { ANALOG("Analog"), SPORT("Sport arc"), DIGITAL("Digital LCD") }
enum class FontStyle(val label: String) { AUTO("Style default"), RACING("Racing"), CLEAN("Clean"), MONO("Mono") }

/** A colour scheme. Names are generic, not brand or team names. */
data class Livery(val id: String, val name: String, val primary: Color, val secondary: Color)

private val RACING_LIVERIES = listOf(
    Livery("rosso", "Rosso Corsa", Color(0xFFE10600), Color(0xFFFFD100)),
    Livery("papaya", "Papaya", Color(0xFFFF8000), Color(0xFF47C7FC)),
    Livery("silver", "Silver Streak", Color(0xFF00D2BE), Color(0xFFC0C0C0)),
    Livery("midnight", "Midnight Blue", Color(0xFF3671C6), Color(0xFFFFC906)),
    Livery("green", "Racing Green", Color(0xFF229971), Color(0xFFCEDC00)),
    Livery("endurance", "Endurance", Color(0xFF6CD3FF), Color(0xFFFF6A13)),
    Livery("gold", "Gold Rush", Color(0xFFD4AF37), Color(0xFFFFFFFF)),
)
private val CYBER_LIVERIES = listOf(
    Livery("nightcity", "Night City", Color(0xFFFF2A6D), Color(0xFF05D9E8)),
    Livery("toxic", "Toxic", Color(0xFF39FF14), Color(0xFFFF00FF)),
    Livery("synth", "Synthwave", Color(0xFFF706CF), Color(0xFFFFD319)),
    Livery("ice", "Ice Breaker", Color(0xFF00F0FF), Color(0xFF7B61FF)),
    Livery("amber", "Amber Term", Color(0xFFFFB000), Color(0xFFFF3C00)),
)
private val RINGS_LIVERIES = listOf(
    Livery("classic", "Classic", Color(0xFF9D8CFF), Color(0xFF4F8DFF)),
    Livery("volt", "Volt", Color(0xFFD4FF00), Color(0xFF00E5FF)),
    Livery("ocean", "Ocean", Color(0xFF2BD9FE), Color(0xFF7C5CFF)),
    Livery("rose", "Rose", Color(0xFFFF5C8A), Color(0xFFFFB86B)),
    Livery("mono", "Mono", Color(0xFFFFFFFF), Color(0xFF9AA0A6)),
)
private val CLEAN_LIVERIES = listOf(
    Livery("teal", "Teal", Color(0xFF0F9D8A), Color(0xFFFF8A3D)),
    Livery("indigo", "Indigo", Color(0xFF5B5BD6), Color(0xFF2EC4B6)),
    Livery("coral", "Coral", Color(0xFFFF6B4A), Color(0xFF3D7BFF)),
    Livery("forest", "Forest", Color(0xFF2E7D32), Color(0xFFF9A825)),
    Livery("graphite", "Graphite", Color(0xFF546E7A), Color(0xFFEF5350)),
)

private val PAPER_LIVERIES = listOf(
    Livery("ink", "Ink", Color(0xFF1B1A17), Color(0xFFD2562B)),
    Livery("terracotta", "Terracotta", Color(0xFFC9502A), Color(0xFF1B1A17)),
    Livery("sage", "Sage", Color(0xFF4F7A5A), Color(0xFFC9A66B)),
    Livery("cobalt", "Cobalt", Color(0xFF2B4FD2), Color(0xFFE8A33D)),
)
private val STUDIO_LIVERIES = listOf(
    Livery("vermilion", "Vermilion", Color(0xFFC23E17), Color(0xFF4A58A8)),
    Livery("forest", "Forest", Color(0xFF1F7A5A), Color(0xFFB07A1F)),
    Livery("indigo", "Indigo", Color(0xFF4252B8), Color(0xFFC23E17)),
    Livery("graphite", "Graphite", Color(0xFF3C4047), Color(0xFFC23E17)),
)

private val MIDNIGHT_LIVERIES = listOf(
    Livery("ice", "Ice blue", Color(0xFF6EA8FE), Color(0xFF9AA6EA)),
    Livery("mint", "Mint", Color(0xFF55CFA6), Color(0xFF6EA8FE)),
    Livery("amber", "Amber", Color(0xFFF0B04D), Color(0xFF9AA6EA)),
    Livery("coral", "Coral", Color(0xFFF2845E), Color(0xFF9AA6EA)),
)
private val BOLD_LIVERIES = listOf(
    Livery("tangerine", "Tangerine", Color(0xFFD9480F), Color(0xFF3B4C9B)),
    Livery("cobalt", "Cobalt", Color(0xFF2347C5), Color(0xFFD9480F)),
    Livery("pine", "Pine", Color(0xFF14684A), Color(0xFFB07A1F)),
    Livery("berry", "Berry", Color(0xFFA92458), Color(0xFF3B4C9B)),
)
private val MONO_LIVERIES = listOf(
    Livery("signal", "Signal red", Color(0xFFD7261E), Color(0xFF6B6B6B)),
    Livery("klein", "Klein blue", Color(0xFF1F45C8), Color(0xFF6B6B6B)),
    Livery("moss", "Moss", Color(0xFF2E7040), Color(0xFF6B6B6B)),
    Livery("ink", "Ink only", Color(0xFF404040), Color(0xFF8A8A8A)),
)

private val HACKER_LIVERIES = listOf(
    Livery("phosphor", "Phosphor green", Color(0xFF3DDC6A), Color(0xFFB6F36A)),
    Livery("amber", "Amber screen", Color(0xFFF2B134), Color(0xFFE8774A)),
    Livery("ice", "Ice", Color(0xFF4AD6C8), Color(0xFF8AB4F8)),
    Livery("red", "Red alert", Color(0xFFF0625A), Color(0xFFF2B134)),
)
private val TERMINAL_LIVERIES = listOf(
    Livery("amber", "Amber", Color(0xFFE5A54B), Color(0xFF61AFEF)),
    Livery("green", "Green", Color(0xFF98C379), Color(0xFFE5A54B)),
    Livery("blue", "Blue", Color(0xFF61AFEF), Color(0xFF98C379)),
    Livery("rose", "Rose", Color(0xFFE06C75), Color(0xFFC678DD)),
)

/** The command-line styles, which write headings like prompts. */
val UiStyle.prompt: String get() = when (this) { UiStyle.HACKER -> "> "; UiStyle.TERMINAL -> "$ "; else -> "" }

/** The OpenBand design system and its variants share one layout; they differ in surfaces, type and the hero card. */
val UiStyle.studio: Boolean get() = this == UiStyle.STUDIO || this == UiStyle.MIDNIGHT || this == UiStyle.BOLD || this == UiStyle.MONO || this == UiStyle.HACKER || this == UiStyle.TERMINAL

fun liveriesFor(style: UiStyle): List<Livery> = when (style) {
    UiStyle.RACING -> RACING_LIVERIES
    UiStyle.CYBER -> CYBER_LIVERIES
    UiStyle.RINGS -> RINGS_LIVERIES
    UiStyle.CLEAN -> CLEAN_LIVERIES
    UiStyle.PAPER -> PAPER_LIVERIES
    UiStyle.STUDIO -> STUDIO_LIVERIES
    UiStyle.MIDNIGHT -> MIDNIGHT_LIVERIES
    UiStyle.BOLD -> BOLD_LIVERIES
    UiStyle.MONO -> MONO_LIVERIES
    UiStyle.HACKER -> HACKER_LIVERIES
    UiStyle.TERMINAL -> TERMINAL_LIVERIES
}

/** Extra colours offered in the custom colour picker. */
val PALETTE = listOf(
    0xFFE10600, 0xFFFF3B30, 0xFFFF8000, 0xFFFFC906, 0xFFCEDC00, 0xFF39FF14, 0xFF16EC06, 0xFF229971,
    0xFF00D2BE, 0xFF05D9E8, 0xFF2BD9FE, 0xFF3671C6, 0xFF5B5BD6, 0xFF7B61FF, 0xFFA020F0, 0xFFF706CF,
    0xFFFF2A6D, 0xFFFF5C8A, 0xFFD4AF37, 0xFFFFFFFF,
).map { Color(it) }

/** Dashboard metrics the user can show, hide and reorder. */
const val INST_SLEEP = "sleep"
val ALL_INSTRUMENTS = listOf(
    M.HR, M.STEPS, M.CALORIES, M.DISTANCE, M.SPO2, M.HRV, M.TEMP, M.STRESS,
    M.RESTING_HR, INST_SLEEP, M.BATTERY, M.TOTAL_CAL, M.RESP, M.WEIGHT,
)

fun instrumentLabel(id: String) = when (id) {
    M.BATTERY -> "Band battery"
    INST_SLEEP -> "Sleep"
    M.RESP -> "Breathing rate"
    else -> M.label(id)
}

data class RacePrefs(
    val uiStyle: UiStyle = UiStyle.STUDIO,
    val livery: String = "vermilion",
    /** 0 = follow the phone, 1 = light, 2 = dark (OpenBand look only). */
    val appearance: Int = 0,
    val customPrimary: Long? = null,
    val customSecondary: Long? = null,
    val gaugeStyle: GaugeStyle = GaugeStyle.ANALOG,
    val font: FontStyle = FontStyle.AUTO,
    val shiftLights: Boolean = true,
    val carbon: Boolean = true,
    val scanlines: Boolean = true,
    val lightMode: Boolean = false,
    val oled: Boolean = false,
    val animate: Boolean = true,
    val showTicks: Boolean = true,
    val hideEmpty: Boolean = true,
    val imperial: Boolean = false,
    val fahrenheit: Boolean = false,
    val stepGoal: Int = 10000,
    val calGoal: Int = 500,
    val sleepGoalMin: Int = 480,
    val age: Int = 25,
    val weightKg: Int = 70,
    val heightCm: Int = 170,
    /** 0 not set, 1 male, 2 female - only used for resting-energy and workout calorie formulas. */
    val sex: Int = 0,
    val liveNotif: Boolean = true,
    val celebrate: Boolean = true,
    val recap: Boolean = true,
    val moveNudge: Boolean = false,
    val bgBand: Boolean = false,
    /** false = show activity calories like the Pebble app, true = include resting energy (Whoop / Noise style). */
    val calTotal: Boolean = false,
    /** Bold look: the step card fills like a glass of liquid. */
    val boldLiquid: Boolean = true,
    /** Minutes between the band's automatic heart-rate readings; 0 = leave the band's setting alone. */
    val hrInterval: Int = 0,
    /** Notice walks, runs and rides that were not started in the app and ask about them. */
    val autoDetect: Boolean = false,
    /** Sunday-night summary notification. */
    val weeklyRecap: Boolean = true,
    val mapSatellite: Boolean = true,
    val driverName: String = "",
    val carNumber: String = "07",
    val layout: List<String> = listOf(M.HR, M.STEPS, INST_SLEEP, M.SPO2, M.HRV, M.STRESS, M.TEMP, M.RESP, M.CALORIES, M.DISTANCE, M.RESTING_HR, M.BATTERY),
) {
    val maxHr get() = (220 - age).coerceIn(150, 220)

    val liveryObj: Livery get() = liveriesFor(uiStyle).let { l -> l.firstOrNull { it.id == livery } ?: l[0] }
    val primary: Color get() = customPrimary?.let { Color(it.toInt()) } ?: liveryObj.primary
    val secondary: Color get() = customSecondary?.let { Color(it.toInt()) } ?: liveryObj.secondary

    /** Name to greet the user with; empty means not set yet. */
    val displayName: String get() = driverName.trim()
}

/** Persisted user customisation. */
object RaceSettings {
    val prefs = MutableStateFlow(RacePrefs())
    private var ctx: Context? = null

    private fun sp(c: Context) = c.getSharedPreferences("race_ui", Context.MODE_PRIVATE)

    private inline fun <reified E : Enum<E>> enumOf(v: String?, d: E): E = runCatching { enumValueOf<E>(v!!) }.getOrDefault(d)

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = sp(c)
        // everyone moves to the new OpenBand look once; afterwards the choice is theirs
        if (!p.getBoolean("studio_v1", false)) p.edit().putString("style", UiStyle.STUDIO.name).putString("livery", "vermilion").remove("cp").remove("cs").putBoolean("studio_v1", true).apply()
        val d = RacePrefs()
        val savedDriver = p.getString("driver", d.driverName)!!
        prefs.value = RacePrefs(
            uiStyle = enumOf(p.getString("style", null), d.uiStyle),
            livery = p.getString("livery", d.livery)!!,
            appearance = p.getInt("appearance", d.appearance),
            customPrimary = if (p.contains("cp")) p.getLong("cp", 0) else null,
            customSecondary = if (p.contains("cs")) p.getLong("cs", 0) else null,
            gaugeStyle = enumOf(p.getString("gauge", null), d.gaugeStyle),
            font = enumOf(p.getString("font", null), d.font),
            shiftLights = p.getBoolean("shift", d.shiftLights),
            carbon = p.getBoolean("carbon", d.carbon),
            scanlines = p.getBoolean("scanlines", d.scanlines),
            lightMode = p.getBoolean("light", d.lightMode),
            oled = p.getBoolean("oled", d.oled),
            animate = p.getBoolean("animate", d.animate),
            showTicks = p.getBoolean("ticks", d.showTicks),
            hideEmpty = p.getBoolean("hideEmpty", d.hideEmpty),
            imperial = p.getBoolean("imperial", d.imperial),
            fahrenheit = p.getBoolean("fahrenheit", d.fahrenheit),
            stepGoal = p.getInt("stepGoal", d.stepGoal),
            calGoal = p.getInt("calGoal", d.calGoal),
            sleepGoalMin = p.getInt("sleepGoal", d.sleepGoalMin),
            age = p.getInt("age", d.age),
            weightKg = p.getInt("weightKg", d.weightKg),
            heightCm = p.getInt("heightCm", d.heightCm),
            sex = p.getInt("sex", d.sex),
            liveNotif = p.getBoolean("liveNotif", d.liveNotif),
            celebrate = p.getBoolean("celebrate", d.celebrate),
            recap = p.getBoolean("recap", d.recap),
            moveNudge = p.getBoolean("moveNudge", d.moveNudge),
            bgBand = p.getBoolean("bgBand", d.bgBand),
            calTotal = p.getBoolean("calTotal", d.calTotal),
            boldLiquid = p.getBoolean("boldLiquid", d.boldLiquid),
            hrInterval = p.getInt("hrInterval", d.hrInterval),
            autoDetect = p.getBoolean("autoDetect", d.autoDetect),
            weeklyRecap = p.getBoolean("weeklyRecap", d.weeklyRecap),
            mapSatellite = p.getBoolean("mapSat", d.mapSatellite),
            // v2 default was the placeholder "DRIVER"; treat it as "no name yet"
            driverName = if (savedDriver == "DRIVER") "" else savedDriver,
            carNumber = p.getString("number", d.carNumber)!!,
            layout = p.getString("layout2", null)?.split(",")?.filter { it in ALL_INSTRUMENTS }?.takeIf { it.isNotEmpty() } ?: d.layout,
        )
    }

    fun update(f: (RacePrefs) -> RacePrefs) {
        prefs.update(f)
        val v = prefs.value
        val c = ctx ?: return
        runCatching { com.openband.app.widget.WidgetUpdater.request(c) }
        sp(c).edit().apply {
            putString("style", v.uiStyle.name)
            putString("livery", v.livery)
            putInt("appearance", v.appearance)
            if (v.customPrimary != null) putLong("cp", v.customPrimary) else remove("cp")
            if (v.customSecondary != null) putLong("cs", v.customSecondary) else remove("cs")
            putString("gauge", v.gaugeStyle.name)
            putString("font", v.font.name)
            putBoolean("shift", v.shiftLights)
            putBoolean("carbon", v.carbon)
            putBoolean("scanlines", v.scanlines)
            putBoolean("light", v.lightMode)
            putBoolean("oled", v.oled)
            putBoolean("animate", v.animate)
            putBoolean("ticks", v.showTicks)
            putBoolean("hideEmpty", v.hideEmpty)
            putBoolean("imperial", v.imperial)
            putBoolean("fahrenheit", v.fahrenheit)
            putInt("stepGoal", v.stepGoal)
            putInt("calGoal", v.calGoal)
            putInt("sleepGoal", v.sleepGoalMin)
            putInt("age", v.age)
            putInt("weightKg", v.weightKg)
            putInt("heightCm", v.heightCm)
            putInt("sex", v.sex)
            putBoolean("liveNotif", v.liveNotif)
            putBoolean("celebrate", v.celebrate)
            putBoolean("recap", v.recap)
            putBoolean("moveNudge", v.moveNudge)
            putBoolean("bgBand", v.bgBand)
            putBoolean("calTotal", v.calTotal)
            putBoolean("boldLiquid", v.boldLiquid)
            putInt("hrInterval", v.hrInterval)
            putBoolean("autoDetect", v.autoDetect)
            putBoolean("weeklyRecap", v.weeklyRecap)
            putBoolean("mapSat", v.mapSatellite)
            putString("driver", v.driverName)
            putString("number", v.carNumber)
            putString("layout2", v.layout.joinToString(","))
        }.apply()
    }

    /** Switches style and resets colours to that style's first palette. */
    fun setStyle(s: UiStyle) = update { it.copy(uiStyle = s, livery = liveriesFor(s)[0].id, customPrimary = null, customSecondary = null) }

    fun reset() = update { RacePrefs(uiStyle = it.uiStyle, livery = liveriesFor(it.uiStyle)[0].id, driverName = it.driverName) }
}

/** Packs a Compose colour into a Long for storage (inverse of RacePrefs.primary). */
fun Color.toStored(): Long = toArgb().toLong()
