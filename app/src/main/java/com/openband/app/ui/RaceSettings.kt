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

fun liveriesFor(style: UiStyle): List<Livery> = when (style) {
    UiStyle.RACING -> RACING_LIVERIES
    UiStyle.CYBER -> CYBER_LIVERIES
    UiStyle.RINGS -> RINGS_LIVERIES
    UiStyle.CLEAN -> CLEAN_LIVERIES
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
    val uiStyle: UiStyle = UiStyle.RACING,
    val livery: String = "rosso",
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
        val d = RacePrefs()
        val savedDriver = p.getString("driver", d.driverName)!!
        prefs.value = RacePrefs(
            uiStyle = enumOf(p.getString("style", null), d.uiStyle),
            livery = p.getString("livery", d.livery)!!,
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
        sp(c).edit().apply {
            putString("style", v.uiStyle.name)
            putString("livery", v.livery)
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
