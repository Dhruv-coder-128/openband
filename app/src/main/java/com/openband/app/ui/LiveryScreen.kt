package com.openband.app.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.data.Stats
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.FontStyle as TFontStyle

/** Theme, colours, fonts and effects. */
@Composable
fun AppearanceContent() {
    val look = LocalRace.current
    val p = look.prefs
    Column {
        // ---------------------------------------------------------------- style picker
        SectionCard("App style") {
            Text("Changes colours, fonts, layout and screen names.", style = MaterialTheme.typography.bodySmall, color = look.dim)
            Spacer(Modifier.height(8.dp))
            UiStyle.entries.sortedByDescending { it.studio }.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { st -> StyleCard(st, st == p.uiStyle, Modifier.weight(1f)) { RaceSettings.setStyle(st) } }
                }
            }
        }


        // ---------------------------------------------------------------- colours
        SectionCard(if (p.uiStyle.studio) "Accent colour" else "Colours") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                liveriesFor(p.uiStyle).forEach { l ->
                    val sel = p.livery == l.id && p.customPrimary == null && p.customSecondary == null
                    LiveryChip(l, sel) { RaceSettings.update { it.copy(livery = l.id, customPrimary = null, customSecondary = null) } }
                }
            }
            if (!p.uiStyle.studio) Column {
            Spacer(Modifier.height(12.dp))
            Label("Custom main colour")
            ColorRow(p.primary) { c -> RaceSettings.update { it.copy(customPrimary = c.toStored()) } }
            Spacer(Modifier.height(8.dp))
            Label("Custom accent")
            ColorRow(p.secondary) { c -> RaceSettings.update { it.copy(customSecondary = c.toStored()) } }
            }
        }


        // ---------------------------------------------------------------- style-specific options
        SectionCard("Look & feel") {
            if (p.uiStyle == UiStyle.HACKER || p.uiStyle == UiStyle.TERMINAL) {
                Text("This look is always dark.", style = MaterialTheme.typography.bodySmall, color = look.dim)
            } else if (p.uiStyle == UiStyle.MIDNIGHT) {
                Text("Midnight is always true black.", style = MaterialTheme.typography.bodySmall, color = look.dim)
            } else if (p.uiStyle.studio) {
                if (p.uiStyle == UiStyle.BOLD) {
                    Toggle("Liquid step card", p.boldLiquid) { v -> RaceSettings.update { it.copy(boldLiquid = v) } }
                    Text(
                        "The step card fills as you walk, leans with the phone and splashes when you tap it. Switch off for the plain card.",
                        style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Label("Appearance")
                Choice(listOf("Auto", "Light", "Dark"), p.appearance) { i -> RaceSettings.update { it.copy(appearance = i) } }
                Text("Auto follows your phone's light / dark setting.", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 4.dp))
            } else when (p.uiStyle.family) {
                UiStyle.RACING -> {
                    Label("Gauge style")
                    Choice(GaugeStyle.entries.map { it.label }, p.gaugeStyle.ordinal) { i -> RaceSettings.update { it.copy(gaugeStyle = GaugeStyle.entries[i]) } }
                    Toggle("F1 shift lights", p.shiftLights) { v -> RaceSettings.update { it.copy(shiftLights = v) } }
                    Toggle("Gauge tick marks", p.showTicks) { v -> RaceSettings.update { it.copy(showTicks = v) } }
                    Toggle("Carbon-fibre background", p.carbon) { v -> RaceSettings.update { it.copy(carbon = v) } }
                }
                UiStyle.CYBER -> Toggle("Scanlines", p.scanlines) { v -> RaceSettings.update { it.copy(scanlines = v) } }
                UiStyle.CLEAN -> if (p.uiStyle == UiStyle.CLEAN) Toggle("Light mode", p.lightMode) { v -> RaceSettings.update { it.copy(lightMode = v) } }
                else Text("This look is always light and warm.", style = MaterialTheme.typography.bodySmall, color = look.dim)
                else -> Text(
                    "Three rings: sleep, steps and calories against your goals (set them under Goals & units).",
                    style = MaterialTheme.typography.bodySmall, color = look.dim,
                )
            }
            Spacer(Modifier.height(6.dp))
            Spacer(Modifier.height(4.dp))
            AppDropdown(FontStyle.entries.map { it.label }, p.font.ordinal, { i -> RaceSettings.update { it.copy(font = FontStyle.entries[i]) } }, label = "Font")
            Spacer(Modifier.height(8.dp))
            Toggle("Animations", p.animate) { v -> RaceSettings.update { it.copy(animate = v) } }
            if (!p.uiStyle.studio && p.uiStyle.family != UiStyle.RINGS && !(p.uiStyle == UiStyle.CLEAN && p.lightMode) && p.uiStyle != UiStyle.PAPER)
                Toggle("Pure black (saves battery)", p.oled) { v -> RaceSettings.update { it.copy(oled = v) } }
        }


        SectionCard(null) {
            RaceButton("Reset this style to default", Modifier.fillMaxWidth(), primary = false) { RaceSettings.reset() }
        }

    }
}

/** Which metrics show on the dashboard, and in what order. */
@Composable
fun DashboardContent() {
    val look = LocalRace.current
    val p = look.prefs
    Column {
        // ---------------------------------------------------------------- dashboard layout
        SectionCard("Dashboard") {
            Toggle("Hide metrics without data", p.hideEmpty) { v -> RaceSettings.update { it.copy(hideEmpty = v) } }
            Text(
                "Turn metrics on or off and move them up or down." + if (p.uiStyle.family == UiStyle.RACING) " The top two become the big gauges." else "",
                style = MaterialTheme.typography.bodySmall, color = look.dim,
            )
            Spacer(Modifier.height(6.dp))
            val ordered = p.layout + ALL_INSTRUMENTS.filter { it !in p.layout }
            ordered.forEach { id ->
                val on = id in p.layout
                val idx = p.layout.indexOf(id)
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(instrumentLabel(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (on) look.text else look.dim)
                    if (on) {
                        SmallKey("▲", idx > 0) { RaceSettings.update { it.copy(layout = it.layout.toMutableList().apply { add(idx - 1, removeAt(idx)) }) } }
                        SmallKey("▼", idx < p.layout.size - 1) { RaceSettings.update { it.copy(layout = it.layout.toMutableList().apply { add(idx + 1, removeAt(idx)) }) } }
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { v -> RaceSettings.update { it.copy(layout = if (v) it.layout + id else (it.layout - id).ifEmpty { listOf(id) }) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = look.primary),
                    )
                }
            }
        }


    }
}

/** Daily goals and units. */
@Composable
fun GoalsContent() {
    val look = LocalRace.current
    val p = look.prefs
    Column {
        // ---------------------------------------------------------------- goals & units
        SectionCard("Goals") {
            Slide("Steps", p.stepGoal.toFloat(), 2000f..30000f, "%,d".format(p.stepGoal)) { v -> RaceSettings.update { it.copy(stepGoal = (v / 500).roundToInt() * 500) } }
            Text("Calories shown", style = MaterialTheme.typography.bodyMedium, color = look.text, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            Choice(listOf("Like Pebble", "Like Whoop"), if (p.calTotal) 1 else 0) { i -> RaceSettings.update { it.copy(calTotal = i == 1) } }
            Text(
                if (p.calTotal) "Total energy: your resting burn (from age, height, weight, sex) plus activity. Higher than the Pebble app."
                else "Activity calories only, the same number the Pebble app shows.",
                style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Slide("Active calories", p.calGoal.toFloat(), 100f..2000f, "${p.calGoal} kcal") { v -> RaceSettings.update { it.copy(calGoal = (v / 50).roundToInt() * 50) } }
            Slide("Sleep", p.sleepGoalMin.toFloat(), 300f..660f, Stats.fmtMinutes(p.sleepGoalMin.toDouble())) { v -> RaceSettings.update { it.copy(sleepGoalMin = (v / 15).roundToInt() * 15) } }
        }
        SectionCard("Units") {
            Choice(listOf("km · kg", "mi · lb"), if (p.imperial) 1 else 0) { i -> RaceSettings.update { it.copy(imperial = i == 1) } }
            Spacer(Modifier.height(6.dp))
            Choice(listOf("°C", "°F"), if (p.fahrenheit) 1 else 0) { i -> RaceSettings.update { it.copy(fahrenheit = i == 1) } }
        }


    }
}

/** Name, race number and age - shown on the Profile tab. */
@Composable
fun ProfileFields() {
    val look = LocalRace.current
    val p = look.prefs
    Column {
        // ---------------------------------------------------------------- you
        SectionCard(look.pick("Driver", "You", "OPERATOR")) {
            var name by remember { mutableStateOf(p.driverName) }
            var num by remember { mutableStateOf(p.carNumber) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (look.family == UiStyle.RACING) OutlinedTextField(
                    value = num, onValueChange = { v -> num = v.filter { it.isLetterOrDigit() }.take(3); RaceSettings.update { it.copy(carNumber = num.ifBlank { "0" }) } },
                    label = { Text("No.") }, singleLine = true, modifier = Modifier.width(90.dp),
                )
                OutlinedTextField(
                    value = name, onValueChange = { v -> name = v.take(18); RaceSettings.update { it.copy(driverName = name.trim()) } },
                    label = { Text("Your name") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Slide("Age (sets max heart rate)", p.age.toFloat(), 10f..80f, "${p.age} · max ${p.maxHr} bpm") { v -> RaceSettings.update { it.copy(age = v.roundToInt()) } }
        }


        SectionCard("Body") {
            Slide("Weight (for calories)", p.weightKg.toFloat(), 35f..160f, "${p.weightKg} kg") { v -> RaceSettings.update { it.copy(weightKg = v.roundToInt()) } }
            Slide("Height", p.heightCm.toFloat(), 120f..220f, "${p.heightCm} cm") { v -> RaceSettings.update { it.copy(heightCm = v.roundToInt()) } }
            Text("Sex (for resting calories)", style = MaterialTheme.typography.bodySmall, color = look.dim, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Male", 2 to "Female", 0 to "Not set").forEach { (v, l) ->
                    FilterChip(selected = p.sex == v, onClick = { RaceSettings.update { it.copy(sex = v) } }, label = { Text(l) })
                }
            }
        }
    }
}

/** Logo + version. */
@Composable
fun AboutContent() {
    val look = LocalRace.current
    Column {
        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.openband.app.R.drawable.ic_logo_full), "OpenBand logo",
                    Modifier.size(72.dp),
                )
                Column(Modifier.padding(start = 14.dp)) {
                    Text("OpenBand", fontFamily = look.display, fontWeight = FontWeight.Black, fontSize = 22.sp, color = look.text)
                    Text("Your band. Your data. Your streak.", style = MaterialTheme.typography.bodySmall, color = look.dim)
                    Text("v" + androidx.compose.ui.platform.LocalContext.current.let { c -> runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "" },
                        style = MaterialTheme.typography.labelSmall, color = look.dim)
                }
            }
        }

    }
}

/** Mini preview of a style: its colours, shape and a hint of its dashboard. */
@Composable
private fun StyleCard(st: UiStyle, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val look = LocalRace.current
    val pal = liveriesFor(st)[0]
    val bg: Color
    val fg: Color
    val shape = when (st) {
        UiStyle.RACING, UiStyle.CYBER -> CutCornerShape(topEnd = 14.dp, bottomStart = 14.dp)
        UiStyle.STUDIO -> RoundedCornerShape(16.dp)
        UiStyle.MIDNIGHT -> RoundedCornerShape(20.dp)
        UiStyle.BOLD -> RoundedCornerShape(24.dp)
        UiStyle.MONO -> RoundedCornerShape(4.dp)
        UiStyle.HACKER -> RoundedCornerShape(2.dp)
        UiStyle.TERMINAL -> RoundedCornerShape(10.dp)
        UiStyle.PAPER -> RoundedCornerShape(10.dp)
        else -> RoundedCornerShape(18.dp)
    }
    when (st) {
        UiStyle.RACING -> { bg = Color(0xFF111317); fg = Color.White }
        UiStyle.CYBER -> { bg = Color(0xFF0B0716); fg = Color(0xFFEAF6FF) }
        UiStyle.RINGS -> { bg = Color.Black; fg = Color.White }
        UiStyle.CLEAN -> { bg = Color(0xFFF4F5F7); fg = Color(0xFF15181B) }
        UiStyle.PAPER -> { bg = Color(0xFFF5F1EA); fg = Color(0xFF1B1A17) }
        UiStyle.STUDIO -> { bg = Color(0xFFF4F3EF); fg = Color(0xFF17181A) }
        UiStyle.MIDNIGHT -> { bg = Color.Black; fg = Color(0xFFE9EAEE) }
        UiStyle.BOLD -> { bg = Color(0xFFF4F3EF); fg = Color(0xFF17181A) }
        UiStyle.MONO -> { bg = Color.White; fg = Color(0xFF0A0A0A) }
        UiStyle.HACKER -> { bg = Color(0xFF030603); fg = Color(0xFF7DF29A) }
        UiStyle.TERMINAL -> { bg = Color(0xFF1E2127); fg = Color(0xFFE6E1CF) }
    }
    Column(
        modifier.clip(shape).background(bg).border(if (selected) 2.5.dp else 1.dp, if (selected) look.primary else Color(0xFF3A3F48), shape)
            .clickable(onClick = onClick).padding(12.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().height(54.dp)) {
            val h = size.height
            when (st) {
                UiStyle.RACING -> {
                    val r = h / 2.1f
                    for (k in 0..1) {
                        val c = Offset(size.width * (0.27f + 0.46f * k), h / 2)
                        drawCircle(Color(0xFF3A3F48), r, c, style = Stroke(3f))
                        drawArc(pal.primary, 135f, 170f + 40f * k, false, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = Stroke(4f))
                    }
                }
                UiStyle.CYBER -> {
                    for (i in 0..6) drawLine(pal.secondary.copy(alpha = 0.35f), Offset(0f, h * 0.55f + i * 4f), Offset(size.width, h * 0.55f + i * 4f), 1f)
                    var x = 0f
                    var up = true
                    while (x < size.width) {
                        drawLine(pal.primary, Offset(x, if (up) h * 0.5f else h * 0.2f), Offset(x + 8f, if (up) h * 0.2f else h * 0.5f), 3f)
                        x += 8f; up = !up
                    }
                }
                UiStyle.RINGS -> {
                    val cols = listOf(Color(0xFF16EC06), pal.secondary, pal.primary)
                    val r = h / 2.4f
                    cols.forEachIndexed { k, c ->
                        val cx = size.width * (0.18f + 0.32f * k)
                        drawCircle(c.copy(alpha = 0.2f), r, Offset(cx, h / 2), style = Stroke(6f))
                        drawArc(c, -90f, 120f + 70f * k, false, Offset(cx - r, h / 2 - r), Size(r * 2, r * 2), style = Stroke(6f))
                    }
                }
                UiStyle.PAPER -> {
                    drawLine(Color(0xFF1B1A17), Offset(0f, h * 0.22f), Offset(size.width * 0.55f, h * 0.22f), 5f)
                    drawLine(Color(0xFFD9D2C3), Offset(0f, h * 0.5f), Offset(size.width, h * 0.5f), 2f)
                    drawLine(Color(0xFFD9D2C3), Offset(0f, h * 0.7f), Offset(size.width * 0.8f, h * 0.7f), 2f)
                    drawCircle(pal.secondary, 9f, Offset(size.width * 0.9f, h * 0.22f))
                }
                UiStyle.MIDNIGHT -> {
                    drawRoundRect(Color(0xFF0D0E11), Offset(0f, 0f), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(18f))
                    val r = h * 0.34f
                    drawCircle(pal.primary.copy(alpha = 0.18f), r, Offset(h * 0.55f, h * 0.5f), style = Stroke(7f))
                    drawArc(pal.primary, -90f, 250f, false, Offset(h * 0.55f - r, h * 0.5f - r), Size(r * 2, r * 2), style = Stroke(7f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    for (k in 0..5) drawRoundRect(pal.primary.copy(alpha = if (k == 5) 1f else 0.4f), Offset(h * 1.15f + k * 18f, h * (0.75f - 0.08f * ((k * 3) % 5))), Size(10f, h * (0.12f + 0.08f * ((k * 3) % 5))), androidx.compose.ui.geometry.CornerRadius(4f))
                }
                UiStyle.BOLD -> {
                    drawRoundRect(pal.primary, Offset(0f, 0f), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(24f))
                    drawRoundRect(Color.White, Offset(14f, h * 0.2f), Size(size.width * 0.42f, h * 0.34f), androidx.compose.ui.geometry.CornerRadius(6f))
                    drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(14f, h * 0.72f), Size(size.width - 28f, 8f), androidx.compose.ui.geometry.CornerRadius(4f))
                    drawRoundRect(Color.White, Offset(14f, h * 0.72f), Size((size.width - 28f) * 0.68f, 8f), androidx.compose.ui.geometry.CornerRadius(4f))
                }
                UiStyle.MONO -> {
                    drawRect(Color(0xFF0A0A0A), Offset(1f, 1f), Size(size.width - 2f, h - 2f), style = Stroke(2f))
                    drawRect(Color(0xFF0A0A0A), Offset(14f, h * 0.22f), Size(size.width * 0.4f, h * 0.3f))
                    drawCircle(pal.primary, 8f, Offset(size.width - 22f, h * 0.3f))
                    for (k in 0 until 14) drawRect(Color(0xFF0A0A0A), Offset(14f + k * (size.width - 28f) / 14f, h * 0.68f), Size((size.width - 28f) / 22f, h * 0.2f))
                }
                UiStyle.HACKER -> {
                    drawRect(pal.primary.copy(alpha = 0.55f), Offset(1f, 1f), Size(size.width - 2f, h - 2f), style = Stroke(2f))
                    val rows = listOf(0.62f, 0.38f, 0.78f, 0.5f)
                    rows.forEachIndexed { r, len ->
                        var x = 14f
                        var k = 0
                        while (x < size.width * len) { val w = 6f + ((k * 7 + r * 5) % 4) * 5f; drawRect(pal.primary.copy(alpha = if (r == 3) 1f else 0.7f), Offset(x, h * (0.16f + 0.2f * r)), Size(w, 7f)); x += w + 6f; k++ }
                    }
                    drawRect(pal.primary, Offset(size.width * 0.5f + 18f, h * 0.76f), Size(10f, 12f))
                }
                UiStyle.TERMINAL -> {
                    drawRoundRect(Color(0xFF16181C), Offset(0f, 0f), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(12f))
                    listOf(Color(0xFFE06C75), Color(0xFFE5C07B), Color(0xFF98C379)).forEachIndexed { k, c -> drawCircle(c, 5f, Offset(16f + k * 18f, 14f)) }
                    drawRect(pal.primary, Offset(14f, h * 0.38f), Size(12f, 7f))
                    drawRect(Color(0xFFE6E1CF), Offset(34f, h * 0.38f), Size(size.width * 0.42f, 7f))
                    drawRect(Color(0xFF959CAA), Offset(14f, h * 0.58f), Size(size.width * 0.66f, 7f))
                    drawRect(pal.primary, Offset(14f, h * 0.78f), Size(12f, 7f))
                    drawRect(pal.primary, Offset(34f, h * 0.74f), Size(11f, 16f))
                }
                UiStyle.STUDIO -> {
                    drawRoundRect(Color.White, Offset(0f, 0f), Size(size.width, h), androidx.compose.ui.geometry.CornerRadius(14f))
                    drawCircle(pal.primary.copy(alpha = 0.18f), h * 0.34f, Offset(h * 0.55f, h * 0.5f), style = Stroke(7f))
                    drawArc(pal.primary, -90f, 230f, false, Offset(h * 0.55f - h * 0.34f, h * 0.5f - h * 0.34f), Size(h * 0.68f, h * 0.68f), style = Stroke(7f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawRoundRect(Color(0xFF17181A), Offset(h * 1.15f, h * 0.30f), Size(size.width * 0.38f, 6f), androidx.compose.ui.geometry.CornerRadius(3f))
                    drawRoundRect(Color(0xFFD9D7CF), Offset(h * 1.15f, h * 0.55f), Size(size.width * 0.5f, 5f), androidx.compose.ui.geometry.CornerRadius(3f))
                }
                UiStyle.CLEAN -> {
                    val w = (size.width - 8f) / 2
                    for (k in 0..1) {
                        drawRoundRect(Color.White, Offset(k * (w + 8f), 0f), Size(w, h), androidx.compose.ui.geometry.CornerRadius(14f))
                        drawRoundRect(pal.primary, Offset(k * (w + 8f) + 10f, h - 18f), Size((w - 20f) * (0.5f + 0.3f * k), 6f), androidx.compose.ui.geometry.CornerRadius(3f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(st.label, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(st.blurb, color = fg.copy(alpha = 0.6f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 3, minLines = 3)
    }
}

@Composable
private fun Label(t: String) {
    val look = LocalRace.current
    Text(if (look.upperTitles) t.uppercase() else t, style = MaterialTheme.typography.labelMedium, color = look.dim, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun LiveryChip(l: Livery, selected: Boolean, onClick: () -> Unit) {
    val look = LocalRace.current
    val shape = look.buttonShape
    Row(
        Modifier.clip(shape).background(look.panelHi).border(if (selected) 2.dp else 1.dp, if (selected) l.primary else look.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(l.primary))
        Box(Modifier.padding(start = 2.dp).size(10.dp).clip(CircleShape).background(l.secondary))
        Text("  ${l.name}", style = MaterialTheme.typography.labelLarge, color = if (selected) look.text else look.dim)
    }
}

@Composable
private fun ColorRow(current: Color, onPick: (Color) -> Unit) {
    val look = LocalRace.current
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PALETTE.forEach { c ->
            val sel = c == current
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(c)
                    .border(if (sel) 3.dp else 1.dp, if (sel) look.text else look.border, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val look = LocalRace.current
    val shape = look.buttonShape
    val onPrimary = if (look.primary.luminance() > 0.55f) Color.Black else Color.White
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.clip(shape).background(if (sel) look.primary else look.panelHi)
                    .clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    if (look.upperTitles) o.uppercase() else o, fontFamily = look.body, fontWeight = FontWeight.Bold,
                    fontStyle = if (look.italicTitles) TFontStyle.Italic else TFontStyle.Normal, fontSize = 13.sp,
                    color = if (sel) onPrimary else look.dim,
                )
            }
        }
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val look = LocalRace.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = look.text)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = look.primary))
    }
}

@Composable
private fun SmallKey(t: String, enabled: Boolean, onClick: () -> Unit) {
    val look = LocalRace.current
    Box(
        Modifier.padding(end = 4.dp).size(30.dp).clip(CircleShape).background(look.panelHi)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(t, color = if (enabled) look.text else look.panelHi, fontSize = 12.sp) }
}

@Composable
private fun Slide(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    val look = LocalRace.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (look.upperTitles) label.uppercase() else label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = look.dim)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = look.text)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = look.primary, activeTrackColor = look.primary),
        )
    }
}
