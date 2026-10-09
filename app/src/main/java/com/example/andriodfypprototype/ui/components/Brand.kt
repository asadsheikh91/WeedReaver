package com.example.andriodfypprototype.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.andriodfypprototype.ui.theme.Wr
import com.example.andriodfypprototype.ui.theme.WrType

/**
 * The mark: a single wheat leaf inside a survey reticle. The leaf is the crop the system
 * protects; the reticle is the precision it adds. It is the same geometry as the launcher
 * icon in res/drawable, so the two never drift.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 40.dp, background: Color = Wr.Forest, ink: Color = Wr.Cream) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val c = Offset(s / 2, s / 2)
        drawCircle(background, s / 2, c)
        val r = s * 0.30f
        val stroke = s * 0.045f
        // reticle arcs with gaps at the cardinal ticks
        for (i in 0 until 4) {
            drawArc(
                ink.copy(alpha = 0.55f), -70f + i * 90f, 50f, false,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        // leaf
        val leaf = Path().apply {
            moveTo(c.x - s * 0.13f, c.y + s * 0.17f)
            cubicTo(c.x - s * 0.16f, c.y - s * 0.02f, c.x + s * 0.02f, c.y - s * 0.17f, c.x + s * 0.17f, c.y - s * 0.19f)
            cubicTo(c.x + s * 0.16f, c.y - s * 0.03f, c.x + s * 0.03f, c.y + s * 0.13f, c.x - s * 0.13f, c.y + s * 0.17f)
            close()
        }
        drawPath(leaf, ink)
        drawLine(background, Offset(c.x - s * 0.1f, c.y + s * 0.14f), Offset(c.x + s * 0.12f, c.y - s * 0.14f), s * 0.022f, StrokeCap.Round)
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, ink: Color = Wr.Ink, markSize: Dp = 32.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandMark(size = markSize)
        Spacer(Modifier.width(10.dp))
        Text("WeedReaver", style = WrType.DisplayS.copy(color = ink, fontSize = 22.sp))
    }
}
