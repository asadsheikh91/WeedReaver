package com.example.andriodfypprototype.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrMotion
import com.example.andriodfypprototype.ui.theme.WrShape
import com.example.andriodfypprototype.ui.theme.WrType

/* ============================================================== surfaces */

@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    color: Color = Wr.Ivory,
    border: Color? = Wr.Line,
    shape: Shape = WrShape.lg,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(color)
        .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), shape) else Modifier)
    Column(
        (if (onClick != null) base.rowPress(onClick = onClick) else base).padding(padding),
        content = content
    )
}

/** Card whose children are full-bleed rows separated by inset hairlines. */
@Composable
fun ListCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(WrShape.lg)
            .background(Wr.Ivory)
            .border(BorderStroke(1.dp, Wr.Line), WrShape.lg),
        content = content
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, inset: Dp = 0.dp, color: Color = Wr.Line) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(1.dp)
            .background(color)
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    info: (() -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = WrType.TitleM)
        if (info != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.Info, "About $title", tint = Wr.Ink3,
                modifier = Modifier.size(28.dp).clip(CircleShape).pressable(onClick = info).padding(6.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = WrType.Label.copy(color = Wr.Forest2, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clip(WrShape.pill).pressable(onClick = onAction).padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/** Tinted note. Used sparingly: one per screen at most, for things the operator must act on. */
@Composable
fun Notice(
    title: String,
    body: String? = null,
    icon: ImageVector? = null,
    tone: Tone = Tone.Wheat,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(WrShape.md)
            .background(tone.bg)
            .border(BorderStroke(1.dp, tone.line), WrShape.md)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tone.ink, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = WrType.TitleS.copy(color = tone.ink))
            if (body != null) {
                Spacer(Modifier.height(3.dp))
                Text(body, style = WrType.BodyS.copy(color = tone.ink.copy(alpha = 0.82f)))
            }
            if (action != null && onAction != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.clip(WrShape.pill).pressable(onClick = onAction).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(action, style = WrType.Label.copy(color = tone.ink, fontWeight = FontWeight.SemiBold))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = tone.ink, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

enum class Tone(val ink: Color, val bg: Color, val line: Color, val solid: Color) {
    Forest(Wr.Forest, Wr.SageTint, Wr.SageLine, Wr.Forest),
    Moss(Wr.Forest, Wr.Sage, Wr.SageLine, Wr.Moss),
    Wheat(Wr.WheatInk, Wr.WheatBg, Wr.WheatLine, Wr.Wheat),
    Clay(Wr.ClayInk, Wr.ClayBg, Wr.ClayLine, Wr.Clay),
    Slate(Wr.Slate, Wr.SlateBg, Color(0xFFCCDADD), Wr.Slate),
    Neutral(Wr.Ink2, Wr.CreamDeep, Wr.Line, Wr.Ink3)
}

/* ============================================================== status */

@Composable
fun StatusPill(
    text: String,
    tone: Tone = Tone.Neutral,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    icon: ImageVector? = null,
    solid: Boolean = false
) {
    Row(
        modifier
            .clip(WrShape.pill)
            .background(if (solid) tone.solid else tone.bg)
            .padding(start = if (dot || icon != null) 8.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (solid) Color.White else tone.solid))
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(icon, null, tint = if (solid) Color.White else tone.ink, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
            color = if (solid) Color.White else tone.ink, maxLines = 1
        )
    }
}

@Composable
fun CountBadge(n: Int, modifier: Modifier = Modifier, color: Color = Wr.Clay) {
    if (n <= 0) return
    Box(
        modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(WrShape.pill).background(color).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(if (n > 99) "99+" else "$n", fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/* ============================================================== rows */

@Composable
fun IconTile(
    icon: ImageVector,
    tone: Tone = Tone.Forest,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tone.bg),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = tone.ink, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: Tone = Tone.Forest,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    chevron: Boolean = true,
    titleStyle: TextStyle = WrType.TitleS,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.rowPress(onClick = onClick) else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(14.dp)) }
            icon != null -> { IconTile(icon, tone); Spacer(Modifier.width(14.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = WrType.BodyS, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        if (chevron && onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Wr.Ink3, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun KeyValueRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Wr.Ink,
    mono: Boolean = false,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 11.dp)
) {
    Row(modifier.fillMaxWidth().padding(padding), verticalAlignment = Alignment.Top) {
        Text(key, style = WrType.BodyS, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = if (mono) WrType.Mono.copy(color = valueColor, fontSize = 13.sp)
            else WrType.BodyS.copy(color = valueColor, fontWeight = FontWeight.Medium),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f)
        )
    }
}

/* ============================================================== metrics */

@Composable
fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = Wr.Ink,
    align: Alignment.Horizontal = Alignment.Start
) {
    Column(modifier, horizontalAlignment = align) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = WrType.NumM.copy(color = valueColor))
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = WrType.Caption.copy(color = Wr.Ink2), modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Spacer(Modifier.height(1.dp))
        Text(label, style = WrType.Caption, maxLines = 1)
    }
}

/** A row of stats separated by vertical hairlines. */
@Composable
fun StatStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun VDivider(height: Dp = 28.dp) {
    Box(Modifier.width(1.dp).height(height).background(Wr.Line))
}

@Composable
fun Meter(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Wr.Moss,
    track: Color = Wr.CreamDeep,
    height: Dp = 6.dp,
    marker: Float? = null
) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), WrMotion.slow(), label = "meter")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        if (f > 0f) drawRoundRect(
            color, size = Size((size.width * f).coerceAtLeast(size.height), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
        if (marker != null) {
            val x = size.width * marker.coerceIn(0f, 1f)
            drawLine(Wr.Ink, Offset(x, -3f), Offset(x, size.height + 3f), 2.5f)
        }
    }
}

@Composable
fun Ring(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Wr.Moss,
    track: Color = Wr.CreamDeep,
    stroke: Dp = 5.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), WrMotion.slow(), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val s = stroke.toPx()
            val d = size.minDimension - s
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(s))
            drawArc(color, -90f, 360f * f, false, tl, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/* ============================================================== misc */

@Composable
fun LegendSwatch(color: Color, label: String, modifier: Modifier = Modifier, hatched: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))) {
            drawRect(color)
            if (hatched) {
                var x = -size.height
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.7f), Offset(x, size.height), Offset(x + size.height, 0f), 1.5f)
                    x += 4f
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = WrType.Caption.copy(color = Wr.Ink2))
    }
}

@Composable
fun Avatar(initials: String, modifier: Modifier = Modifier, size: Dp = 44.dp, color: Color = Wr.Forest) {
    Box(
        modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initials,
            style = WrType.TitleM.copy(color = Wr.Cream, fontSize = (size.value * 0.36f).sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
        )
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(Wr.CreamDeep),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = Wr.Ink3, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(title, style = WrType.TitleM, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = WrType.BodyS, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Expand/collapse block with a rotating chevron. */
@Composable
fun Expandable(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val rot by animateFloatAsState(if (expanded) 90f else 0f, WrMotion.standard(), label = "chev")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().rowPress(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = WrType.TitleS)
                if (subtitle != null) Text(subtitle, style = WrType.Caption)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Wr.Ink3, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(content = content)
        }
    }
}

@Composable
fun Elevated(modifier: Modifier = Modifier, shape: Shape = WrShape.lg, elevation: Dp = 10.dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = Color(0xFF1B2A21), spotColor = Color(0x661B2A21))
            .clip(shape)
            .background(Wr.Ivory),
        content = content
    )
}

@Composable
fun animatedTint(target: Color) = animateColorAsState(target, WrMotion.standard(), label = "tint").value

@Composable
fun animatedDp(target: Dp) = animateDpAsState(target, WrMotion.standard(), label = "dp").value
