package com.example.andriodfypprototype.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.andriodfypprototype.data.Pt
import com.example.andriodfypprototype.ui.theme.WrMotion
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Camera over a local metric frame. Zoom is in pixels per metre. The visible area can be
 * inset (a bottom sheet covering the lower half, a floating header) so that "fit" and
 * "centre" mean the part of the map the operator can actually see.
 */
@Stable
class MapCamera {
    var center by mutableStateOf(Pt(0f, 0f))
    var zoom by mutableFloatStateOf(0f)
    var viewport by mutableStateOf(IntSize.Zero)
    var insetTop by mutableFloatStateOf(0f)
    var insetBottom by mutableFloatStateOf(0f)
    var minZoom by mutableFloatStateOf(0.5f)
    var maxZoom by mutableFloatStateOf(60f)
    var world: FloatArray = floatArrayOf(-1e6f, -1e6f, 1e6f, 1e6f)
    var userMoved by mutableStateOf(false)

    val ready get() = zoom > 0f && viewport.width > 0

    private val focusY get() = insetTop + (viewport.height - insetTop - insetBottom) / 2f

    fun project(p: Pt): Offset =
        Offset((p.x - center.x) * zoom + viewport.width / 2f, (p.y - center.y) * zoom + focusY)

    fun project(x: Float, y: Float): Offset =
        Offset((x - center.x) * zoom + viewport.width / 2f, (y - center.y) * zoom + focusY)

    fun unproject(o: Offset): Pt =
        Pt((o.x - viewport.width / 2f) / zoom + center.x, (o.y - focusY) / zoom + center.y)

    fun fitZoom(b: FloatArray, padPx: Float): Float {
        val bw = (b[2] - b[0]).coerceAtLeast(1f)
        val bh = (b[3] - b[1]).coerceAtLeast(1f)
        val vw = viewport.width - padPx * 2
        val vh = viewport.height - insetTop - insetBottom - padPx * 2
        return min(vw / bw, vh / bh).coerceIn(minZoom, maxZoom)
    }

    fun snapTo(b: FloatArray, padPx: Float) {
        zoom = fitZoom(b, padPx)
        center = Pt((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f)
        clamp()
    }

    suspend fun animateTo(target: Pt, targetZoom: Float = zoom, durationMs: Int = 520) {
        val c0 = center
        val z0 = zoom
        val z1 = targetZoom.coerceIn(minZoom, maxZoom)
        animate(0f, 1f, animationSpec = tween(durationMs, easing = WrMotion.Emphasized)) { t, _ ->
            // interpolate in log-zoom so a large zoom change feels even
            zoom = (z0 * Math.pow((z1 / z0).toDouble(), t.toDouble())).toFloat()
            center = Pt(c0.x + (target.x - c0.x) * t, c0.y + (target.y - c0.y) * t)
        }
    }

    suspend fun animateFit(b: FloatArray, padPx: Float) =
        animateTo(Pt((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f), fitZoom(b, padPx))

    fun transform(centroid: Offset, pan: Offset, gestureZoom: Float) {
        if (!ready) return
        val anchor = unproject(centroid)
        zoom = (zoom * gestureZoom).coerceIn(minZoom, maxZoom)
        val after = unproject(centroid)
        center = Pt(center.x + (anchor.x - after.x) - pan.x / zoom, center.y + (anchor.y - after.y) - pan.y / zoom)
        clamp()
        userMoved = true
    }

    fun clamp() {
        center = Pt(center.x.coerceIn(world[0], world[2]), center.y.coerceIn(world[1], world[3]))
    }

    /** Metres per device pixel, for the scale bar. */
    val metersPerPx get() = if (zoom > 0f) 1f / zoom else 0f
}

@Composable
fun rememberMapCamera(): MapCamera = remember { MapCamera() }

/** Places a composable at a world coordinate. Reads the camera in the layout phase only. */
fun Modifier.mapAnchor(camera: MapCamera, at: Pt, ax: Float = 0.5f, ay: Float = 0.5f): Modifier =
    this.layout { m, c ->
        val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
        layout(p.width, p.height) {
            val o = camera.project(at)
            p.place((o.x - p.width * ax).roundToInt(), (o.y - p.height * ay).roundToInt())
        }
    }

@Composable
fun rememberImagery(spec: Aerial.Spec): ImageBitmap? {
    val state = produceState(Aerial.cached(spec.key), spec.key) {
        if (value == null) value = Aerial.load(spec)
    }
    return state.value
}

@Composable
fun MapSurface(
    spec: Aerial.Spec,
    camera: MapCamera,
    fit: FloatArray,
    modifier: Modifier = Modifier,
    fitPadding: Dp = 28.dp,
    insetTop: Dp = 0.dp,
    insetBottom: Dp = 0.dp,
    interactive: Boolean = true,
    onTap: ((Pt) -> Unit)? = null,
    overlay: DrawScope.(MapCamera) -> Unit = {},
    markers: @Composable BoxScope.() -> Unit = {}
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val image = rememberImagery(spec)
    val fade = remember(spec.key) { Animatable(if (image != null) 1f else 0f) }
    LaunchedEffect(image) { if (image != null && fade.value < 1f) fade.animateTo(1f, tween(420)) }

    val padPx = with(density) { fitPadding.toPx() }
    camera.insetTop = with(density) { insetTop.toPx() }
    camera.insetBottom = with(density) { insetBottom.toPx() }
    camera.world = floatArrayOf(spec.minX, spec.minY, spec.maxX, spec.maxY)

    Box(
        modifier
            .clipToBounds()
            .background(Color(0xFFB3AE95))
            .onSizeChanged { sz ->
                val first = camera.viewport == IntSize.Zero
                camera.viewport = sz
                val worldFit = min(sz.width / spec.widthM, sz.height / spec.heightM)
                camera.minZoom = worldFit
                camera.maxZoom = with(density) { 14.dp.toPx() }
                if (first || !camera.userMoved) camera.snapTo(fit, padPx)
            }
            .then(
                if (interactive) Modifier
                    .pointerInput(spec.key) {
                        detectTransformGestures { centroid, pan, z, _ -> camera.transform(centroid, pan, z) }
                    }
                    .pointerInput(spec.key, onTap) {
                        detectTapGestures(
                            onDoubleTap = { at ->
                                val anchor = camera.unproject(at)
                                val z0 = camera.zoom
                                val z1 = (z0 * 2f).coerceAtMost(camera.maxZoom)
                                camera.zoom = z1
                                val after = camera.unproject(at)
                                camera.zoom = z0
                                val target = Pt(camera.center.x + anchor.x - after.x, camera.center.y + anchor.y - after.y)
                                scope.launch {
                                    camera.animateTo(target, z1, 300)
                                    camera.userMoved = true
                                }
                            },
                            onTap = { at -> onTap?.invoke(camera.unproject(at)) }
                        )
                    }
                else Modifier
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (!camera.ready) return@Canvas
            if (image == null || fade.value < 1f) drawTilePlaceholder(camera)
            if (image != null) {
                val sx = image.width / spec.widthM
                // only sample the visible part of the mosaic
                val v0 = camera.unproject(Offset.Zero)
                val v1 = camera.unproject(Offset(size.width, size.height))
                val x0 = max(spec.minX, v0.x); val y0 = max(spec.minY, v0.y)
                val x1 = min(spec.maxX, v1.x); val y1 = min(spec.maxY, v1.y)
                if (x1 > x0 && y1 > y0) {
                    val srcX = ((x0 - spec.minX) * sx).toInt().coerceIn(0, image.width - 1)
                    val srcY = ((y0 - spec.minY) * sx).toInt().coerceIn(0, image.height - 1)
                    val srcW = ((x1 - x0) * sx).roundToInt().coerceIn(1, image.width - srcX)
                    val srcH = ((y1 - y0) * sx).roundToInt().coerceIn(1, image.height - srcY)
                    val dst = camera.project(spec.minX + srcX / sx, spec.minY + srcY / sx)
                    drawImage(
                        image,
                        srcOffset = IntOffset(srcX, srcY),
                        srcSize = IntSize(srcW, srcH),
                        dstOffset = IntOffset(dst.x.roundToInt(), dst.y.roundToInt()),
                        dstSize = IntSize((srcW / sx * camera.zoom).roundToInt(), (srcH / sx * camera.zoom).roundToInt()),
                        alpha = fade.value,
                        filterQuality = FilterQuality.Low
                    )
                }
            }
            overlay(camera)
        }
        if (camera.ready) markers()
    }
}

private fun DrawScope.drawTilePlaceholder(camera: MapCamera) {
    // 256-px-style tile lattice in world space, the way a real map shows unloaded tiles
    val step = 64f
    val a = camera.unproject(Offset.Zero)
    val b = camera.unproject(Offset(size.width, size.height))
    var x = (kotlin.math.floor(a.x / step) * step)
    val line = Color(0x22FFFFFF)
    while (x < b.x) {
        val sx = camera.project(x, 0f).x
        drawLine(line, Offset(sx, 0f), Offset(sx, size.height), 1f)
        x += step
    }
    var y = (kotlin.math.floor(a.y / step) * step)
    while (y < b.y) {
        val sy = camera.project(0f, y).y
        drawLine(line, Offset(0f, sy), Offset(size.width, sy), 1f)
        y += step
    }
}

/* ============================================================== chrome */

@Composable
fun ScaleBar(camera: MapCamera, modifier: Modifier = Modifier, dark: Boolean = false) {
    val density = LocalDensity.current
    val maxPx = with(density) { 84.dp.toPx() }
    val mpp = camera.metersPerPx
    if (mpp <= 0f) return
    val options = intArrayOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000)
    val meters = options.lastOrNull { it / mpp <= maxPx } ?: 1
    val barPx = meters / mpp
    val ink = if (dark) Color.White else Color(0xFF1B2A21)
    val halo = if (dark) Color(0x66000000) else Color(0xCCFFFCF6)
    Column(modifier) {
        Text(
            if (meters >= 1000) "${meters / 1000} km" else "$meters m",
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = ink,
            modifier = Modifier.background(halo, androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Spacer(Modifier.height(2.dp))
        Canvas(Modifier.width(with(density) { barPx.toDp() }).height(6.dp)) {
            val y = size.height - 1.5f
            drawLine(halo, Offset(0f, y), Offset(size.width, y), 5f)
            drawLine(halo, Offset(1f, 0f), Offset(1f, size.height), 5f)
            drawLine(halo, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 5f)
            drawLine(ink, Offset(0f, y), Offset(size.width, y), 2.2f)
            drawLine(ink, Offset(1f, 0f), Offset(1f, size.height), 2.2f)
            drawLine(ink, Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 2.2f)
        }
    }
}

@Composable
fun rememberPulse(periodMs: Int = 1800): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs), RepeatMode.Restart), label = "p")
    return v
}

@Composable
fun rememberDashPhase(periodMs: Int = 900): Float {
    val t = rememberInfiniteTransition(label = "dash")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = androidx.compose.animation.core.LinearEasing)), label = "d")
    return v
}
