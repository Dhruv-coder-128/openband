package com.openband.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.openband.app.data.M

/**
 * The colour of each kind of measurement. Muted, desaturated and tuned separately for light and dark surfaces so text and
 * graphs stay readable (contrast >= 3:1 on both). Each hue is used for one thing only, everywhere in the app.
 */
object Palette {
    fun metric(id: String, dark: Boolean): Color = when (id) {
        M.HR, M.RESTING_HR -> if (dark) Color(0xFFF0697C) else Color(0xFFC83A52)   // rose
        M.STEPS, M.EXERCISE -> if (dark) Color(0xFFF2845E) else Color(0xFFD4501F)  // vermilion
        M.CALORIES, M.TOTAL_CAL -> if (dark) Color(0xFFF0B04D) else Color(0xFFC77F12) // amber
        M.DISTANCE -> if (dark) Color(0xFF7FAEE8) else Color(0xFF3569B5)           // steel blue
        INST_SLEEP, M.SLEEP_DEEP, M.SLEEP_LIGHT, M.SLEEP_REM -> if (dark) Color(0xFF9AA6EA) else Color(0xFF4A58A8) // slate indigo
        M.SPO2 -> if (dark) Color(0xFF63B7DF) else Color(0xFF1F7FAE)               // sea blue
        M.HRV -> if (dark) Color(0xFFB69AE0) else Color(0xFF7655AE)                // muted violet
        M.STRESS -> if (dark) Color(0xFFE3B85B) else Color(0xFF9E7010)             // ochre
        M.TEMP -> if (dark) Color(0xFFEE9459) else Color(0xFFC0611F)               // copper
        M.BATTERY -> if (dark) Color(0xFF5CC38E) else Color(0xFF23855A)            // green
        else -> if (dark) Color(0xFFA2A6AD) else Color(0xFF5D6066)
    }

    fun icon(id: String): ImageVector = when (id) {
        M.HR, M.RESTING_HR -> AppIcons.Heart
        M.STEPS, M.EXERCISE -> AppIcons.Steps
        M.CALORIES, M.TOTAL_CAL -> AppIcons.Flame
        M.DISTANCE -> AppIcons.Mountain
        INST_SLEEP -> AppIcons.Moon
        M.SPO2 -> AppIcons.Drop
        M.HRV -> AppIcons.Wave
        M.STRESS -> AppIcons.Gauge
        M.TEMP -> AppIcons.Thermometer
        M.BATTERY -> AppIcons.Battery
        else -> AppIcons.Activity
    }

    /** Plain-language reading of a value, using the commonly used ranges. tone: 0 good, 1 note, 2 attention, -1 neutral. */
    fun status(id: String, v: Double?, baseline: Double? = null): Pair<String, Int>? {
        if (v == null) return null
        return when (id) {
            M.SPO2 -> when { v >= 95 -> "Normal" to 0; v >= 90 -> "Slightly low" to 1; else -> "Low" to 2 }
            M.STRESS -> when { v < 30 -> "Relaxed" to 0; v < 60 -> "Normal" to 0; v < 80 -> "Medium" to 1; else -> "High" to 2 }
            M.TEMP -> when { v < 35.0 -> "Cool wrist" to -1; v <= 37.6 -> "Normal" to 0; else -> "Warm" to 1 }
            M.HRV -> if (baseline != null && baseline > 0) {
                val d = (v - baseline) / baseline * 100
                when { d >= 8 -> "Above average" to 0; d <= -8 -> "Below average" to 1; else -> "Usual for you" to 0 }
            } else null
            M.HR -> when { v < 60 -> "Resting" to -1; v < 100 -> "Normal" to 0; v < 140 -> "Active" to -1; else -> "High" to 1 }
            M.BATTERY -> when { v >= 40 -> "Good" to 0; v >= 20 -> "Charge soon" to 1; else -> "Low" to 2 }
            else -> null
        }
    }
}
