package com.example.andriodfypprototype.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrMotion
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* ============================================================== buttons */

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = Wr.Forest,
    height: Dp = 56.dp
) {
    val bg by animateColorAsState(if (enabled) color else Wr.CreamDeep, WrMotion.standard(), label = "bg")
    val fg by animateColorAsState(if (enabled) Color.White else Wr.Ink3, WrMotion.standard(), label = "fg")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(WrShape.md)
            .background(bg)
            .pressable(enabled = enabled && !loading, haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(loading, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) }, label = "load") { busy ->
            if (busy) {
                CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(text, style = WrType.Button.copy(color = fg), textAlign = TextAlign.Center, maxLines = 1)
                    if (trailingIcon != null) {
                        Spacer(Modifier.width(8.dp))
                        Icon(trailingIcon, null, tint = fg, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    ink: Color = Wr.Ink,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(WrShape.md)
            .background(Wr.Ivory)
            .border(BorderStroke(1.dp, Wr.LineStrong), WrShape.md)
            .pressable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(text, style = WrType.Button.copy(color = ink, fontSize = 14.sp), maxLines = 1)
    }
}

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Tone = Tone.Forest,
    height: Dp = 44.dp,
    fill: Boolean = false
) {
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .clip(WrShape.pill)
            .background(tone.bg)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tone.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = WrType.Label.copy(color = tone.ink, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Wr.Ink,
    background: Color = Color.Transparent,
    size: Dp = 44.dp,
    border: Boolean = false,
    badge: Int = 0
) {
    Box(modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(background)
                .then(if (border) Modifier.border(1.dp, Wr.Line, CircleShape) else Modifier)
                .pressable(scaleTo = 0.9f, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f)) }
        if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp))
    }
}

/** Floating round control for use over imagery. */
@Composable
fun MapButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = 46.dp
) {
    Box(
        modifier
            .size(size)
            .shadow(8.dp, CircleShape, ambientColor = Color.Black, spotColor = Color(0x55000000))
            .clip(CircleShape)
            .background(if (active) Wr.Forest else Wr.Ivory)
            .pressable(scaleTo = 0.9f, haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = if (active) Color.White else Wr.Ink, modifier = Modifier.size(22.dp))
    }
}

/* ============================================================== selection */

/** Segmented control with a sliding thumb. Used where the options are few and mutually exclusive. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    onDark: Boolean = false
) {
    val haptics = rememberHaptics()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(WrShape.pill)
            .background(if (onDark) Color.White.copy(alpha = 0.14f) else Wr.CreamDeep)
            .padding(3.dp)
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * selected, WrMotion.settle(), label = "thumb")
        Box(
            Modifier
                .offset(x = x)
                .width(w)
                .fillMaxHeight()
                .shadow(2.dp, WrShape.pill, ambientColor = Color(0x331B2A21), spotColor = Color(0x331B2A21))
                .clip(WrShape.pill)
                .background(if (onDark) Color.White else Wr.Ivory)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val ink by animateColorAsState(if (on) Wr.Ink else if (onDark) Color.White.copy(alpha = 0.8f) else Wr.Ink2, label = "ink")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(WrShape.pill)
                        .pressable(scaleTo = 1f) {
                            if (i != selected) { haptics.tick(); onSelect(i) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = WrType.Label.copy(color = ink, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
fun ChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    counts: Map<String, Int> = emptyMap()
) {
    val haptics = rememberHaptics()
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { opt ->
            val on = opt == selected
            val bg by animateColorAsState(if (on) Wr.Forest else Wr.Ivory, WrMotion.standard(), label = "chip")
            val fg by animateColorAsState(if (on) Color.White else Wr.Ink2, WrMotion.standard(), label = "chipf")
            Row(
                Modifier
                    .height(36.dp)
                    .clip(WrShape.pill)
                    .background(bg)
                    .border(BorderStroke(1.dp, if (on) Wr.Forest else Wr.Line), WrShape.pill)
                    .pressable(scaleTo = 0.94f) { if (!on) { haptics.tick(); onSelect(opt) } }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(opt, style = WrType.Label.copy(color = fg))
                counts[opt]?.let {
                    Spacer(Modifier.width(6.dp))
                    Text("$it", style = WrType.Caption.copy(color = if (on) Color.White.copy(alpha = 0.7f) else Wr.Ink3))
                }
            }
        }
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val haptics = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .rowPress { haptics.toggle(!checked); onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IconTile(icon, Tone.Neutral, 36.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = WrType.TitleS)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = WrType.BodyS)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { haptics.toggle(it); onChange(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Wr.Forest,
                checkedBorderColor = Wr.Forest,
                uncheckedThumbColor = Wr.Ink3,
                uncheckedTrackColor = Wr.CreamDeep,
                uncheckedBorderColor = Wr.LineStrong
            )
        )
    }
}

/* ============================================================== inputs */

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, optional: Boolean = false) {
    Row(modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = WrType.Label.copy(color = Wr.Ink2))
        if (optional) Text("  Optional", style = WrType.Caption)
    }
}

@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    suffix: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    optional: Boolean = false,
    error: String? = null,
    minLines: Int = 1,
    password: Boolean = false
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val border by animateColorAsState(
        when { error != null -> Wr.Clay; focused -> Wr.Forest; else -> Wr.Line }, WrMotion.quick(), label = "b"
    )
    val bw by animateDpAsState(if (focused) 1.5.dp else 1.dp, label = "bw")
    Column(modifier) {
        if (label != null) FieldLabel(label, optional = optional)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(WrShape.md)
                .background(Wr.Ivory)
                .border(BorderStroke(bw, border), WrShape.md)
                .padding(horizontal = 16.dp, vertical = if (singleLine) 15.dp else 14.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = WrType.Body.copy(color = Wr.Ink3))
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = WrType.Body,
                    singleLine = singleLine,
                    minLines = minLines,
                    cursorBrush = SolidColor(Wr.Forest),
                    interactionSource = source,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (suffix != null) {
                Spacer(Modifier.width(8.dp))
                Text(suffix, style = WrType.Body.copy(color = Wr.Ink2))
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = WrType.Caption.copy(color = Wr.Clay))
        }
    }
}

@Composable
fun SelectField(
    value: String,
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null
) {
    Column(modifier) {
        if (label != null) FieldLabel(label)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(WrShape.md)
                .background(Wr.Ivory)
                .border(BorderStroke(1.dp, Wr.Line), WrShape.md)
                .rowPress(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(value.ifBlank { placeholder }, style = WrType.Body.copy(color = if (value.isBlank()) Wr.Ink3 else Wr.Ink), maxLines = 1)
                if (supporting != null && value.isNotBlank()) Text(supporting, style = WrType.Caption, maxLines = 1)
            }
            Icon(Icons.Rounded.UnfoldMore, null, tint = Wr.Ink3, modifier = Modifier.size(20.dp))
        }
    }
}

/* ============================================================== swipe to confirm */

/**
 * Slide-to-confirm. Used for writes the operator makes while walking a field with a
 * knapsack on: a tap in a pocket or a stumble must never mark a zone treated.
 */
@Composable
fun SwipeToConfirm(
    text: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Wr.Forest,
    disabledText: String = text
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val offset = remember { Animatable(0f) }
    var trackW by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val thumb = with(density) { 48.dp.toPx() }
    val pad = with(density) { 4.dp.toPx() }
    val max = (trackW - thumb - pad * 2).coerceAtLeast(1f)
    val progress = (offset.value / max).coerceIn(0f, 1f)
    val bg by animateColorAsState(if (enabled) color else Wr.CreamDeep, label = "sbg")

    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(WrShape.pill)
            .background(bg)
            .onSizeChanged { trackW = it.width.toFloat() }
            .pointerInput(enabled, max) {
                if (!enabled) return@pointerInput
                var armed = false
                detectHorizontalDragGestures(
                    onDragStart = { armed = false },
                    onDragEnd = {
                        scope.launch {
                            if (offset.value >= max * 0.86f) {
                                offset.animateTo(max, tween(120))
                                done = true
                                haptics.confirm()
                                onConfirmed()
                                kotlinx.coroutines.delay(600)
                                done = false
                                offset.snapTo(0f)
                            } else {
                                offset.animateTo(0f, WrMotion.settle())
                            }
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, max)) }
                        if (!armed && offset.value > max * 0.86f) { armed = true; haptics.threshold() }
                        if (armed && offset.value < max * 0.8f) armed = false
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // shimmer on the label hints at the gesture
        Text(
            if (enabled) text else disabledText,
            style = WrType.Button.copy(color = if (enabled) Color.White.copy(alpha = 0.92f - progress * 0.7f) else Wr.Ink3),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp).then(if (enabled && !done) Modifier.shimmer() else Modifier)
        )
        Box(
            Modifier
                .padding(4.dp)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled) Color.White else Wr.Line),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (done) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward, null,
                tint = if (enabled) color else Wr.Ink3, modifier = Modifier.size(22.dp)
            )
        }
    }
}

/* ============================================================== bars */

/** Pinned bottom action area; a soft fade above it instead of a hard hairline. */
@Composable
fun BottomActions(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(18.dp)
                .background(Brush.verticalGradient(listOf(Wr.Cream.copy(alpha = 0f), Wr.Cream)))
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(Wr.Cream)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}
