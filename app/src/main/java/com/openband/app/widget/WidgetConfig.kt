package com.openband.app.widget

import android.content.Context

/** How one placed widget looks. Every field has a sensible default, so a widget works without ever being configured. */
data class WidgetConfig(
    /** What it shows; any entry of the catalogue. */
    val kind: String,
    /** 0 = same as the app, 1 = always light, 2 = always dark. */
    val theme: Int = 0,
    /** Background opacity in %. */
    val opacity: Int = 100,
    /** 0 = the metric's own colour, otherwise an ARGB colour. */
    val accent: Int = 0,
    /** Which readings the "All vitals" widget lists, in order. */
    val tiles: List<String> = VITAL_TILES,
)

val VITAL_TILES = listOf("hr", "spo2", "hrv", "stress", "temp", "battery")

object WidgetConfigs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun load(ctx: Context, id: Int, defaultKind: String): WidgetConfig {
        val p = sp(ctx)
        return WidgetConfig(
            kind = p.getString("w$id.kind", null) ?: defaultKind,
            theme = p.getInt("w$id.theme", 0),
            opacity = p.getInt("w$id.opacity", 100),
            accent = p.getInt("w$id.accent", 0),
            tiles = p.getString("w$id.tiles", null)?.split(",")?.filter { it in VITAL_TILES }?.takeIf { it.isNotEmpty() } ?: VITAL_TILES,
        )
    }

    fun save(ctx: Context, id: Int, c: WidgetConfig) = sp(ctx).edit()
        .putString("w$id.kind", c.kind).putInt("w$id.theme", c.theme).putInt("w$id.opacity", c.opacity)
        .putInt("w$id.accent", c.accent).putString("w$id.tiles", c.tiles.joinToString(",")).apply()

    fun delete(ctx: Context, id: Int) {
        val e = sp(ctx).edit()
        listOf("kind", "theme", "opacity", "accent", "tiles").forEach { e.remove("w$id.$it") }
        e.apply()
    }
}
