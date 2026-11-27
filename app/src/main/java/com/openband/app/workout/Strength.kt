package com.openband.app.workout

import org.json.JSONArray
import org.json.JSONObject

/** Lifting: the exercise list and the arithmetic done on logged sets. */
object Strength {

    /**
     * [bodyShare] = how much of the body's weight is lifted in a bodyweight movement (0 for bar and machine work, where
     * only the entered weight counts).
     */
    class Exercise(
        val id: String, val name: String, val compound: Boolean, val primary: List<String>, val secondary: List<String> = emptyList(),
        val bodyShare: Double = 0.0,
    )

    val all: List<Exercise> = listOf(
        Exercise("squat", "Back squat", true, listOf("Quads", "Glutes"), listOf("Hamstrings", "Core")),
        Exercise("front_squat", "Front squat", true, listOf("Quads"), listOf("Glutes", "Core")),
        Exercise("deadlift", "Deadlift", true, listOf("Hamstrings", "Glutes", "Back"), listOf("Core", "Forearms")),
        Exercise("rdl", "Romanian deadlift", true, listOf("Hamstrings", "Glutes"), listOf("Back")),
        Exercise("bench", "Bench press", true, listOf("Chest"), listOf("Triceps", "Shoulders")),
        Exercise("incline", "Incline bench press", true, listOf("Chest", "Shoulders"), listOf("Triceps")),
        Exercise("ohp", "Overhead press", true, listOf("Shoulders"), listOf("Triceps", "Core")),
        Exercise("row", "Barbell row", true, listOf("Back"), listOf("Biceps", "Forearms")),
        Exercise("pullup", "Pull-up", true, listOf("Back"), listOf("Biceps", "Core"), bodyShare = 1.0),
        Exercise("chinup", "Chin-up", true, listOf("Back", "Biceps"), listOf("Core"), bodyShare = 1.0),
        Exercise("dip", "Dip", true, listOf("Triceps", "Chest"), listOf("Shoulders"), bodyShare = 0.95),
        Exercise("pushup", "Push-up", true, listOf("Chest"), listOf("Triceps", "Shoulders", "Core"), bodyShare = 0.64),
        Exercise("bw_squat", "Bodyweight squat", true, listOf("Quads", "Glutes"), listOf("Core"), bodyShare = 0.7),
        Exercise("lunge", "Lunge", true, listOf("Quads", "Glutes"), listOf("Hamstrings")),
        Exercise("split_squat", "Bulgarian split squat", true, listOf("Quads", "Glutes"), listOf("Core")),
        Exercise("hip_thrust", "Hip thrust", true, listOf("Glutes"), listOf("Hamstrings")),
        Exercise("leg_press", "Leg press", true, listOf("Quads", "Glutes"), listOf("Hamstrings")),
        Exercise("clean_press", "Clean and press", true, listOf("Shoulders", "Glutes"), listOf("Back", "Quads", "Core")),
        Exercise("thruster", "Thruster", true, listOf("Quads", "Shoulders"), listOf("Glutes", "Triceps")),
        Exercise("lat_pull", "Lat pulldown", true, listOf("Back"), listOf("Biceps")),
        Exercise("cable_row", "Seated cable row", true, listOf("Back"), listOf("Biceps")),
        Exercise("curl", "Biceps curl", false, listOf("Biceps"), listOf("Forearms")),
        Exercise("tri_ext", "Triceps extension", false, listOf("Triceps")),
        Exercise("lat_raise", "Lateral raise", false, listOf("Shoulders")),
        Exercise("fly", "Chest fly", false, listOf("Chest")),
        Exercise("leg_curl", "Leg curl", false, listOf("Hamstrings")),
        Exercise("leg_ext", "Leg extension", false, listOf("Quads")),
        Exercise("calf", "Calf raise", false, listOf("Calves")),
        Exercise("crunch", "Crunch", false, listOf("Core"), bodyShare = 0.3),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: Exercise(id, id, false, emptyList())

    /** [weight] is what was added (bar, plates, dumbbells, belt); [bodyKg] the body weight on that day. */
    data class SetEntry(val exercise: String, val weight: Double, val reps: Int, val atSec: Int, val bodyKg: Double) {
        val load: Double get() = weight + byId(exercise).bodyShare * bodyKg
        val volume: Double get() = load * reps
        /** Estimated one-rep maximum (Epley), only trustworthy up to 12 repetitions. */
        val oneRepMax: Double? get() = when {
            load <= 0 || reps < 1 || reps > 12 -> null
            reps == 1 -> load
            else -> load * (1 + reps / 30.0)
        }
    }

    fun volume(sets: List<SetEntry>) = sets.sumOf { it.volume }

    fun best(sets: List<SetEntry>, exercise: String): Double? = sets.filter { it.exercise == exercise }.mapNotNull { it.oneRepMax }.maxOrNull()

    /** Hard sets per muscle: a set counts 1 for its main muscles and half for helpers. */
    fun muscleSets(sets: List<SetEntry>): List<Pair<String, Double>> {
        val m = HashMap<String, Double>()
        for (s in sets) {
            val e = byId(s.exercise)
            e.primary.forEach { m[it] = (m[it] ?: 0.0) + 1.0 }
            e.secondary.forEach { m[it] = (m[it] ?: 0.0) + 0.5 }
        }
        return m.toList().sortedByDescending { it.second }
    }

    fun toJson(sets: List<SetEntry>): JSONArray = JSONArray().also { a ->
        sets.forEach { a.put(JSONObject().put("e", it.exercise).put("w", it.weight).put("r", it.reps).put("t", it.atSec).put("bw", it.bodyKg)) }
    }

    fun fromJson(a: JSONArray?): List<SetEntry> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        a.optJSONObject(i)?.let { SetEntry(it.optString("e"), it.optDouble("w", 0.0), it.optInt("r"), it.optInt("t"), it.optDouble("bw", 0.0)) }
    }
}
