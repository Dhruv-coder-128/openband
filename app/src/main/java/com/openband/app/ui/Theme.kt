package com.openband.app.ui

import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openband.app.R
import androidx.compose.ui.graphics.Canvas as GCanvas
import androidx.compose.ui.text.font.FontStyle as TFontStyle

/** Screen / tab names for each style. */
data class Names(
    val dash: String, val garage: String, val health: String, val history: String, val style: String, val lab: String,
    val sync: String, val pair: String, val sport: String = "Sport", val profile: String = "Profile", val settings: String = "Settings",
)

private fun namesFor(s: UiStyle) = when (s) {
    UiStyle.RACING -> Names("Dash", "Garage", "Pit wall", "Laps", "Livery", "ECU diagnostics", "BOX BOX · SYNC", "PAIR", "Track", "Driver", "Setup")
    UiStyle.CYBER -> Names("HUD", "Link", "Feed", "Logs", "Mods", "Netrunner lab", "SYNC_NOW", "JACK_IN", "Ops", "Identity", "Sys")
    UiStyle.RINGS -> Names("Overview", "Band", "Health", "Trends", "Profile", "Protocol lab", "Sync", "Pair band", "Sport")
    UiStyle.CLEAN -> Names("Today", "Band", "Health", "History", "Style", "Protocol lab", "Sync now", "Pair band", "Workout")
    UiStyle.PAPER -> Names("Today", "Band", "Health", "Journal", "Style", "Protocol lab", "Sync now", "Pair band", "Workout", "Profile", "Settings")
    UiStyle.STUDIO, UiStyle.MIDNIGHT, UiStyle.BOLD, UiStyle.MONO, UiStyle.HACKER, UiStyle.TERMINAL -> Names("Today", "Band", "Health", "History", "Appearance", "Protocol lab", "Sync now", "Pair band", "Activity", "Profile", "Settings")
}

/** Everything the UI needs, available anywhere via [LocalRace]. */
data class RaceLook(
    val prefs: RacePrefs,
    val style: UiStyle,
    val names: Names,
    val primary: Color,
    val secondary: Color,
    val bg: Color,
    val panel: Color,
    val panelHi: Color,
    val border: Color,
    val text: Color,
    val dim: Color,
    val light: Boolean,
    val danger: Color = Color(0xFFFF1E1E),
    val ok: Color = Color(0xFF00E676),
    val warn: Color = Color(0xFFFFC400),
    val display: FontFamily,
    val body: FontFamily,
    val panelShape: Shape,
    val buttonShape: Shape,
    val italicTitles: Boolean,
    val upperTitles: Boolean,
    /** Buttons and chips are near-black / near-white (the OpenBand look) instead of the accent colour. */
    val inkButtons: Boolean = false,
) {
    /** The layout family this look is built on. */
    val family: UiStyle get() = style.family

    /** Picks the text for the current style. */
    fun pick(racing: String, plain: String, cyber: String = plain.uppercase().replace(' ', '_')): String = when (family) {
        UiStyle.RACING -> racing
        UiStyle.CYBER -> cyber
        else -> plain
    }
}

val LocalRace = staticCompositionLocalOf<RaceLook> { error("no RaceLook") }

@OptIn(ExperimentalTextApi::class)
private val Orbitron = FontFamily(
    Font(R.font.orbitron, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.orbitron, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.orbitron, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.orbitron, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.orbitron, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)
private val Rajdhani = FontFamily(
    Font(R.font.rajdhani_medium, FontWeight.Normal),
    Font(R.font.rajdhani_medium, FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold, FontWeight.Bold),
    Font(R.font.rajdhani_bold, FontWeight.Black),
)
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(res: Int) = FontFamily(
    Font(res, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(res, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(res, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(res, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(res, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)
private val SpaceGrotesk = variableFamily(R.font.space_grotesk)
private val Playfair = variableFamily(R.font.playfair)
private val Nunito = variableFamily(R.font.nunito)
private val Inter = variableFamily(R.font.inter)
private val TechMono = FontFamily(Font(R.font.sharetech_mono, FontWeight.Normal), Font(R.font.sharetech_mono, FontWeight.Bold))

private fun typography(display: FontFamily, body: FontFamily, style: UiStyle, studio: Boolean = false): Typography {
    val t = Typography()
    val heavy = if (style == UiStyle.CLEAN || style == UiStyle.RINGS) FontWeight.Bold else FontWeight.Black
    fun TextStyle.d() = copy(fontFamily = display)
    fun TextStyle.b() = copy(fontFamily = body)
    val bodyScale = if (body == Rajdhani) 1.1f else 1f
    val base = t.copy(
        displayLarge = t.displayLarge.d(), displayMedium = t.displayMedium.d(), displaySmall = t.displaySmall.d().copy(fontWeight = heavy),
        headlineLarge = t.headlineLarge.d().copy(fontWeight = heavy),
        headlineMedium = t.headlineMedium.d().copy(fontWeight = heavy, fontSize = if (style == UiStyle.RACING) 24.sp else 28.sp, letterSpacing = if (style == UiStyle.RACING) 1.sp else 0.sp),
        headlineSmall = t.headlineSmall.d().copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.d(),
        titleMedium = t.titleMedium.d().copy(
            fontWeight = FontWeight.Bold,
            fontSize = if (style == UiStyle.RACING) 14.sp else 15.sp,
            letterSpacing = if (style == UiStyle.RACING || style == UiStyle.CYBER) 2.sp else 0.3.sp,
        ),
        titleSmall = t.titleSmall.d(),
        bodyLarge = t.bodyLarge.b().copy(fontSize = 16.sp * bodyScale), bodyMedium = t.bodyMedium.b().copy(fontSize = 14.sp * bodyScale),
        bodySmall = t.bodySmall.b().copy(fontSize = 12.5.sp * bodyScale),
        labelLarge = t.labelLarge.b().copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, fontSize = 14.sp * bodyScale),
        labelMedium = t.labelMedium.b().copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, fontSize = 11.5.sp * bodyScale),
        labelSmall = t.labelSmall.b().copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
    )
    if (!studio) return base
    // OpenBand look: one calm scale. Numbers are tabular so live values do not jiggle.
    fun TextStyle.n(size: Int, w: FontWeight, ls: Float = 0f, lh: Int = 0) = copy(fontSize = size.sp, fontWeight = w, letterSpacing = ls.sp, fontFeatureSettings = "tnum", lineHeight = if (lh > 0) lh.sp else this.lineHeight)
    return base.copy(
        displayLarge = base.displayLarge.n(48, FontWeight.SemiBold, -1f), displayMedium = base.displayMedium.n(40, FontWeight.SemiBold, -0.8f), displaySmall = base.displaySmall.n(32, FontWeight.SemiBold, -0.5f),
        headlineLarge = base.headlineLarge.n(30, FontWeight.SemiBold, -0.5f), headlineMedium = base.headlineMedium.n(26, FontWeight.SemiBold, -0.4f, 32), headlineSmall = base.headlineSmall.n(21, FontWeight.SemiBold, -0.2f),
        titleLarge = base.titleLarge.n(19, FontWeight.SemiBold, -0.1f), titleMedium = base.titleMedium.n(16, FontWeight.SemiBold, 0f, 22), titleSmall = base.titleSmall.n(14, FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    )
}

fun lookFor(p: RacePrefs, systemDark: Boolean = true): RaceLook {
    val style = p.uiStyle
    val (autoDisplay, autoBody) = when (style) {
        UiStyle.RACING -> Orbitron to Rajdhani
        UiStyle.CYBER -> Orbitron to TechMono
        UiStyle.RINGS -> FontFamily.SansSerif to FontFamily.SansSerif
        UiStyle.CLEAN -> FontFamily.SansSerif to FontFamily.SansSerif
        UiStyle.PAPER -> Playfair to FontFamily.SansSerif
        UiStyle.STUDIO, UiStyle.MIDNIGHT -> Inter to Inter
        UiStyle.BOLD -> SpaceGrotesk to Inter
        UiStyle.MONO -> TechMono to Inter
        UiStyle.HACKER, UiStyle.TERMINAL -> TechMono to TechMono
    }
    val (display, body) = when (p.font) {
        FontStyle.AUTO -> autoDisplay to autoBody
        FontStyle.RACING -> Orbitron to Rajdhani
        FontStyle.CLEAN -> FontFamily.SansSerif to FontFamily.SansSerif
        FontStyle.MONO -> TechMono to TechMono
    }
    val light = (style == UiStyle.CLEAN && p.lightMode) || style == UiStyle.PAPER
    val black = p.oled || style == UiStyle.RINGS
    return when (style) {
        UiStyle.HACKER, UiStyle.TERMINAL -> {
            val hacker = style == UiStyle.HACKER
            RaceLook(
                p, style, namesFor(style), p.primary, p.secondary,
                bg = if (hacker) Color(0xFF030603) else Color(0xFF16181C),
                panel = if (hacker) Color(0xFF080E09) else Color(0xFF1E2127),
                panelHi = if (hacker) Color(0xFF112016) else Color(0xFF2A2E36),
                border = if (hacker) mix(Color(0xFF080E09), p.primary, 0.32f) else Color(0xFF3A3F4A),
                text = if (hacker) mix(Color(0xFFE4F2E6), p.primary, 0.22f) else Color(0xFFE6E1CF),
                dim = if (hacker) mix(Color(0xFF7C8C80), p.primary, 0.30f) else Color(0xFF959CAA),
                light = false,
                danger = if (hacker) Color(0xFFF0625A) else Color(0xFFE06C75),
                ok = if (hacker) Color(0xFF3DDC6A) else Color(0xFF98C379),
                warn = if (hacker) Color(0xFFF2B134) else Color(0xFFE5C07B),
                display = display, body = body,
                panelShape = if (hacker) CutCornerShape(topStart = 14.dp, bottomEnd = 14.dp) else RoundedCornerShape(10.dp),
                buttonShape = if (hacker) CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp) else RoundedCornerShape(4.dp),
                italicTitles = false, upperTitles = false,
            )
        }
        UiStyle.RACING -> RaceLook(
            p, style, namesFor(style), p.primary, p.secondary,
            bg = if (black) Color.Black else Color(0xFF0B0C0F), panel = if (black) Color(0xFF0A0A0A) else Color(0xFF15171C),
            panelHi = mix(Color(0xFF22252C), p.primary, 0.06f), border = mix(Color(0xFF2C3038), p.primary, 0.18f),
            text = mix(Color(0xFFF4F4F4), p.primary, 0.05f), dim = mix(Color(0xFFA2A7B0), p.primary, 0.22f), light = false,
            display = display, body = body, panelShape = CutCornerShape(topEnd = 18.dp, bottomStart = 18.dp),
            buttonShape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp), italicTitles = true, upperTitles = true,
        )
        UiStyle.CYBER -> RaceLook(
            p, style, namesFor(style), p.primary, p.secondary,
            bg = if (black) Color.Black else Color(0xFF07040F), panel = Color(0xFF0E0A1C), panelHi = Color(0xFF1A1430),
            border = p.primary.copy(alpha = 0.55f), text = mix(Color(0xFFF2F8FF), p.secondary, 0.08f), dim = mix(Color(0xFFA9A5C6), p.secondary, 0.30f), light = false,
            danger = Color(0xFFFF003C), ok = p.secondary, warn = Color(0xFFFCEE0A),
            display = display, body = body, panelShape = CutCornerShape(topEnd = 22.dp, bottomStart = 22.dp),
            buttonShape = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp), italicTitles = false, upperTitles = true,
        )
        UiStyle.RINGS -> RaceLook(
            p, style, namesFor(style), p.primary, p.secondary,
            bg = Color(0xFF0B0D0F), panel = Color(0xFF161A1E), panelHi = Color(0xFF20262C), border = Color(0xFF242A31),
            text = Color(0xFFF7F9FB), dim = mix(Color(0xFFA4ADB7), p.primary, 0.12f), light = false,
            danger = Color(0xFFFF3B30), ok = Color(0xFF16EC06), warn = Color(0xFFFFDE00),
            display = display, body = body, panelShape = RoundedCornerShape(22.dp), buttonShape = RoundedCornerShape(50),
            italicTitles = false, upperTitles = false,
        )
        UiStyle.CLEAN -> RaceLook(
            p, style, namesFor(style), p.primary, p.secondary,
            bg = if (light) Color(0xFFF4F5F7) else if (black) Color.Black else Color(0xFF111315),
            panel = if (light) Color.White else Color(0xFF1B1E21), panelHi = if (light) Color(0xFFECEFF2) else Color(0xFF262A2E),
            border = if (light) mix(Color(0xFFE3E6EA), p.primary, 0.12f) else mix(Color(0xFF262A2E), p.primary, 0.12f),
            text = if (light) mix(Color(0xFF111418), p.primary, 0.08f) else mix(Color(0xFFF3F5F7), p.primary, 0.04f),
            dim = if (light) mix(Color(0xFF59616E), p.primary, 0.22f) else mix(Color(0xFFA8AFB7), p.primary, 0.18f), light = light,
            danger = Color(0xFFE53935), ok = Color(0xFF2E9E5B), warn = Color(0xFFF2A100),
            display = display, body = body, panelShape = RoundedCornerShape(24.dp), buttonShape = RoundedCornerShape(50),
            italicTitles = false, upperTitles = false,
        )
        UiStyle.PAPER -> RaceLook(
            p, style, namesFor(style), p.primary, p.secondary,
            bg = Color(0xFFF5F1EA), panel = Color(0xFFFFFFFF), panelHi = Color(0xFFEFEAE0), border = Color(0xFFE2DBCD),
            text = Color(0xFF1B1A17), dim = Color(0xFF7A7467), light = true,
            danger = Color(0xFFC0392B), ok = Color(0xFF2E7D4F), warn = Color(0xFFC77D0A),
            display = display, body = body, panelShape = RoundedCornerShape(10.dp), buttonShape = RoundedCornerShape(10.dp),
            italicTitles = false, upperTitles = false,
        )
        UiStyle.STUDIO, UiStyle.MIDNIGHT, UiStyle.BOLD, UiStyle.MONO -> {
            val night = style == UiStyle.MIDNIGHT
            val mono = style == UiStyle.MONO
            val dark = night || when (p.appearance) { 1 -> false; 2 -> true; else -> systemDark }
            fun pick(d: Long, l: Long) = Color(if (dark) d else l)
            RaceLook(
                p, style, namesFor(style),
                primary = when {
                    night -> p.primary
                    mono && p.livery == "ink" && p.customPrimary == null -> pick(0xFFEDEDED, 0xFF111111)
                    dark -> lerp(p.primary, Color.White, if (style == UiStyle.BOLD) 0.12f else 0.30f)
                    else -> p.primary
                },
                secondary = if (dark && !night) lerp(p.secondary, Color.White, 0.28f) else p.secondary,
                bg = when { night -> Color.Black; mono -> pick(0xFF000000, 0xFFFFFFFF); else -> pick(0xFF0E0F11, 0xFFF4F3EF) },
                panel = when { night -> Color(0xFF0D0E11); mono -> pick(0xFF0B0B0B, 0xFFFFFFFF); else -> pick(0xFF17191C, 0xFFFFFFFF) },
                panelHi = when { night -> Color(0xFF181A1F); mono -> pick(0xFF1A1A1A, 0xFFF0F0F0); else -> pick(0xFF212328, 0xFFEFEDE7) },
                border = when { night -> Color(0xFF1F2228); mono -> pick(0xFF3A3A3A, 0xFF1A1A1A); style == UiStyle.BOLD -> pick(0xFF24262B, 0xFFE9E7E1); else -> pick(0xFF2A2D32, 0xFFE5E3DC) },
                text = when { night -> Color(0xFFE9EAEE); mono -> pick(0xFFF2F2F2, 0xFF0A0A0A); else -> pick(0xFFF2F2EF, 0xFF17181A) },
                dim = when { night -> Color(0xFF8E94A0); mono -> pick(0xFFA0A0A0, 0xFF555555); else -> pick(0xFFA2A6AD, 0xFF5D6066) },
                light = !dark,
                danger = pick(0xFFF0697C, 0xFFC0354A),
                ok = pick(0xFF5CC38E, 0xFF23855A),
                warn = pick(0xFFE3B85B, 0xFFA9741A),
                display = display, body = body,
                panelShape = RoundedCornerShape(when (style) { UiStyle.MONO -> 4.dp; UiStyle.BOLD -> 24.dp; UiStyle.MIDNIGHT -> 20.dp; else -> 16.dp }),
                buttonShape = RoundedCornerShape(when (style) { UiStyle.MONO -> 2.dp; UiStyle.BOLD, UiStyle.MIDNIGHT -> 50.dp; else -> 12.dp }),
                italicTitles = false, upperTitles = false, inkButtons = style != UiStyle.BOLD,
            )
        }
    }
}

private fun schemeFor(l: RaceLook): ColorScheme {
    val onPrimary = if (l.primary.luminance() > 0.55f) Color.Black else Color.White
    return if (l.light) lightColorScheme(
        primary = l.primary, onPrimary = onPrimary, secondary = l.secondary, background = l.bg, onBackground = l.text,
        surface = l.bg, onSurface = l.text, surfaceVariant = l.panelHi, onSurfaceVariant = l.dim,
        surfaceContainer = l.panel, surfaceContainerLow = l.panel, surfaceContainerHigh = l.panelHi, surfaceContainerHighest = l.panelHi,
        outline = l.border, outlineVariant = l.border, error = l.danger,
    ) else darkColorScheme(
        primary = l.primary, onPrimary = onPrimary, secondary = l.secondary, onSecondary = Color.Black,
        background = l.bg, onBackground = l.text, surface = l.bg, onSurface = l.text,
        surfaceVariant = l.panelHi, onSurfaceVariant = l.dim,
        surfaceContainer = l.panel, surfaceContainerLow = l.panel, surfaceContainerHigh = l.panelHi, surfaceContainerHighest = l.panelHi,
        outline = mix(Color(0xFF4A505A), l.primary, 0.35f), outlineVariant = l.border, error = l.danger,
    )
}

@Composable
fun OpenBandTheme(content: @Composable () -> Unit) {
    val p by RaceSettings.prefs.collectAsState()
    val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
    val look = remember(p, sysDark) { lookFor(p, sysDark) }
    CompositionLocalProvider(LocalRace provides look) {
        MaterialTheme(colorScheme = schemeFor(look), typography = typography(look.display, look.body, look.family, look.style.studio)) {
            // any Text without an explicit colour follows the theme (it used to fall back to black)
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides look.text, content = content)
        }
    }
}

/** Blends [a] towards [b] by [f] (0..1). Used to tint neutrals with the user's accent colour. */
fun mix(a: Color, b: Color, f: Float): Color = Color(
    red = a.red + (b.red - a.red) * f, green = a.green + (b.green - a.green) * f,
    blue = a.blue + (b.blue - a.blue) * f, alpha = 1f,
)

fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

// ------------------------------------------------------------------------------------------------ backgrounds

/** A tiling carbon-fibre weave texture, generated once. */
@Composable
fun rememberCarbonBrush(): Brush = remember {
    val px = 12
    val bmp = ImageBitmap(px * 2, px * 2)
    val canvas = GCanvas(bmp)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px * 2f, px * 2f)) {
        drawRect(Color(0xFF0D0E11))
        fun weave(x: Float, y: Float, horizontal: Boolean) {
            val g = if (horizontal) Brush.verticalGradient(listOf(Color(0xFF1C1E23), Color(0xFF101114)), y, y + px)
            else Brush.horizontalGradient(listOf(Color(0xFF1A1C21), Color(0xFF0F1013)), x, x + px)
            drawRect(g, Offset(x + 0.5f, y + 0.5f), Size(px - 1f, px - 1f))
        }
        weave(0f, 0f, true); weave(px.toFloat(), 0f, false)
        weave(0f, px.toFloat(), false); weave(px.toFloat(), px.toFloat(), true)
    }
    ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
}

/** Full-screen background for the current style. Drawn in its own layer, so it is not redrawn with content. */
@Composable
fun RaceBackground(content: @Composable () -> Unit) {
    val look = LocalRace.current
    val p = look.prefs
    val carbon = rememberCarbonBrush()
    Box(Modifier.fillMaxSize().background(look.bg)) {
        when (look.style) {
            UiStyle.PAPER, UiStyle.STUDIO, UiStyle.MIDNIGHT, UiStyle.BOLD, UiStyle.MONO, UiStyle.TERMINAL -> Unit
            UiStyle.HACKER -> DigitalRain(look.primary, p.animate)
            else -> when (look.family) {
            UiStyle.RACING -> {
                if (p.carbon && !p.oled) Box(Modifier.fillMaxSize().background(carbon))
                Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(look.primary.copy(alpha = 0.16f), Color.Transparent))))
            }
            UiStyle.CYBER -> Canvas(Modifier.fillMaxSize().graphicsLayer()) {
                // glow at the top, perspective grid at the bottom, scanlines over everything
                drawRect(Brush.verticalGradient(listOf(look.primary.copy(alpha = 0.18f), Color.Transparent), 0f, size.height * 0.35f))
                val horizon = size.height * 0.72f
                val gridCol = look.secondary.copy(alpha = 0.16f)
                for (i in 0..12) {
                    val t = i / 12f
                    val y = horizon + (size.height - horizon) * t * t
                    drawLine(gridCol, Offset(0f, y), Offset(size.width, y), 1.5f)
                }
                val cx = size.width / 2
                for (i in -10..10) {
                    drawLine(gridCol, Offset(cx + i * size.width * 0.03f, horizon), Offset(cx + i * size.width * 0.22f, size.height), 1.5f)
                }
                if (p.scanlines) {
                    var y = 0f
                    while (y < size.height) { drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), 1f); y += 4f }
                }
            }
            UiStyle.RINGS -> Unit
            else -> Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(look.primary.copy(alpha = if (look.light) 0.10f else 0.12f), Color.Transparent))))
            }
        }
        content()
    }
}

// ------------------------------------------------------------------------------------------------ titles & panels

@Composable
fun ScreenTitle(title: String, sub: String? = null) {
    val look = LocalRace.current
    if (look.isConsole) { ConsoleTitle(title, sub, Modifier.padding(top = 12.dp, bottom = 6.dp)); return }
    Column(Modifier.padding(top = 14.dp, bottom = 6.dp)) {
        when (look.family) {
            UiStyle.RACING -> Row(verticalAlignment = Alignment.CenterVertically) {
                RaceStripes(look.primary, look.secondary)
                Spacer(Modifier.width(10.dp))
                Text(title.uppercase(), style = MaterialTheme.typography.headlineMedium, fontStyle = TFontStyle.Italic, color = look.text)
            }
            UiStyle.CYBER -> Box {
                val t = "> " + title.uppercase().replace(' ', '_') + "_"
                Text(t, style = MaterialTheme.typography.headlineMedium, color = look.secondary.copy(alpha = 0.7f), modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                Text(t, style = MaterialTheme.typography.headlineMedium, color = look.primary)
            }
            else -> Text(look.style.prompt + title, style = MaterialTheme.typography.headlineMedium, color = if (look.style.prompt.isEmpty()) look.text else look.primary)
        }
        if (sub != null) Text(
            if (look.family == UiStyle.CYBER) "// " + sub.lowercase() else sub,
            style = MaterialTheme.typography.bodyMedium, color = look.dim,
            modifier = Modifier.padding(start = if (look.style == UiStyle.RACING) 40.dp else 0.dp, top = 2.dp),
        )
    }
}

@Composable
fun RaceStripes(a: Color, b: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.width(30.dp).height(20.dp)) {
        val w = size.width / 4.5f
        for (i in 0 until 3) {
            val x = i * w * 1.5f
            val path = Path().apply {
                moveTo(x + w * 0.8f, 0f); lineTo(x + w * 1.8f, 0f); lineTo(x + w, size.height); lineTo(x, size.height); close()
            }
            drawPath(path, if (i == 2) b else a, style = Fill)
        }
    }
}

@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val look = LocalRace.current
    val shape = look.panelShape
    val brackets = if (look.style == UiStyle.HACKER) Modifier.brackets(look.primary.copy(alpha = 0.8f)) else Modifier
    val base = modifier.fillMaxWidth().padding(vertical = 6.dp).reveal().then(brackets).clip(shape)
    val styled = when (look.style) {
        UiStyle.PAPER -> base.background(look.panel).border(1.dp, look.border, shape)
        else -> when (look.family) {
        UiStyle.RACING -> base.background(Brush.verticalGradient(listOf(look.panelHi.copy(alpha = 0.92f), look.panel.copy(alpha = 0.95f)))).border(1.dp, look.border, shape)
        UiStyle.CYBER -> base.background(look.panel.copy(alpha = 0.88f)).border(1.dp, look.border, shape)
        UiStyle.RINGS -> base.background(look.panel)
        else -> base.background(look.panel).border(1.dp, look.border, shape)
        }
    }
    Box(styled) {
        if (look.style == UiStyle.RACING) Box(Modifier.matchParentSize()) { Box(Modifier.width(4.dp).fillMaxHeight().background(look.primary)) }
        Column(Modifier.padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            if (title != null) {
                CardTitle(title)
                Spacer(Modifier.height(6.dp))
            }
            content()
        }
    }
}

@Composable
fun CardTitle(title: String) {
    val look = LocalRace.current
    when (look.family) {
        UiStyle.RACING -> Text(title.uppercase(), style = MaterialTheme.typography.titleMedium, fontStyle = TFontStyle.Italic, color = look.primary)
        UiStyle.CYBER -> Text("// " + title.uppercase(), style = MaterialTheme.typography.titleMedium, color = look.primary)
        UiStyle.RINGS -> Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = look.dim)
        else -> Text(look.style.prompt + themedText(title), style = MaterialTheme.typography.titleMedium, color = if (look.style.prompt.isEmpty()) look.text else look.primary)
    }
}

@Composable
fun StatTile(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    Box(modifier.padding(4.dp).reveal().clip(look.panelShape).background(look.panel).border(1.dp, look.border, look.panelShape)) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Text(if (look.upperTitles) label.uppercase() else label, style = MaterialTheme.typography.labelMedium, color = look.dim)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = look.text)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = look.dim)
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    val look = LocalRace.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(if (look.upperTitles) k.uppercase() else k, Modifier.weight(1f), style = if (look.upperTitles) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium, color = look.dim)
        Text(v, style = MaterialTheme.typography.bodyMedium, color = look.text, fontWeight = FontWeight.SemiBold)
    }
}

/** Main action button, shaped for the current style. */
@Composable
fun RaceButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val look = LocalRace.current
    val shape = look.buttonShape
    val onPrimary = if (look.primary.luminance() > 0.55f) Color.Black else Color.White
    val bg = when {
        !enabled -> look.panelHi
        look.inkButtons -> if (primary) look.text else Color.Transparent
        primary && look.family == UiStyle.CYBER -> look.primary.copy(alpha = 0.16f)
        primary -> look.primary
        else -> Color.Transparent
    }
    val borderCol = when {
        look.inkButtons -> if (primary) Color.Transparent else look.border
        look.family == UiStyle.CYBER -> if (primary) look.primary else look.secondary
        primary -> Color.Transparent
        else -> if (look.family == UiStyle.CLEAN || look.family == UiStyle.RINGS) look.border else look.primary
    }
    val fg = when {
        !enabled -> look.dim
        look.inkButtons -> if (primary) look.bg else look.text
        look.family == UiStyle.CYBER -> if (primary) look.primary else look.secondary
        primary -> onPrimary
        look.family == UiStyle.CLEAN || look.family == UiStyle.RINGS -> look.text
        else -> look.primary
    }
    Box(
        modifier.clip(shape).background(bg).border(1.5.dp, borderCol, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (look.upperTitles) text.uppercase() else text, fontFamily = if (look.family == UiStyle.RACING) look.display else look.body,
            fontWeight = FontWeight.Bold, fontStyle = if (look.italicTitles) TFontStyle.Italic else TFontStyle.Normal,
            fontSize = if (look.family == UiStyle.RACING) 13.sp else 15.sp, letterSpacing = if (look.upperTitles) 1.sp else 0.sp, color = fg,
        )
    }
}

// ------------------------------------------------------------------------------------------------ charts

/**
 * Follows a finger (tap / drag) or a mouse (hover) over a chart and reports the x position, or null when the pointer leaves.
 * It never consumes events, so scrolling the page still works.
 */
fun Modifier.chartPointer(onX: (Float?) -> Unit): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            val ch = e.changes.firstOrNull() ?: continue
            when (e.type) {
                PointerEventType.Exit -> onX(null)
                PointerEventType.Press, PointerEventType.Move, PointerEventType.Enter -> onX(ch.position.x)
                else -> {}
            }
        }
    }
}

/** Draws the little info bubble used by every chart (value on top, time / label under it). */
@OptIn(ExperimentalTextApi::class)
fun DrawScope.drawTip(tm: TextMeasurer, look: RaceLook, title: String, sub: String, anchor: Offset, accent: Color) {
    val t1 = tm.measure(title, TextStyle(color = look.text, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    val t2 = tm.measure(sub, TextStyle(color = look.dim, fontSize = 11.sp))
    val padX = 10.dp.toPx(); val padY = 6.dp.toPx()
    val w = maxOf(t1.size.width, t2.size.width) + padX * 2
    val h = t1.size.height + t2.size.height + padY * 2
    val x = (anchor.x - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    var y = anchor.y - h - 12.dp.toPx()
    if (y < 0f) y = (anchor.y + 14.dp.toPx()).coerceAtMost((size.height - h).coerceAtLeast(0f))
    drawRoundRect(look.panelHi, Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
    drawRoundRect(accent.copy(alpha = 0.8f), Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = Stroke(1.2.dp.toPx()))
    drawText(t1, topLeft = Offset(x + padX, y + padY))
    drawText(t2, topLeft = Offset(x + padX, y + padY + t1.size.height))
}

/** Time-series line: grid, glowing line, gradient fill. Tap, drag or hover to read a value. x = time between [from, to]. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>, from: Long, to: Long, modifier: Modifier = Modifier, color: Color? = null, height: Dp = 150.dp, grid: Boolean = true,
    unit: String = "", decimals: Int = 0, xLabel: ((Long) -> String)? = null,
) {
    val look = LocalRace.current
    val c = color ?: look.primary
    val gridCol = if (look.light) Color(0xFFE5E7EB) else Color(0xFF2A2E35)
    val tm = rememberTextMeasurer()
    var sel by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(sel) { if (sel != null) { delay(4000); sel = null } }
    val fmtX = xLabel ?: { t -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) }
    Canvas(modifier.fillMaxWidth().height(height).chartPointer { sel = it }) {
        if (grid) {
            for (i in 0..4) { val y = size.height * i / 4f; drawLine(gridCol, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f) }
            if (look.family == UiStyle.RACING || look.family == UiStyle.CYBER)
                for (i in 0..6) { val x = size.width * i / 6f; drawLine(gridCol, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f) }
        }
        if (points.size < 2) return@Canvas
        val minY = points.minOf { it.second } - 5
        val maxY = points.maxOf { it.second } + 5
        val span = (to - from).coerceAtLeast(1).toFloat()
        fun xOf(t: Long) = (t - from) / span * size.width
        fun yOf(v: Double) = size.height - ((v - minY) / (maxY - minY)).toFloat() * size.height
        val line = Path()
        var firstX = 0f; var lastX = 0f
        points.forEachIndexed { i, (t, v) ->
            val x = xOf(t); val y = yOf(v)
            if (i == 0) { line.moveTo(x, y); firstX = x } else line.lineTo(x, y)
            lastX = x
        }
        val fill = Path().apply { addPath(line); lineTo(lastX, size.height); lineTo(firstX, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(c.copy(alpha = if (look.style.studio) 0.13f else 0.30f), Color.Transparent)))
        if (look.family == UiStyle.RACING || look.family == UiStyle.CYBER) drawPath(line, c.copy(alpha = 0.35f), style = Stroke(width = 9f))
        drawPath(line, c, style = Stroke(width = 3f))

        sel?.let { px ->
            var bi = 0; var bd = Float.MAX_VALUE
            points.forEachIndexed { i, (t, _) -> val d = kotlin.math.abs(xOf(t) - px); if (d < bd) { bd = d; bi = i } }
            val (t, v) = points[bi]
            val x = xOf(t); val y = yOf(v)
            drawLine(c.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
            drawCircle(look.bg, 9f, Offset(x, y)); drawCircle(c, 6f, Offset(x, y))
            val txt = (if (decimals == 0) "${Math.round(v)}" else "%.${decimals}f".format(v)) + if (unit.isNotEmpty()) " $unit" else ""
            drawTip(tm, look, txt, fmtX(t), Offset(x, y), c)
        }
    }
}

/** Tiny 7-day trend line. */
@Composable
fun Sparkline(values: List<Double?>, modifier: Modifier = Modifier, color: Color? = null) {
    val look = LocalRace.current
    val c = color ?: look.primary
    Canvas(modifier.width(64.dp).height(26.dp)) {
        val pts = values.mapIndexedNotNull { i, v -> v?.let { i to it } }
        if (pts.size < 2) return@Canvas
        val mn = pts.minOf { it.second }; val mx = pts.maxOf { it.second }
        val span = (mx - mn).takeIf { it > 1e-9 } ?: 1.0
        val n = (values.size - 1).coerceAtLeast(1)
        val path = Path()
        pts.forEachIndexed { k, (i, v) ->
            val x = size.width * i / n
            val y = size.height - ((v - mn) / span).toFloat() * (size.height - 4) - 2
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c, style = Stroke(width = 2.5f))
        val (li, lv) = pts.last()
        drawCircle(c, 3.5f, Offset(size.width * li / n, size.height - ((lv - mn) / span).toFloat() * (size.height - 4) - 2))
    }
}

/** Daily bars. The best (highest) bar uses the secondary colour. Tap, drag or hover to read a bar. */
@OptIn(ExperimentalTextApi::class)
@Composable
fun BarChart(values: List<Double?>, modifier: Modifier = Modifier, color: Color? = null, tip: ((Int) -> Pair<String, String>)? = null) {
    val look = LocalRace.current
    val empty = if (look.light) Color(0xFFE5E7EB) else Color(0xFF2A2E35)
    val tm = rememberTextMeasurer()
    var sel by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(sel) { if (sel != null) { delay(4000); sel = null } }
    Canvas(modifier.fillMaxWidth().height(170.dp).chartPointer { sel = it }) {
        if (values.isEmpty()) return@Canvas
        val max = (values.filterNotNull().maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
        val slot = size.width / values.size
        val bw = slot * 0.62f
        val picked = sel?.let { (it / slot).toInt().coerceIn(0, values.lastIndex) }
        values.forEachIndexed { i, v ->
            val h = if (v == null) 4f else ((v / max) * (size.height - 4)).toFloat().coerceAtLeast(4f)
            val flat = look.style.studio && color != null
            val col = when {
                v == null -> empty
                flat -> color!!.copy(alpha = if (i == values.lastIndex) 1f else 0.55f)
                v == max -> look.secondary
                else -> look.primary
            }
            val x = i * slot + (slot - bw) / 2
            if (look.isConsole) {
                themedBar(look, col.copy(alpha = col.alpha * (if (picked == null || picked == i) 1f else 0.45f)), x, size.height - h, bw, size.height, 0f)
                return@forEachIndexed
            }
            val path = Path().apply {
                val skew = if (look.family == UiStyle.RACING) bw * 0.25f else 0f
                moveTo(x + skew, size.height - h); lineTo(x + bw, size.height - h)
                lineTo(x + bw - skew, size.height); lineTo(x, size.height); close()
            }
            if (flat) drawPath(path, col, alpha = if (picked == null || picked == i) 1f else 0.45f) else drawPath(path, Brush.verticalGradient(listOf(col, col.copy(alpha = 0.5f)), size.height - h, size.height), alpha = if (picked == null || picked == i) 1f else 0.45f)
        }
        if (picked != null && tip != null) {
            val v = values[picked]
            val h = if (v == null) 4f else ((v / max) * (size.height - 4)).toFloat().coerceAtLeast(4f)
            val (a, b) = tip(picked)
            drawTip(tm, look, a, b, Offset(picked * slot + slot / 2, size.height - h), look.primary)
        }
    }
}
