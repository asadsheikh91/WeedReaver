package com.example.andriodfypprototype.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrMotion
import com.example.andriodfypprototype.ui.theme.WrType

/**
 * Season-over-season control for one mode of action. The dashed line marks the level below
 * which the agronomist treats a population as suspect, so the reader does not have to know
 * what "47%" means to see that it is bad.
 */
@Composable
fun TrendChart(
    values: List<Int>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    line: Color = Wr.Clay,
    threshold: Int = 70,
    height: Dp = 168.dp
) {
    val measurer = rememberTextMeasurer()
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(values) { reveal.snapTo(0f); reveal.animateTo(1f, WrMotion.slow()) }
    val axis = TextStyle(fontSize = 11.sp, color = Wr.Ink3)
    val valueStyle = WrType.TitleS.copy(color = line, fontFeatureSettings = "tnum")

    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val left = 34.dp.toPx()
        val right = 28.dp.toPx()
        val top = 30.dp.toPx()
        val bottom = 26.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        fun yOf(v: Float) = top + h * (1f - v / 100f)
        val step = if (values.size > 1) w / (values.size - 1) else 0f

        listOf(0, 50, 100).forEach { g ->
            val y = yOf(g.toFloat())
            drawLine(Wr.Line, Offset(left, y), Offset(left + w, y), 1f)
            val t = measurer.measure("$g%", axis)
            drawText(t, topLeft = Offset(left - t.size.width - 8.dp.toPx(), y - t.size.height / 2))
        }
        val ty = yOf(threshold.toFloat())
        drawLine(Wr.Moss, Offset(left, ty), Offset(left + w, ty), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        val tl = measurer.measure("Acceptable", axis.copy(color = Wr.Moss))
        drawText(tl, topLeft = Offset(left + 6.dp.toPx(), ty + 3.dp.toPx()))

        val pts = values.mapIndexed { i, v -> Offset(left + i * step, yOf(v.toFloat())) }
        val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val area = Path().apply {
            addPath(path)
            lineTo(pts.last().x, top + h)
            lineTo(pts.first().x, top + h)
            close()
        }
        clipRect(right = if (reveal.value >= 1f) size.width else left + w * reveal.value + 8.dp.toPx()) {
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.18f), line.copy(alpha = 0f)), top, top + h))
            drawPath(path, line, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            pts.forEachIndexed { i, p ->
                drawCircle(Wr.Ivory, 5.dp.toPx(), p)
                drawCircle(line, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                val vt = measurer.measure("${values[i]}%", valueStyle)
                drawText(vt, topLeft = Offset(p.x - vt.size.width / 2, p.y - vt.size.height - 8.dp.toPx()))
            }
        }
        labels.forEachIndexed { i, l ->
            val t = measurer.measure(l, axis)
            val x = (left + i * step - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
            drawText(t, topLeft = Offset(x, size.height - t.size.height))
        }
    }
}

/** Two stacked bars: infestation before (clay) and after (moss), on a shared scale. */
@Composable
fun BeforeAfterBar(before: Float, after: Float, scale: Float, modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(before, after) { reveal.snapTo(0f); reveal.animateTo(1f, WrMotion.slow()) }
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val bh = 8.dp.toPx()
        val gap = 6.dp.toPx()
        val r = CornerRadius(bh / 2, bh / 2)
        drawRoundRect(Wr.CreamDeep, size = Size(size.width, bh), cornerRadius = r)
        drawRoundRect(Wr.CreamDeep, topLeft = Offset(0f, bh + gap), size = Size(size.width, bh), cornerRadius = r)
        val bw = (size.width * (before / scale) * reveal.value).coerceAtLeast(bh)
        val aw = (size.width * (after / scale) * reveal.value).coerceAtLeast(bh)
        drawRoundRect(Wr.Clay.copy(alpha = 0.75f), size = Size(bw, bh), cornerRadius = r)
        drawRoundRect(Wr.Moss, topLeft = Offset(0f, bh + gap), size = Size(aw, bh), cornerRadius = r)
    }
}
