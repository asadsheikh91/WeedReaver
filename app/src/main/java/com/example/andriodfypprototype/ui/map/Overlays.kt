package com.example.andriodfypprototype.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.andriodfypprototype.data.Pt
import com.example.andriodfypprototype.data.RasterGrid
import com.example.andriodfypprototype.ui.theme.Wr
import kotlin.math.roundToInt

fun MapCamera.path(points: List<Pt>, close: Boolean = true): Path = Path().apply {
    points.forEachIndexed { i, p ->
        val o = project(p)
        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
    if (close && points.size > 2) close()
}

/** Parcel outline: a soft dark halo under a crisp light line reads on any imagery. */
fun DrawScope.fieldOutline(
    cam: MapCamera,
    poly: List<Pt>,
    color: Color = Color.White,
    width: Float = 2.2f,
    fill: Color? = null,
    dashed: Boolean = false,
    closed: Boolean = true
) {
    if (poly.size < 2) return
    val p = cam.path(poly, closed)
    val w = width.dp.toPx()
    if (fill != null) drawPath(p, fill)
    drawPath(p, Color.Black.copy(alpha = 0.28f), style = Stroke(w + 3.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
    drawPath(
        p, color,
        style = Stroke(
            w, join = StrokeJoin.Round, cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())) else null
        )
    )
}

/** Dims everything outside the parcel so the working area carries the eye. */
fun DrawScope.dimOutside(cam: MapCamera, poly: List<Pt>, alpha: Float = 0.26f) {
    val outer = Path().apply {
        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        addPath(cam.path(poly))
        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
    }
    drawPath(outer, Color(0xFF0E1A12).copy(alpha = alpha))
}

fun DrawScope.heatLayer(cam: MapCamera, heat: ImageBitmap, grid: RasterGrid, poly: List<Pt>, alpha: Float = 1f) {
    val tl = cam.project(grid.originX, grid.originY)
    val w = grid.cols * grid.cellMeters * cam.zoom
    val h = grid.rows * grid.cellMeters * cam.zoom
    clipPath(cam.path(poly)) {
        drawImage(
            heat,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(heat.width, heat.height),
            dstOffset = IntOffset(tl.x.roundToInt(), tl.y.roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            alpha = alpha,
            filterQuality = FilterQuality.None
        )
    }
    // cell lattice once cells are large enough to be tapped individually
    val cellPx = grid.cellMeters * cam.zoom
    if (cellPx > 18f) {
        val a = cam.unproject(Offset.Zero)
        val b = cam.unproject(Offset(size.width, size.height))
        val c0 = ((a.x - grid.originX) / grid.cellMeters).toInt().coerceIn(0, grid.cols)
        val c1 = ((b.x - grid.originX) / grid.cellMeters).toInt().plus(1).coerceIn(0, grid.cols)
        val r0 = ((a.y - grid.originY) / grid.cellMeters).toInt().coerceIn(0, grid.rows)
        val r1 = ((b.y - grid.originY) / grid.cellMeters).toInt().plus(1).coerceIn(0, grid.rows)
        val line = Color.White.copy(alpha = ((cellPx - 18f) / 40f).coerceIn(0f, 0.28f))
        clipPath(cam.path(poly)) {
            for (c in c0..c1) {
                val x = cam.project(grid.originX + c * grid.cellMeters, 0f).x
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
            }
            for (r in r0..r1) {
                val y = cam.project(0f, grid.originY + r * grid.cellMeters).y
                drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
            }
        }
    }
}

fun DrawScope.prescriptionOutline(cam: MapCamera, segs: FloatArray, color: Color = Color.White, alpha: Float = 0.9f) {
    if (segs.isEmpty()) return
    val p = Path()
    var i = 0
    while (i + 3 < segs.size) {
        val a = cam.project(segs[i], segs[i + 1])
        val b = cam.project(segs[i + 2], segs[i + 3])
        p.moveTo(a.x, a.y); p.lineTo(b.x, b.y)
        i += 4
    }
    drawPath(p, Color.Black.copy(alpha = 0.22f * alpha), style = Stroke(3.dp.toPx(), cap = StrokeCap.Square))
    drawPath(p, color.copy(alpha = alpha), style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Square))
}

/** Walking route. Completed legs are solid, pending legs flow toward the next target. */
fun DrawScope.routeLine(cam: MapCamera, pts: List<Pt>, phase: Float, doneLegs: Int = 0) {
    if (pts.size < 2) return
    val w = 4.dp.toPx()
    for (i in 0 until pts.size - 1) {
        val a = cam.project(pts[i])
        val b = cam.project(pts[i + 1])
        val done = i < doneLegs
        drawLine(Color.Black.copy(alpha = 0.32f), a, b, w + 3.dp.toPx(), cap = StrokeCap.Round)
        if (done) {
            drawLine(Wr.Route.copy(alpha = 0.55f), a, b, w, cap = StrokeCap.Round)
        } else {
            val on = 9.dp.toPx(); val off = 7.dp.toPx()
            drawLine(Wr.Forest.copy(alpha = 0.9f), a, b, w, cap = StrokeCap.Round)
            drawLine(
                Wr.Route, a, b, w * 0.55f, cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, off), -(on + off) * phase)
            )
        }
    }
}

/** Location puck: accuracy disc, heading cone, white-ringed dot. */
fun DrawScope.locationPuck(cam: MapCamera, at: Pt, accuracyM: Float, headingDeg: Float?, pulse: Float) {
    val c = cam.project(at)
    val acc = accuracyM * cam.zoom
    drawCircle(Wr.Puck.copy(alpha = 0.13f), acc, c)
    drawCircle(Wr.Puck.copy(alpha = 0.35f), acc, c, style = Stroke(1.dp.toPx()))
    val ring = 7.dp.toPx()
    drawCircle(Wr.Puck.copy(alpha = 0.35f * (1f - pulse)), ring + 16.dp.toPx() * pulse, c)
    if (headingDeg != null) {
        rotate(headingDeg, c) {
            val len = 38.dp.toPx()
            val cone = Path().apply {
                moveTo(c.x, c.y)
                lineTo(c.x - len * 0.42f, c.y - len)
                quadraticTo(c.x, c.y - len * 1.12f, c.x + len * 0.42f, c.y - len)
                close()
            }
            drawPath(cone, Brush.verticalGradient(listOf(Wr.Puck.copy(alpha = 0f), Wr.Puck.copy(alpha = 0.45f)), c.y - len, c.y))
        }
    }
    drawCircle(Color.Black.copy(alpha = 0.18f), ring + 2.5.dp.toPx(), c + Offset(0f, 1.dp.toPx()))
    drawCircle(Color.White, ring + 2.dp.toPx(), c)
    drawCircle(Wr.Puck, ring - 0.5.dp.toPx(), c)
}

/** Captured GNSS trace: fixes as small beads on a line. */
fun DrawScope.gnssTrace(cam: MapCamera, pts: List<Pt>, closed: Boolean) {
    if (pts.isEmpty()) return
    if (closed && pts.size > 2) {
        drawPath(cam.path(pts), Wr.Wheat.copy(alpha = 0.22f))
    }
    val p = cam.path(pts, closed)
    drawPath(p, Color.Black.copy(alpha = 0.3f), style = Stroke(5.5.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
    drawPath(p, Color(0xFFFFD66B), style = Stroke(3.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
    if (cam.zoom > 3f) {
        pts.forEachIndexed { i, q ->
            if (i % 3 == 0) {
                val o = cam.project(q)
                drawCircle(Color.White, 2.dp.toPx(), o)
            }
        }
    }
}
