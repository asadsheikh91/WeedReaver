package com.example.andriodfypprototype.ui.map

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.andriodfypprototype.data.Geo
import com.example.andriodfypprototype.data.InfestationField
import com.example.andriodfypprototype.data.Landscape
import com.example.andriodfypprototype.data.Noise
import com.example.andriodfypprototype.data.Pt
import com.example.andriodfypprototype.data.WeedClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic orthomosaic.
 *
 * Stands in for the georeferenced mosaic that OpenDroneMap produces from the survey flight.
 * The landscape is modelled on the Rechna Doab in January: the canal-colony killa grid split
 * into inheritance strips, flood-irrigation beds inside each strip, earthen bunds, lined
 * watercourses, kikar and shisham on the field edges, a dera with its buffalo shade and a
 * flowering mustard strip or two. Weed patches inside the surveyed parcel are rendered into
 * the canopy so the heat overlay lines up with something the operator can actually see.
 *
 * A real build replaces this with cached raster tiles; nothing above this file cares.
 */
object Aerial {

    data class Spec(
        val key: String,
        val target: List<Pt>?,
        val land: Landscape,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val ppm: Float,
        val infest: InfestationField? = null
    ) {
        val widthM get() = maxX - minX
        val heightM get() = maxY - minY
    }

    private const val KW = 60.3f
    private const val KH = 67.0f

    private val cache = HashMap<String, ImageBitmap>()
    private val locks = HashMap<String, Mutex>()

    fun cached(key: String): ImageBitmap? = synchronized(cache) { cache[key] }

    suspend fun load(spec: Spec): ImageBitmap {
        cached(spec.key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(spec.key) { Mutex() } }
        return lock.withLock {
            cached(spec.key) ?: withContext(Dispatchers.Default) {
                timed(spec) { render(spec) }.asImageBitmap().also { synchronized(cache) { cache[spec.key] = it } }
            }
        }
    }

    private inline fun timed(spec: Spec, block: () -> Bitmap): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        return block().also {
            android.util.Log.d("Aerial", "${spec.key}: ${it.width}x${it.height} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        }
    }

    fun spec(
        key: String,
        target: List<Pt>?,
        land: Landscape,
        infest: InfestationField? = null,
        margin: Float = 95f
    ): Spec {
        val b = if (target != null) Geo.bounds(target) else floatArrayOf(0f, 0f, 120f, 120f)
        val minX = b[0] - margin
        val minY = b[1] - margin
        val maxX = b[2] + margin
        val maxY = b[3] + margin
        val longest = max(maxX - minX, maxY - minY)
        val ppm = (1700f / longest).coerceIn(2.6f, 5.2f)
        return Spec(key, target, land, minX, minY, maxX, maxY, ppm, infest)
    }

    // ------------------------------------------------------------------ crops

    private enum class Crop { WHEAT, WHEAT_THIN, BERSEEM, MUSTARD, CANE, FALLOW, POTATO }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun cropFor(h: Float, mustard: Float): Crop = when {
        h < mustard -> Crop.MUSTARD
        h < mustard + 0.50f -> Crop.WHEAT
        h < mustard + 0.60f -> Crop.WHEAT_THIN
        h < mustard + 0.71f -> Crop.BERSEEM
        h < mustard + 0.78f -> Crop.CANE
        h < mustard + 0.88f -> Crop.FALLOW
        else -> Crop.POTATO
    }

    // ------------------------------------------------------------------ render

    fun render(spec: Spec): Bitmap {
        val w = (spec.widthM * spec.ppm).roundToInt()
        val h = (spec.heightM * spec.ppm).roundToInt()
        val seed = spec.land.seed
        val px = IntArray(w * h)
        val inv = 1f / spec.ppm

        // Low-frequency fields sampled on a coarse lattice and interpolated: vigour, moisture,
        // mid-scale mottling. Sampling per pixel would cost ten times as much for no visible gain.
        val step = 4
        val lw = w / step + 2
        val lh = h / step + 2
        val vigour = FloatArray(lw * lh)
        val moist = FloatArray(lw * lh)
        val mottle = FloatArray(lw * lh)
        parallelRows(lh) { j ->
            for (i in 0 until lw) {
                val x = spec.minX + i * step * inv
                val y = spec.minY + j * step * inv
                vigour[j * lw + i] = Noise.fbm(x * 0.02f, y * 0.02f, seed + 11, 4)
                moist[j * lw + i] = Noise.fbm(x * 0.007f, y * 0.007f, seed + 23, 3)
                mottle[j * lw + i] = Noise.fbm(x * 0.16f, y * 0.16f, seed + 29, 3)
            }
        }
        fun lerpField(a: FloatArray, xi: Int, yi: Int): Float {
            val i = xi / step
            val j = yi / step
            val tx = (xi - i * step) / step.toFloat()
            val ty = (yi - j * step) / step.toFloat()
            val p = a[j * lw + i]; val q = a[j * lw + i + 1]
            val r = a[(j + 1) * lw + i]; val s = a[(j + 1) * lw + i + 1]
            val top = p + (q - p) * tx
            return top + ((r + (s - r) * tx) - top) * ty
        }

        val target = spec.target
        val tb = target?.let { Geo.bounds(it) }
        val infest = spec.infest

        // Weed surface on a 0.5 m lattice over the surveyed parcel.
        val wStep = 0.5f
        val ww = if (tb != null) ((tb[2] - tb[0]) / wStep).toInt() + 2 else 0
        val wh = if (tb != null) ((tb[3] - tb[1]) / wStep).toInt() + 2 else 0
        val weed = FloatArray(ww * wh)
        val broad = BooleanArray(ww * wh)
        if (infest != null && tb != null) {
            parallelRows(wh) { j ->
                for (i in 0 until ww) {
                    val x = tb[0] + i * wStep
                    val y = tb[1] + j * wStep
                    weed[j * ww + i] = infest.valueAt(x, y)
                    broad[j * ww + i] = infest.dominantAt(x, y)?.weedClass == WeedClass.BROADLEAF
                }
            }
        }

        parallelRows(h) { yi ->
            val y = spec.minY + (yi + 0.5f) * inv
            // scanline spans of the target polygon
            var spans: FloatArray? = null
            if (target != null && tb != null && y >= tb[1] - 2f && y <= tb[3] + 2f) {
                val xs = ArrayList<Float>(8)
                for (k in target.indices) {
                    val a = target[k]
                    val b = target[(k + 1) % target.size]
                    if ((a.y > y) != (b.y > y)) xs.add(a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y))
                }
                xs.sort()
                spans = xs.toFloatArray()
            }
            for (xi in 0 until w) {
                val x = spec.minX + (xi + 0.5f) * inv
                val vig = lerpField(vigour, xi, yi)
                val mo = lerpField(moist, xi, yi)
                val mot = lerpField(mottle, xi, yi)

                // ---- killa grid and inheritance strips of uneven width
                val ki = floor(x / KW).toInt()
                val kj = floor(y / KH).toInt()
                val u = x - ki * KW
                val v = y - kj * KH
                val hk = Noise.white(ki, kj, seed)
                val split = when {
                    hk < 0.36f -> 1
                    hk < 0.68f -> 2
                    hk < 0.84f -> 3
                    else -> -2
                }
                val j1 = Noise.white(ki + 3, kj - 7, seed + 1)
                val j2 = Noise.white(ki - 5, kj + 2, seed + 2)
                val sub: Int
                val du: Float
                val dv: Float
                val along: Float
                val across: Float
                if (split > 0) {
                    val c1 = KW * (if (split == 2) 0.38f + j1 * 0.24f else 0.24f + j1 * 0.14f)
                    val c2 = if (split == 3) KW * (0.58f + j2 * 0.16f) else KW
                    val lo: Float
                    val hi: Float
                    when {
                        split == 1 -> { sub = 0; lo = 0f; hi = KW }
                        u <= c1 -> { sub = 0; lo = 0f; hi = c1 }
                        u <= c2 -> { sub = 1; lo = c1; hi = c2 }
                        else -> { sub = 2; lo = c2; hi = KW }
                    }
                    du = min(u - lo, hi - u)
                    dv = min(v, KH - v)
                    along = v; across = u - lo
                } else {
                    val cut = KH * (0.4f + j1 * 0.2f)
                    sub = if (v > cut) 1 else 0
                    du = min(u, KW - u)
                    dv = if (sub == 0) min(v, cut - v) else min(v - cut, KH - v)
                    along = u; across = if (sub == 0) v else v - cut
                }
                val killaEdge = min(min(u, KW - u), min(v, KH - v))
                val edge = min(du, dv)
                val ph = Noise.white(ki * 7 + sub * 3, kj * 13 + sub, seed + 3)
                var crop = cropFor(ph, spec.land.mustardBias)
                var bedLen = 8f + Noise.white(ki, kj + sub * 5, seed + 5) * 6f
                var laneW = 9f + Noise.white(ki + sub, kj, seed + 6) * 8f
                var parcelKey = ki * 31 + kj * 17 + sub
                val bundWobble = (Noise.value(x * 0.5f, y * 0.5f, seed + 67) - 0.5f) * 0.5f
                var bundW = (if (killaEdge < 1.5f) 1.25f else 0.75f) + bundWobble
                var isBund = edge < bundW || killaEdge < 1.1f + bundWobble
                var margin = edge

                // ---- the surveyed parcel overrides whatever the grid says
                var inTarget = false
                var tEdge = 99f
                if (spans != null && target != null) {
                    var k = 0
                    while (k + 1 < spans.size) {
                        if (x >= spans[k] && x <= spans[k + 1]) { inTarget = true; break }
                        k += 2
                    }
                    if (tb != null && x >= tb[0] - 2f && x <= tb[2] + 2f) {
                        for (e in target.indices) {
                            val a = target[e]
                            val b = target[(e + 1) % target.size]
                            val d = segDist(x, y, a.x, a.y, b.x, b.y)
                            if (d < tEdge) tEdge = d
                        }
                    }
                    if (inTarget) {
                        crop = Crop.WHEAT
                        bedLen = 11f
                        laneW = 13f
                        parcelKey = -1
                        bundW = 1.2f + bundWobble
                        isBund = tEdge < bundW
                        margin = tEdge
                    } else if (tEdge < 1.2f + bundWobble) {
                        isBund = true
                    }
                }

                // per-parcel and per-bed tone: every kiara is watered and fertilised on its own day
                val pt = Noise.white(parcelKey, 991, seed + 13) - 0.5f
                val bed = floor(along / bedLen).toInt()
                val lane = floor(across / laneW).toInt()
                val bt = Noise.white(bed * 13 + lane, parcelKey + lane * 7, seed + 17)
                val watered = bt < 0.1f
                val bedTone = (bt - 0.5f) * 8f

                var r: Float
                var g: Float
                var b: Float
                val tex = Noise.value(x * 1.3f, y * 1.3f, seed + 31)

                when (crop) {
                    Crop.WHEAT -> {
                        val t = vig * 0.55f + mot * 0.45f
                        r = 90f + t * 38f; g = 114f + t * 34f; b = 60f + t * 16f
                        val band = sin(across * 2.618f) * 2.5f
                        r += band; g += band
                    }
                    Crop.WHEAT_THIN -> {
                        val soil = Noise.fbm(x * 0.5f, y * 0.5f, seed + 7, 2)
                        r = 116f + vig * 22f + soil * 34f; g = 124f + vig * 20f + soil * 16f; b = 82f + soil * 22f
                    }
                    Crop.BERSEEM -> {
                        val t = vig * 0.5f + mot * 0.5f
                        r = 66f + t * 30f; g = 104f + t * 34f; b = 54f + t * 14f
                        val cut = ((along / 16f).toInt() + ki) % 3 == 0
                        if (cut) { r += 34f; g += 18f; b += 24f }
                    }
                    Crop.MUSTARD -> {
                        val bloom = mot * 0.6f + tex * 0.4f
                        r = 150f + bloom * 50f; g = 148f + bloom * 38f; b = 66f + bloom * 10f
                    }
                    Crop.CANE -> {
                        val coarse = Noise.value(x * 0.6f, y * 0.6f, seed + 19)
                        r = 72f + coarse * 30f; g = 92f + coarse * 28f; b = 62f + coarse * 16f
                        r += sin(across * 4.8f) * 4f; g += sin(across * 4.8f) * 4f
                    }
                    Crop.FALLOW -> {
                        val furrow = sin(along * 7.6f + tex * 2f) * 6f
                        r = 158f + vig * 20f + mot * 16f + furrow
                        g = 138f + vig * 16f + mot * 12f + furrow
                        b = 108f + vig * 12f + mot * 8f + furrow
                        if (mo > 0.56f) { r -= 24f; g -= 22f; b -= 16f }
                    }
                    Crop.POTATO -> {
                        val ridge = sin(across * 6.9f)
                        if (ridge > 0.1f) { r = 98f + mot * 24f; g = 116f + mot * 22f; b = 70f } else {
                            r = 134f + tex * 16f; g = 118f + tex * 12f; b = 94f + tex * 10f
                        }
                    }
                }

                r += pt * 16f + bedTone; g += pt * 12f + bedTone; b += pt * 8f + bedTone * 0.6f
                if (watered && crop != Crop.MUSTARD && crop != Crop.CANE) { r -= 12f; g -= 7f; b -= 5f }

                // low ridges between beds and down the lane lines
                if (!isBund && crop != Crop.CANE) {
                    val a2 = along % bedLen
                    val c2 = across % laneW
                    val ridge = min(min(a2, bedLen - a2), min(c2, laneW - c2))
                    if (ridge < 0.35f) {
                        val k = 1f - ridge / 0.35f
                        r += 16f * k; g += 12f * k; b += 12f * k
                    }
                }

                if (!isBund && mo > 0.66f && crop != Crop.MUSTARD) {
                    val k = (mo - 0.66f) * 2f
                    r -= 20f * k; g -= 12f * k; b -= 8f * k
                }

                // weeds inside the surveyed parcel, rendered into the canopy
                if (inTarget && tb != null && !isBund && ww > 0) {
                    val wi = ((x - tb[0]) / wStep).toInt().coerceIn(0, ww - 1)
                    val wj = ((y - tb[1]) / wStep).toInt().coerceIn(0, wh - 1)
                    val wv = weed[wj * ww + wi]
                    if (wv > 0.03f) {
                        val vis = kotlin.math.sqrt(wv)
                        if (broad[wj * ww + wi]) {
                            // broadleaf: darker, coarse rosettes breaking the drill lines
                            val speck = Noise.value(x * 2.6f, y * 2.6f, seed + 53)
                            val k = (vis * (0.45f + speck * 0.7f)).coerceAtMost(0.95f)
                            r += (58f - r) * k * 0.75f; g += (86f - g) * k * 0.6f; b += (46f - b) * k * 0.6f
                            if (speck > 0.68f) { r += 34f * vis; g += 38f * vis; b += 22f * vis }
                        } else {
                            // Phalaris minor: paler, glaucous, taller than the crop around it
                            val k = (vis * (0.7f + tex * 0.45f)).coerceAtMost(1f)
                            r += (150f - r) * k * 0.72f; g += (170f - g) * k * 0.62f; b += (130f - b) * k * 0.75f
                        }
                    }
                }

                if (isBund) {
                    val bn = Noise.fbm(x * 0.9f, y * 0.9f, seed + 61, 2)
                    val grassK = ((bn - 0.42f) * 3f).coerceIn(0f, 1f)
                    r = 164f + (112f - 164f) * grassK + tex * 10f
                    g = 150f + (122f - 150f) * grassK + tex * 10f
                    b = 118f + (82f - 118f) * grassK + tex * 6f
                } else if (margin < bundW + 0.8f) {
                    // grassy, weedy margin inside the bund
                    val k = 1f - (margin - bundW) / 0.8f
                    r -= 12f * k; g -= 4f * k; b -= 10f * k
                }

                px[yi * w + xi] = rgb(r.toInt(), g.toInt(), b.toInt())
            }
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)

        val c = Canvas(bmp)
        val painter = Painter(c, spec)
        val rng = Random(seed)
        val trees = ArrayList<Tree>()

        spec.land.canal?.let { painter.canal(it, trees, rng) }
        spec.land.watercourse?.let { painter.watercourse(it, trees, rng) }
        spec.land.road?.let { painter.road(it, trees, rng) }
        spec.land.village?.let { painter.village(it, trees, rng) }
        spec.land.farmstead?.let { painter.farmstead(it, trees, rng) }
        spec.land.tubewell?.let { painter.tubewell(it, trees, rng) }

        // kikar on bund intersections, away from the surveyed parcel
        var ki = floor(spec.minX / KW).toInt()
        while (ki * KW < spec.maxX) {
            var kj = floor(spec.minY / KH).toInt()
            while (kj * KH < spec.maxY) {
                val hh = Noise.white(ki, kj, seed + 97)
                val tx = ki * KW
                val ty = kj * KH
                val clear = target == null || !nearPoly(target, tx, ty, 8f)
                if (hh < 0.22f && clear) trees.add(Tree(tx + (hh - 0.1f) * 20f, ty + 0.6f, 2.8f + hh * 10f, 0))
                kj++
            }
            ki++
        }

        painter.trees(trees)

        // Final grade over both layers so everything shares one camera: a touch of
        // desaturation and lift (winter haze), then sensor grain in 2 x 2 blocks.
        val out = IntArray(w * h)
        bmp.getPixels(out, 0, w, 0, 0, w, h)
        parallelRows(h) { yy ->
            for (xx in 0 until w) {
                val i = yy * w + xx
                val p = out[i]
                val r0 = ((p shr 16) and 0xFF).toFloat()
                val g0 = ((p shr 8) and 0xFF).toFloat()
                val b0 = (p and 0xFF).toFloat()
                val l = r0 * 0.3f + g0 * 0.59f + b0 * 0.11f
                val grain = (Noise.white(xx / 2, yy / 2, seed + 41) - 0.5f) * 9f
                val r = (l + (r0 - l) * 0.82f) * 0.9f + 17f + grain
                val g = (l + (g0 - l) * 0.82f) * 0.9f + 16f + grain
                val b = (l + (b0 - l) * 0.82f) * 0.88f + 16f + grain
                out[i] = rgb(r.toInt(), g.toInt(), b.toInt())
            }
        }
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Rows are independent, so the pixel passes fan out across every core. */
    private inline fun parallelRows(rows: Int, crossinline body: (Int) -> Unit) {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        if (threads == 1) { for (r in 0 until rows) body(r); return }
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val workers = List(threads) {
            Thread {
                while (true) {
                    val r = next.getAndIncrement()
                    if (r >= rows) break
                    body(r)
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun nearPoly(poly: List<Pt>, x: Float, y: Float, d: Float): Boolean {
        if (Geo.contains(poly, x, y)) return true
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            if (segDist(x, y, a.x, a.y, b.x, b.y) < d) return true
        }
        return false
    }

    // ------------------------------------------------------------------ vector layer

    private data class Tree(val x: Float, val y: Float, val r: Float, val kind: Int)

    private class Painter(val c: Canvas, val s: Aerial.Spec) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun X(x: Float) = (x - s.minX) * s.ppm
        fun Y(y: Float) = (y - s.minY) * s.ppm
        fun M(m: Float) = m * s.ppm

        fun path(pts: List<Pt>): Path = Path().apply {
            pts.forEachIndexed { i, q -> if (i == 0) moveTo(X(q.x), Y(q.y)) else lineTo(X(q.x), Y(q.y)) }
        }

        fun stroke(pts: List<Pt>, widthM: Float, color: Int, blur: Float = 0f) {
            p.reset(); p.isAntiAlias = true
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.strokeWidth = M(widthM)
            p.color = color
            if (blur > 0f) p.maskFilter = BlurMaskFilter(M(blur), BlurMaskFilter.Blur.NORMAL)
            c.drawPath(path(pts), p)
            p.maskFilter = null
        }

        private fun offsetLine(pts: List<Pt>, d: Float): List<Pt> {
            if (pts.size < 2) return pts
            return pts.mapIndexed { i, q ->
                val a = pts[max(0, i - 1)]; val b = pts[min(pts.lastIndex, i + 1)]
                val dx = b.x - a.x; val dy = b.y - a.y
                val l = hypot(dx, dy).coerceAtLeast(0.001f)
                Pt(q.x - dy / l * d, q.y + dx / l * d)
            }
        }

        private fun sample(pts: List<Pt>, every: Float, rng: Random, jitter: Float, f: (Pt) -> Unit) {
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                val len = a.dist(b)
                var t = rng.nextFloat() * every
                while (t < len) {
                    val k = t / len
                    f(Pt(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k))
                    t += every * (0.6f + rng.nextFloat() * jitter)
                }
            }
        }

        fun canal(line: List<Pt>, trees: MutableList<Tree>, rng: Random) {
            stroke(line, 40f, argb(255, 170, 154, 120), 1.2f)
            stroke(line, 34f, argb(255, 184, 168, 134))
            // service tracks on both embankments
            stroke(offsetLine(line, 13.5f), 3.2f, argb(255, 200, 186, 152))
            stroke(offsetLine(line, -13.5f), 3.2f, argb(255, 200, 186, 152))
            stroke(line, 19f, argb(255, 150, 142, 112))
            stroke(line, 17f, argb(255, 88, 104, 88))
            stroke(line, 10f, argb(255, 76, 94, 82), 1.8f)
            stroke(offsetLine(line, 7.9f), 0.6f, argb(110, 210, 200, 170))
            // eucalyptus rows planted along the canal
            listOf(19.5f, -19.5f).forEach { off ->
                sample(offsetLine(line, off), 5.5f, rng, 0.8f) { q -> trees.add(Tree(q.x, q.y, 2.4f + rng.nextFloat() * 1.6f, 1)) }
            }
        }

        fun watercourse(line: List<Pt>, trees: MutableList<Tree>, rng: Random) {
            stroke(line, 4.2f, argb(255, 150, 136, 104))
            stroke(line, 3.0f, argb(255, 118, 122, 88))
            stroke(line, 1.3f, argb(255, 62, 80, 70))
            sample(offsetLine(line, 3.2f), 16f, rng, 2.4f) { q ->
                if (rng.nextFloat() < 0.45f) trees.add(Tree(q.x, q.y, 2.6f + rng.nextFloat() * 2.8f, 0))
            }
        }

        fun road(line: List<Pt>, trees: MutableList<Tree>, rng: Random) {
            stroke(line, 6.4f, argb(170, 176, 160, 128), 0.8f)
            stroke(line, 4.8f, argb(255, 194, 178, 144))
            stroke(offsetLine(line, 1.05f), 0.55f, argb(150, 164, 146, 114))
            stroke(offsetLine(line, -1.05f), 0.55f, argb(150, 164, 146, 114))
            listOf(5.5f, -5.5f).forEach { off ->
                sample(offsetLine(line, off), 11f, rng, 1.6f) { q ->
                    if (rng.nextFloat() < 0.5f) trees.add(Tree(q.x, q.y, 3f + rng.nextFloat() * 2.6f, 2))
                }
            }
        }

        fun building(x: Float, y: Float, w: Float, h: Float, roof: Int, height: Float = 3.2f) {
            p.reset(); p.isAntiAlias = true
            p.color = argb(110, 20, 24, 18)
            p.maskFilter = BlurMaskFilter(M(0.6f), BlurMaskFilter.Blur.NORMAL)
            c.drawRect(RectF(X(x - height * 0.45f), Y(y - height * 0.35f), X(x + w - height * 0.45f), Y(y + h - height * 0.35f)), p)
            p.maskFilter = null
            p.color = roof
            c.drawRect(RectF(X(x), Y(y), X(x + w), Y(y + h)), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = M(0.35f)
            p.color = argb(90, 255, 255, 240)
            c.drawRect(RectF(X(x + 0.2f), Y(y + 0.2f), X(x + w - 0.2f), Y(y + h - 0.2f)), p)
            p.style = Paint.Style.FILL
        }

        fun farmstead(at: Pt, trees: MutableList<Tree>, rng: Random) {
            p.reset(); p.isAntiAlias = true
            p.color = argb(255, 176, 158, 124)
            c.drawRoundRect(RectF(X(at.x - 20f), Y(at.y - 14f), X(at.x + 20f), Y(at.y + 16f)), M(2f), M(2f), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = M(0.5f); p.color = argb(255, 150, 120, 96)
            c.drawRect(RectF(X(at.x - 19f), Y(at.y - 13f), X(at.x + 19f), Y(at.y + 15f)), p)
            p.style = Paint.Style.FILL
            building(at.x - 17f, at.y - 11f, 16f, 7f, argb(255, 168, 164, 154))
            building(at.x + 4f, at.y - 11f, 12f, 6f, argb(255, 146, 94, 70))
            // buffalo shade: thatch on poles
            building(at.x + 6f, at.y + 5f, 11f, 7f, argb(255, 150, 136, 98), 2.4f)
            // buffaloes and a toori stack
            p.color = argb(255, 40, 36, 34)
            repeat(4) { i -> c.drawOval(RectF(X(at.x - 12f + i * 2.4f), Y(at.y + 6f + (i % 2)), X(at.x - 10.6f + i * 2.4f), Y(at.y + 8.4f + (i % 2))), p) }
            p.color = argb(255, 214, 196, 138)
            c.drawCircle(X(at.x - 14f), Y(at.y + 12f), M(2.1f), p)
            p.color = argb(255, 96, 82, 62)
            repeat(6) { i -> c.drawCircle(X(at.x - 4f + i * 1.1f), Y(at.y + 13f), M(0.45f), p) }
            repeat(7) { trees.add(Tree(at.x - 24f + rng.nextFloat() * 50f, at.y - 20f + rng.nextFloat() * 42f, 3f + rng.nextFloat() * 3.5f, 0)) }
        }

        fun tubewell(at: Pt, trees: MutableList<Tree>, rng: Random) {
            p.reset(); p.isAntiAlias = true
            p.color = argb(255, 172, 156, 120)
            c.drawRect(RectF(X(at.x - 10f), Y(at.y - 11f), X(at.x + 10f), Y(at.y + 11f)), p)
            building(at.x - 4f, at.y - 6f, 5f, 4f, argb(255, 178, 174, 164), 2.6f)
            // brick sump and the outlet into the khal
            p.color = argb(255, 128, 86, 66)
            c.drawRect(RectF(X(at.x + 2f), Y(at.y - 5f), X(at.x + 5f), Y(at.y - 2f)), p)
            p.color = argb(255, 64, 84, 76)
            c.drawRect(RectF(X(at.x + 2.6f), Y(at.y - 4.4f), X(at.x + 4.4f), Y(at.y - 2.6f)), p)
            stroke(listOf(Pt(at.x + 3.5f, at.y - 2f), Pt(at.x + 3.5f, at.y + 10f), Pt(at.x + 12f, at.y + 12f)), 1.1f, argb(255, 66, 84, 72))
            trees.add(Tree(at.x - 6f, at.y + 5f, 5.2f, 0))
        }

        fun village(at: Pt, trees: MutableList<Tree>, rng: Random) {
            p.reset(); p.isAntiAlias = true
            val blob = Path()
            val n = 28
            for (i in 0..n) {
                val a = i / n.toFloat() * 6.2832f
                val rr = 1f + 0.18f * sin(a * 3f + 1.2f) + 0.1f * sin(a * 5f)
                val q = Pt(at.x + kotlin.math.cos(a) * 95f * rr, at.y + sin(a) * 62f * rr)
                if (i == 0) blob.moveTo(X(q.x), Y(q.y)) else blob.lineTo(X(q.x), Y(q.y))
            }
            blob.close()
            p.color = argb(255, 166, 148, 118)
            p.maskFilter = BlurMaskFilter(M(2f), BlurMaskFilter.Blur.NORMAL)
            c.drawPath(blob, p)
            p.maskFilter = null
            // johad: the village pond
            p.color = argb(255, 88, 104, 78)
            c.drawOval(RectF(X(at.x + 58f), Y(at.y + 18f), X(at.x + 88f), Y(at.y + 40f)), p)
            p.color = argb(255, 66, 86, 72)
            c.drawOval(RectF(X(at.x + 62f), Y(at.y + 21f), X(at.x + 84f), Y(at.y + 37f)), p)
            val roofs = intArrayOf(
                argb(255, 160, 156, 148), argb(255, 188, 182, 170), argb(255, 146, 96, 74),
                argb(255, 164, 140, 108), argb(255, 176, 170, 160), argb(255, 128, 120, 112)
            )
            var gy = at.y - 58f
            while (gy < at.y + 58f) {
                var gx = at.x - 92f
                while (gx < at.x + 92f) {
                    val nx = (gx - at.x) / 95f
                    val ny = (gy - at.y) / 62f
                    val inside = nx * nx + ny * ny < 0.86f
                    val pond = gx > at.x + 52f && gy > at.y + 12f
                    if (inside && !pond && rng.nextFloat() < 0.86f) {
                        val cw = 9f + rng.nextFloat() * 4f
                        val ch = 8f + rng.nextFloat() * 4f
                        p.color = argb(255, 180, 164, 132)
                        c.drawRect(RectF(X(gx), Y(gy), X(gx + cw), Y(gy + ch)), p)
                        val roof = roofs[rng.nextInt(roofs.size)]
                        if (rng.nextBoolean()) building(gx + 0.4f, gy + 0.4f, cw - 0.8f, ch * 0.5f, roof)
                        else building(gx + 0.4f, gy + 0.4f, cw * 0.55f, ch - 0.8f, roof)
                        if (rng.nextFloat() < 0.12f) trees.add(Tree(gx + cw * 0.7f, gy + ch * 0.7f, 2.6f + rng.nextFloat() * 2f, 0))
                    }
                    gx += 13.5f + rng.nextFloat() * 1.5f
                }
                gy += 12.5f + rng.nextFloat() * 1.5f
            }
            // mosque: white courtyard and dome
            building(at.x - 8f, at.y - 6f, 16f, 12f, argb(255, 232, 228, 216), 4.5f)
            p.color = argb(255, 214, 210, 200)
            c.drawCircle(X(at.x - 1f), Y(at.y - 1f), M(3.4f), p)
            p.color = argb(255, 246, 244, 236)
            c.drawCircle(X(at.x - 1.8f), Y(at.y - 1.8f), M(2.1f), p)
            repeat(10) { trees.add(Tree(at.x - 100f + rng.nextFloat() * 200f, at.y - 66f + rng.nextFloat() * 132f, 3f + rng.nextFloat() * 3f, 0)) }
        }

        fun trees(list: List<Tree>) {
            p.reset(); p.isAntiAlias = true
            // winter-morning sun from the south-east: shadows fall north-west
            p.maskFilter = BlurMaskFilter(M(0.9f), BlurMaskFilter.Blur.NORMAL)
            p.color = argb(120, 18, 26, 16)
            list.forEach { t ->
                val off = t.r * 0.9f
                c.drawOval(RectF(X(t.x - off - t.r), Y(t.y - off * 0.8f - t.r * 0.8f), X(t.x - off + t.r), Y(t.y - off * 0.8f + t.r * 0.8f)), p)
            }
            p.maskFilter = null
            val rng = Random(s.land.seed + 5)
            val soft = BlurMaskFilter(M(0.35f), BlurMaskFilter.Blur.NORMAL)
            list.forEach { t ->
                val base = when (t.kind) {
                    1 -> argb(255, 62, 84, 60)
                    2 -> argb(255, 54, 76, 44)
                    else -> argb(255, 50, 70, 40)
                }
                p.color = base
                p.maskFilter = soft
                c.drawCircle(X(t.x), Y(t.y), M(t.r), p)
                p.maskFilter = null
                repeat(6) {
                    val a = rng.nextFloat() * 6.28f
                    val d = rng.nextFloat() * t.r * 0.55f
                    val rr = t.r * (0.3f + rng.nextFloat() * 0.3f)
                    val shade = 0.8f + rng.nextFloat() * 0.5f
                    p.color = argb(255, (68 * shade).toInt(), (92 * shade).toInt(), (54 * shade).toInt())
                    c.drawCircle(X(t.x + kotlin.math.cos(a) * d), Y(t.y + sin(a) * d), M(rr), p)
                }
                // lit south-east flank
                p.color = argb(90, 170, 190, 120)
                c.drawCircle(X(t.x + t.r * 0.3f), Y(t.y + t.r * 0.3f), M(t.r * 0.45f), p)
            }
        }

        private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Heat overlay: one texel per spray cell, drawn with nearest-neighbour sampling. */
    fun heatBitmap(grid: com.example.andriodfypprototype.data.RasterGrid, dim: Boolean = false): ImageBitmap {
        val bmp = Bitmap.createBitmap(grid.cols, grid.rows, Bitmap.Config.ARGB_8888)
        val out = IntArray(grid.cols * grid.rows)
        val k = if (dim) 0.7f else 1f
        for (cell in grid.cells) {
            val i = cell.row * grid.cols + cell.col
            out[i] = when {
                !cell.inside -> 0
                cell.abstained -> argb((190 * k).toInt(), 0xA7, 0xA3, 0xC4)
                cell.infestPct < 4f -> 0
                cell.infestPct < 10f -> 0
                cell.infestPct <= 30f -> argb((175 * k).toInt(), 0xF3, 0xC0, 0x4A)
                else -> argb((200 * k).toInt(), 0xE4, 0x55, 0x2D)
            }
        }
        bmp.setPixels(out, 0, grid.cols, 0, 0, grid.cols, grid.rows)
        return bmp.asImageBitmap()
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Cells in the prescription traced as an outline, the shape a sprayer actually follows. */
    fun prescriptionEdges(grid: com.example.andriodfypprototype.data.RasterGrid): FloatArray {
        val segs = ArrayList<Float>()
        val cm = grid.cellMeters.toFloat()
        fun t(c: Int, r: Int) = grid.at(c, r)?.let { it.inside && it.treated } ?: false
        for (r in 0 until grid.rows) for (c in 0 until grid.cols) {
            if (!t(c, r)) continue
            val x0 = grid.originX + c * cm
            val y0 = grid.originY + r * cm
            if (!t(c, r - 1)) segs.addAll(listOf(x0, y0, x0 + cm, y0))
            if (!t(c, r + 1)) segs.addAll(listOf(x0, y0 + cm, x0 + cm, y0 + cm))
            if (!t(c - 1, r)) segs.addAll(listOf(x0, y0, x0, y0 + cm))
            if (!t(c + 1, r)) segs.addAll(listOf(x0 + cm, y0, x0 + cm, y0 + cm))
        }
        return segs.toFloatArray()
    }
}
