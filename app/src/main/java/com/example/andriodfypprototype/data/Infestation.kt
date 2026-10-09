package com.example.andriodfypprototype.data

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic synthetic infestation surface standing in for the segmentation output of C3.
 *
 * Weeds are spatially aggregated (spec section 32, Claim B), so the surface is built from a
 * small number of patches rather than from noise. Patches are lobed and stretched along the
 * drill direction because that is how Phalaris minor spreads behind a seed drill and along
 * irrigation flow. Rasterising the same surface at 1 m, 2 m and 5 m reproduces the trade in
 * spec section 31: a cell is treated if any part of it is infested, so coarsening the grid
 * raises the treated-area fraction.
 */
data class Patch(
    val cx: Float,
    val cy: Float,
    val radiusM: Float,
    val peak: Float,
    val weedClass: WeedClass,
    val label: String,
    val stretch: Float = 1.35f
)

class InfestationField(
    val boundary: List<Pt>,
    val patches: List<Patch>,
    private val seed: Int
) {
    private val b = Geo.bounds(boundary)
    val minX = floor(b[0])
    val minY = floor(b[1])
    val widthM = b[2] - minX
    val heightM = b[3] - minY

    private fun lobes(p: Patch, angle: Float): Float {
        val ph = p.label[0].code * 1.7f
        return 1f + 0.22f * sin(3f * angle + ph) + 0.12f * sin(5f * angle - ph * 0.6f)
    }

    fun patchValue(p: Patch, x: Float, y: Float): Float {
        val dx = (x - p.cx) / p.stretch
        val dy = y - p.cy
        val d = hypot(dx, dy)
        val r = p.radiusM * lobes(p, atan2(dy, dx))
        if (d >= r) return 0f
        val t = 1f - d / r
        return p.peak * t * t
    }

    /** Sparse background: isolated plants that sit well below the prescription threshold. */
    private fun scatter(x: Float, y: Float): Float {
        val n = Noise.fbm(x * 0.11f, y * 0.11f, seed, 3)
        return ((n - 0.62f) * 0.28f).coerceAtLeast(0f)
    }

    fun valueAt(x: Float, y: Float): Float {
        var v = scatter(x, y)
        for (p in patches) v += patchValue(p, x, y)
        return v.coerceIn(0f, 1f)
    }

    fun dominantAt(x: Float, y: Float): Patch? =
        patches.maxByOrNull { patchValue(it, x, y) - hypot(x - it.cx, y - it.cy) * 0.0005f }

    /** Scale every patch peak; used to synthesise a post-treatment follow-up survey. */
    fun scaled(factors: Map<String, Float>, default: Float): InfestationField =
        InfestationField(
            boundary,
            patches.map { it.copy(peak = (it.peak * (factors[it.label] ?: default)).coerceIn(0f, 1f)) },
            seed + 7
        )

    fun rasterise(grid: GridSize, thresholdPct: Float = 10f): RasterGrid {
        val cell = grid.meters.toFloat()
        val cols = (widthM / cell).roundToInt().coerceAtLeast(1)
        val rows = (heightM / cell).roundToInt().coerceAtLeast(1)
        val rng = Random(seed * 31 + grid.meters)
        val out = ArrayList<GridCell>(cols * rows)
        val n = if (grid.meters == 1) 2 else 3
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = minX + c * cell
                val y0 = minY + r * cell
                val inside = Geo.contains(boundary, x0 + cell / 2f, y0 + cell / 2f)
                if (!inside) {
                    out.add(GridCell(c, r, 0f, WeedClass.CROP, 1f, false, false, inside = false))
                    continue
                }
                var sum = 0f
                var max = 0f
                for (sy in 0 until n) for (sx in 0 until n) {
                    val v = valueAt(x0 + cell * (sx + 0.5f) / n, y0 + cell * (sy + 0.5f) / n)
                    sum += v
                    if (v > max) max = v
                }
                val mean = sum / (n * n)
                val infestPct = mean * 100f
                val patch = dominantAt(x0 + cell / 2f, y0 + cell / 2f)
                val cls = if (infestPct < 4f) WeedClass.CROP else patch?.weedClass ?: WeedClass.GRASS
                // Confidence is lowest where crop and weed canopy mix, which is the patch
                // margin rather than its core. Those are the cells the model abstains on, and
                // an abstained cell is an instruction to send a person to look (section 26).
                val ambiguity = (1f - abs(mean - 0.13f) / 0.09f).coerceIn(0f, 1f)
                val conf = (0.97f - ambiguity * 0.42f - rng.nextFloat() * 0.16f).coerceIn(0.35f, 0.99f)
                val abstained = infestPct > 6f && conf < 0.5f
                out.add(
                    GridCell(
                        col = c, row = r,
                        infestPct = infestPct,
                        weedClass = cls,
                        confidence = conf,
                        abstained = abstained,
                        treated = !abstained && max * 100f >= thresholdPct
                    )
                )
            }
        }
        return RasterGrid(cols, rows, grid.meters, minX, minY, out)
    }

    /**
     * Zones are the contiguous prescription area around each patch. The route is a greedy
     * nearest-neighbour chain from the field gate, which is what "nearest first" means to a
     * person on foot: each leg is the shortest walk from where they are standing.
     */
    fun zones(gate: Pt, thresholdPct: Float = 10f): List<TreatmentZone> {
        val thr = thresholdPct / 100f
        val candidates = patches.filter { it.peak >= 0.30f }.map { p ->
            var count = 0
            var sum = 0f
            val reach = (p.radiusM * 1.4f * p.stretch).toInt() + 2
            for (yy in -reach..reach) for (xx in -reach..reach) {
                val x = p.cx + xx
                val y = p.cy + yy
                if (!Geo.contains(boundary, x, y)) continue
                val v = patchValue(p, x, y)
                if (v >= thr) {
                    count++
                    sum += valueAt(x, y)
                }
            }
            val mean = if (count == 0) 0f else sum / count * 100f
            val area = count
            TreatmentZone(
                id = "Z-${p.label}",
                label = "Zone ${p.label}",
                severity = Severity.band(mean * 1.25f),
                dominantClass = p.weedClass,
                areaSqm = area,
                cellCount = (area / 4f).roundToInt(),
                cx = p.cx, cy = p.cy,
                radiusM = sqrt(area / PI.toFloat()),
                distanceM = 0,
                state = ZoneState.FLAGGED,
                meanInfestPct = mean
            )
        }
        val remaining = candidates.toMutableList()
        val ordered = ArrayList<TreatmentZone>()
        var at = gate
        while (remaining.isNotEmpty()) {
            val next = remaining.minBy { it.center.dist(at) }
            ordered.add(next.copy(distanceM = next.center.dist(at).roundToInt()))
            at = next.center
            remaining.remove(next)
        }
        return ordered
    }
}

/** Small hash-based value noise. Deterministic across devices, no allocation per sample. */
object Noise {
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    fun value(x: Float, y: Float, seed: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val tx = smooth(x - xi)
        val ty = smooth(y - yi)
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        val top = a + (b - a) * tx
        val bot = c + (d - c) * tx
        return top + (bot - top) * ty
    }

    fun fbm(x: Float, y: Float, seed: Int, octaves: Int = 4): Float {
        var amp = 0.5f
        var freq = 1f
        var sum = 0f
        var norm = 0f
        for (o in 0 until octaves) {
            sum += value(x * freq, y * freq, seed + o * 101) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.03f
        }
        return sum / norm
    }

    fun white(x: Int, y: Int, seed: Int): Float = hash(x, y, seed)
}
