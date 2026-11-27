package com.openband.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.text.KeyboardOptions

/*
 * OpenBand's own control set. Every button, switch, slider, chip, text field, dialog and dropdown in the app is drawn here from
 * the active theme (colours, corner shapes, fonts) instead of the stock Android look. The names match the Material ones on purpose,
 * so screens only change an import.
 */

/** Gentle press-in animation + a soft haptic tick. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, haptic: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    val view = LocalView.current
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = src, indication = null, enabled = enabled) {
            if (haptic) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
}

private fun RaceLook.controlShape(): androidx.compose.ui.graphics.Shape = when (family) {
    UiStyle.RACING, UiStyle.CYBER -> CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    else -> buttonShape
}

@Composable
private fun KitButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, kind: Int, content: @Composable RowScope.() -> Unit) {
    val look = LocalRace.current
    val shape = look.controlShape()
    val onPrimary = onColor(look.primary)
    val cyber = look.family == UiStyle.CYBER
    val ink = look.inkButtons
    val bg = when (kind) {
        0 -> if (ink) look.text else if (cyber) look.primary.copy(alpha = 0.16f) else look.primary
        else -> Color.Transparent
    }
    val borderCol = when (kind) {
        0 -> if (ink) Color.Transparent else if (cyber) look.primary else Color.Transparent
        1 -> if (ink) look.border else if (cyber) look.secondary else if (look.family == UiStyle.CLEAN || look.family == UiStyle.RINGS) look.border else look.primary
        3 -> Color.Transparent
        1 -> if (cyber) look.secondary else if (look.family == UiStyle.CLEAN || look.family == UiStyle.RINGS) look.border else look.primary
        else -> Color.Transparent
    }
    val fg = when (kind) {
        0 -> if (ink) look.bg else if (cyber) look.primary else onPrimary
        1 -> if (ink) look.text else if (cyber) look.secondary else if (look.family == UiStyle.CLEAN || look.family == UiStyle.RINGS) look.text else look.primary
        2 -> look.primary
        3 -> look.primary
        1 -> if (cyber) look.secondary else if (look.family == UiStyle.CLEAN || look.family == UiStyle.RINGS) look.text else look.primary
        else -> look.primary
    }
    Box(
        modifier.clip(shape).background(bg).border(if (kind == 2) 0.dp else 1.5.dp, borderCol, shape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .pressable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (kind == 2) 10.dp else 18.dp, vertical = if (kind == 2) 8.dp else 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold, color = fg,
                    letterSpacing = if (look.upperTitles) 1.sp else 0.2.sp,
                ),
            ) { Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, content = content) }
        }
    }
}

@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    KitButton(onClick, modifier, enabled, 0, content)

@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    KitButton(onClick, modifier, enabled, 1, content)

@Composable
fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    KitButton(onClick, modifier, enabled, 2, content)

/** On/off switch: pill (or cut-corner) track, springy knob. */
@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true, colors: Any? = null) {
    val look = LocalRace.current
    val sharp = look.family == UiStyle.RACING || look.family == UiStyle.CYBER || look.isConsole
    val shape = if (sharp) CutCornerShape(topStart = 9.dp, bottomEnd = 9.dp) else RoundedCornerShape(50)
    val knobShape = if (sharp) CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp) else CircleShape
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "knob")
    val onCol = if (look.inkButtons) look.ok else look.primary
    val track by animateColorAsState(if (checked) onCol.copy(alpha = if (look.family == UiStyle.CYBER) 0.25f else 1f) else look.panelHi, tween(180), label = "track")
    val ring by animateColorAsState(if (checked) onCol else look.border, tween(180), label = "ring")
    Box(
        modifier.size(54.dp, 31.dp).clip(shape).background(track).border(1.5.dp, ring, shape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .pressable(enabled = enabled && onCheckedChange != null) { onCheckedChange?.invoke(!checked) }
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = x).size(23.dp).clip(knobShape).background(if (look.inkButtons) (if (checked) Color.White else look.dim) else if (checked) (if (look.family == UiStyle.CYBER) look.primary else onColor(look.primary)) else look.dim))
    }
}

/** Slider with a gradient fill track and a ringed thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Slider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, colors: Any? = null,
) {
    val look = LocalRace.current
    androidx.compose.material3.Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled, valueRange = valueRange, steps = steps,
        thumb = {
            Box(Modifier.size(26.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(if (look.inkButtons) Color.White else look.bg).border(if (look.inkButtons) 1.dp else 3.5.dp, if (look.inkButtons) look.border else look.primary, CircleShape))
        },
        track = { st ->
            val f = ((st.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val h = 7.dp.toPx(); val y = (size.height - h) / 2
                drawRoundRect(look.panelHi, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
                if (f > 0f) drawRoundRect(
                    if (look.inkButtons) SolidColor(look.primary) else Brush.horizontalGradient(listOf(look.primary, look.secondary), 0f, size.width),
                    Offset(0f, y), Size(size.width * f, h), CornerRadius(h / 2),
                )
            }
        },
    )
}

/** Selectable pill (filters, metric pickers). */
@Composable
fun FilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    val shape = look.controlShape().let { if (look.family == UiStyle.RACING || look.family == UiStyle.CYBER) it else look.pill() }
    val bg by animateColorAsState(if (selected) (if (look.inkButtons) look.text else look.primary) else look.panel, tween(160), label = "chip")
    val fg = if (selected) (if (look.inkButtons) look.bg else onColor(look.primary)) else look.text
    Box(
        modifier.clip(shape).background(bg).border(1.dp, if (selected) (if (look.inkButtons) look.text else look.primary) else look.border, shape).pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, color = fg)) { label() }
        }
    }
}

/** Text field with the label above and a focus glow. */
@Composable
fun OutlinedTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visual: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val look = LocalRace.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (look.family == UiStyle.RACING || look.family == UiStyle.CYBER) 6.dp else 12.dp)
    val ring by animateColorAsState(if (focused) look.primary else look.border, tween(150), label = "field")
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(look.primary, look.primary.copy(alpha = 0.3f))) {
        Column(modifier) {
            if (label != null) {
                CompositionLocalProvider(LocalContentColor provides if (focused) look.primary else look.dim) {
                    ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(color = if (focused) look.primary else look.dim)) { label() }
                }
                Box(Modifier.height(5.dp))
            }
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = singleLine, interactionSource = src, keyboardOptions = keyboardOptions,
                visualTransformation = visual,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = look.text),
                cursorBrush = SolidColor(look.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(
                        Modifier.fillMaxWidth().clip(shape).background(look.panelHi.copy(alpha = if (look.light) 0.6f else 0.55f))
                            .border(if (focused) 2.dp else 1.dp, ring, shape).padding(horizontal = 14.dp, vertical = 12.dp),
                    ) { inner() }
                },
            )
        }
    }
}

/** Dialog card in the app's look. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
) {
    val look = LocalRace.current
    val shape = look.panelShape
    Dialog(onDismissRequest) {
        Column(
            modifier.fillMaxWidth().shadow(18.dp, shape).clip(shape).background(look.panel).border(1.dp, look.border, shape).padding(20.dp),
        ) {
            if (title != null) {
                CompositionLocalProvider(LocalContentColor provides look.text) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(color = look.text, fontWeight = FontWeight.Bold)) { title() }
                }
                Box(Modifier.height(10.dp))
            }
            if (text != null) {
                CompositionLocalProvider(LocalContentColor provides look.dim) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = look.dim)) { text() }
                }
                Box(Modifier.height(14.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}

@Composable
fun HorizontalDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalRace.current.border.copy(alpha = 0.6f)))
}

/** Drop-down picker: a field that opens a themed list. */
@Composable
fun AppDropdown(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val look = LocalRace.current
    var open by remember { mutableStateOf(false) }
    var heightPx by remember { mutableStateOf(0) }
    val shape = RoundedCornerShape(if (look.family == UiStyle.RACING || look.family == UiStyle.CYBER) 6.dp else 14.dp)
    Column(modifier) {
        if (label != null) {
            Text(if (look.upperTitles) label.uppercase() else label, style = MaterialTheme.typography.labelMedium, color = look.dim)
            Box(Modifier.height(5.dp))
        }
        Box {
            Row(
                Modifier.fillMaxWidth().clip(shape).background(look.panelHi.copy(alpha = 0.55f)).border(if (open) 2.dp else 1.dp, if (open) look.primary else look.border, shape)
                    .pressable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp)
                    .onSizeChanged { heightPx = it.height },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(options.getOrElse(selected) { "" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = look.text, maxLines = 1)
                Text(if (open) "▴" else "▾", color = look.primary, fontSize = 16.sp)
            }
            if (open) Popup(
                alignment = Alignment.TopStart, offset = IntOffset(0, heightPx + 6), onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.widthIn(min = 180.dp, max = 320.dp).shadow(14.dp, shape).clip(shape).background(look.panel).border(1.dp, look.border, shape)
                        .padding(vertical = 6.dp).verticalScroll(rememberScrollState()),
                ) {
                    options.forEachIndexed { i, o ->
                        val sel = i == selected
                        Row(
                            Modifier.fillMaxWidth().background(if (sel) look.primary.copy(alpha = 0.16f) else Color.Transparent)
                                .pressable { onSelect(i); open = false }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(o, Modifier.weight(1f), color = if (sel) look.primary else look.text, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                            if (sel) Text("✓", color = look.primary, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

/** Standard back control: a clear 48 dp touch target with the shared chevron. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalRace.current
    Box(modifier.size(44.dp).clip(CircleShape).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Icon(AppIcons.Back, "Back", Modifier.size(24.dp), tint = look.text)
    }
}
