package com.openband.app.data

/** All metric types the app stores. Values are always in the unit given in [M.info]. */
object M {
    const val STEPS = "steps"
    const val CALORIES = "active_kcal"
    const val TOTAL_CAL = "total_kcal"
    const val DISTANCE = "distance_m"
    const val HR = "heart_rate"
    const val RESTING_HR = "resting_hr"
    const val SPO2 = "spo2"
    const val HRV = "hrv_ms"
    const val TEMP = "body_temp"
    const val STRESS = "stress"
    const val RESP = "resp_rate"
    const val BP_SYS = "bp_sys"
    const val BP_DIA = "bp_dia"
    const val WEIGHT = "weight"
    const val VO2 = "vo2max"
    const val EXERCISE = "exercise_min"
    const val SLEEP_DEEP = "sleep_deep"
    const val SLEEP_LIGHT = "sleep_light"
    const val SLEEP_REM = "sleep_rem"
    const val SLEEP_AWAKE = "sleep_awake"
    const val SLEEP_ASLEEP = "sleep_asleep"
    const val BATTERY = "battery"
    /** Heart-rate recovery test: beats dropped after 1 and 2 minutes, and the rate it started from. */
    const val HRR1 = "hrr_1min"
    const val HRR2 = "hrr_2min"
    const val HRR_START = "hrr_start"

    enum class Agg { SUM, AVG, LATEST }

    data class Info(val label: String, val unit: String, val agg: Agg, val decimals: Int = 0)

    val info: Map<String, Info> = linkedMapOf(
        STEPS to Info("Steps", "", Agg.SUM),
        CALORIES to Info("Active calories", "kcal", Agg.SUM),
        TOTAL_CAL to Info("Total calories", "kcal", Agg.SUM),
        DISTANCE to Info("Distance", "km", Agg.SUM, 2),
        HR to Info("Heart rate", "bpm", Agg.AVG),
        RESTING_HR to Info("Resting HR", "bpm", Agg.LATEST),
        SPO2 to Info("SpO2", "%", Agg.AVG),
        HRV to Info("HRV (RMSSD)", "ms", Agg.AVG),
        TEMP to Info("Temperature", "°C", Agg.AVG, 1),
        STRESS to Info("Stress", "", Agg.AVG),
        RESP to Info("Respiratory rate", "/min", Agg.AVG, 1),
        BP_SYS to Info("BP systolic", "mmHg", Agg.LATEST),
        BP_DIA to Info("BP diastolic", "mmHg", Agg.LATEST),
        WEIGHT to Info("Weight", "kg", Agg.LATEST, 1),
        VO2 to Info("VO2 max", "ml/kg/min", Agg.LATEST, 1),
        EXERCISE to Info("Exercise", "min", Agg.SUM),
        SLEEP_DEEP to Info("Deep sleep", "min", Agg.SUM),
        SLEEP_LIGHT to Info("Light sleep", "min", Agg.SUM),
        SLEEP_REM to Info("REM sleep", "min", Agg.SUM),
        SLEEP_AWAKE to Info("Awake", "min", Agg.SUM),
        SLEEP_ASLEEP to Info("Sleep (unstaged)", "min", Agg.SUM),
        BATTERY to Info("Band battery", "%", Agg.LATEST),
        HRR1 to Info("Heart recovery (1 min)", "bpm", Agg.LATEST),
        HRR2 to Info("Heart recovery (2 min)", "bpm", Agg.LATEST),
        HRR_START to Info("Heart rate at test start", "bpm", Agg.LATEST),
    )

    val sleepTypes = listOf(SLEEP_DEEP, SLEEP_LIGHT, SLEEP_REM, SLEEP_ASLEEP)

    fun label(t: String) = info[t]?.label ?: t

    /** Converts a stored value to its display value (distance stored in m, shown in km). */
    fun display(t: String, v: Double): Double = if (t == DISTANCE) v / 1000.0 else v

    fun format(t: String, v: Double?): String {
        if (v == null) return "--"
        val i = info[t]
        val d = display(t, v)
        val s = if ((i?.decimals ?: 0) == 0) "%,d".format(Math.round(d)) else "%.${i!!.decimals}f".format(d)
        return if (i?.unit.isNullOrEmpty()) s else "$s ${i!!.unit}"
    }
}
