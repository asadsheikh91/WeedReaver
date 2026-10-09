package com.example.andriodfypprototype.data

import androidx.compose.ui.graphics.Color
import com.example.andriodfypprototype.ui.theme.Wr
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Domain model. Mirrors the PostGIS schema in spec section 36.3 so that the prototype
 * screens exercise the same entities the real backend will expose.
 *
 * Geometry is held in metres in a local tangent frame per field (x east, y south, origin at
 * the field's north-west corner). The real build stores geometry(MultiPolygon, 4326); the
 * conversion is [FieldParcel.toLatLon].
 */

/** Spec section 21. The aerial model predicts three classes, never a species. */
enum class WeedClass(val label: String, val short: String, val color: Color) {
    CROP("Crop canopy", "Crop", Wr.HeatLow),
    GRASS("Grass weed", "Grass", Wr.HeatMid),
    BROADLEAF("Broadleaf weed", "Broadleaf", Wr.HeatHigh);

    val chemistryHint: String
        get() = when (this) {
            CROP -> "No action indicated"
            GRASS -> "Points to ACCase (HRAC 1) or ALS (HRAC 2) chemistry"
            BROADLEAF -> "Points to a different mode-of-action group"
        }
}

/** Spec section 38. Bands are a decision aid, not a measurement. */
enum class Severity(val label: String, val fill: Color, val ink: Color, val bg: Color, val line: Color) {
    CLEAN("Clean", Wr.HeatLow, Wr.Forest, Wr.Sage, Wr.SageLine),
    MODERATE("Moderate", Wr.HeatMid, Wr.WheatInk, Wr.WheatBg, Wr.WheatLine),
    HEAVY("Heavy", Wr.HeatHigh, Wr.ClayInk, Wr.ClayBg, Wr.ClayLine);

    val meaning: String
        get() = when (this) {
            CLEAN -> "No treatment indicated"
            MODERATE -> "Spot treatment indicated"
            HEAVY -> "Likely control failure — investigate"
        }

    companion object {
        fun band(infestPct: Float) = when {
            infestPct < 10f -> CLEAN
            infestPct <= 30f -> MODERATE
            else -> HEAVY
        }
    }
}

/** Spec section 36.3: surveys carry a role, never an inferred timestamp comparison. */
enum class SurveyRole(val code: String, val label: String, val short: String) {
    PRE("pre", "Pre-treatment", "Pre"),
    PLUS_14D("plus_14d", "Follow-up +14 d", "+14 d"),
    PLUS_28D("plus_28d", "Follow-up +28 d", "+28 d")
}

enum class SurveyStatus(val label: String) {
    SCHEDULED("Scheduled"), QUEUED("Queued"), PROCESSING("Processing"), READY("Ready"), FAILED("Failed")
}

/** Spec section 31. The grid an applicator can actually act on. */
enum class GridSize(val meters: Int, val label: String, val actuator: String) {
    G1(1, "1 m", "Knapsack operator on a navigation prompt"),
    G2(2, "2 m", "Section control on a tractor boom"),
    G5(5, "5 m", "Robust to unassisted smartphone GNSS drift")
}

/** Spec section 43.1. The one object both surfaces share. */
enum class ZoneState(val label: String) {
    FLAGGED("Flagged"),
    ROUTED("Routed"),
    TREATED("Treated"),
    RESURVEYED("Re-surveyed");

    val done: Boolean get() = this == TREATED || this == RESURVEYED
}

/** Local metric vertex. */
data class Pt(val x: Float, val y: Float) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

object Geo {
    fun area(poly: List<Pt>): Float {
        if (poly.size < 3) return 0f
        var s = 0.0
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            s += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return abs(s / 2.0).toFloat()
    }

    fun perimeter(poly: List<Pt>, closed: Boolean = true): Float {
        if (poly.size < 2) return 0f
        var s = 0f
        for (i in 0 until poly.size - 1) s += poly[i].dist(poly[i + 1])
        if (closed && poly.size > 2) s += poly.last().dist(poly.first())
        return s
    }

    fun contains(poly: List<Pt>, x: Float, y: Float): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun centroid(poly: List<Pt>): Pt =
        Pt(poly.map { it.x }.average().toFloat(), poly.map { it.y }.average().toFloat())

    fun bounds(poly: List<Pt>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        poly.forEach {
            if (it.x < minX) minX = it.x; if (it.y < minY) minY = it.y
            if (it.x > maxX) maxX = it.x; if (it.y > maxY) maxY = it.y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** Douglas-Peucker. This is the simplification the boundary walk applies at 2 m. */
    fun simplify(pts: List<Pt>, tolerance: Float): List<Pt> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true; keep[pts.lastIndex] = true
        fun seg(s: Int, e: Int) {
            if (e <= s + 1) return
            val a = pts[s]; val b = pts[e]
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1e-3f)
            var best = -1; var bestD = 0f
            for (i in s + 1 until e) {
                val p = pts[i]
                val d = abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / len
                if (d > bestD) { bestD = d; best = i }
            }
            if (bestD > tolerance && best > 0) {
                keep[best] = true
                seg(s, best); seg(best, e)
            }
        }
        seg(0, pts.lastIndex)
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Simplifies a closed ring. Douglas-Peucker needs two distinct anchors, and a ring's first
     * and last points coincide, so it is split at the vertex farthest from the start.
     */
    fun simplifyRing(ring: List<Pt>, tolerance: Float): List<Pt> {
        if (ring.size < 4) return ring
        val start = ring.first()
        val far = ring.indices.maxBy { ring[it].dist(start) }
        val a = simplify(ring.subList(0, far + 1), tolerance)
        val b = simplify(ring.subList(far, ring.size) + start, tolerance)
        return a.dropLast(1) + b.dropLast(1)
    }

    /** Bearing in degrees clockwise from north for a vector in the local frame (y south). */
    fun bearing(from: Pt, to: Pt): Float {
        val deg = Math.toDegrees(kotlin.math.atan2((to.x - from.x).toDouble(), -(to.y - from.y).toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    fun compass(deg: Float): String =
        listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[
            (((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]

    fun compassShort(deg: Float): String =
        listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
}

/** What surrounds a parcel in the imagery. Drawn, never shipped as a tile. */
data class Landscape(
    val seed: Int,
    val canal: List<Pt>? = null,
    val road: List<Pt>? = null,
    val watercourse: List<Pt>? = null,
    val farmstead: Pt? = null,
    val village: Pt? = null,
    val tubewell: Pt? = null,
    val mustardBias: Float = 0.12f
)

data class FieldParcel(
    val id: String,
    val name: String,
    val village: String,
    val boundary: List<Pt>,
    val lat: Double,
    val lon: Double,
    val capturedBy: String,
    val landscape: Landscape,
    val gate: Pt,
    val captureMethod: String = "Surveyed"
) {
    val areaSqm: Float = Geo.area(boundary)
    val areaAcres: Float get() = areaSqm / 4046.86f
    val hectares: Float get() = areaSqm / 10000f
    val kanal: Int get() = (areaAcres * 8f).toInt()
    val marla: Int get() = ((areaAcres * 8f - kanal) * 20f).toInt()
    val perimeterM: Float get() = Geo.perimeter(boundary)
    val bounds: FloatArray get() = Geo.bounds(boundary)
    val widthM: Float get() = bounds[2] - bounds[0]
    val heightM: Float get() = bounds[3] - bounds[1]

    fun toLatLon(p: Pt): Pair<Double, Double> {
        val dLat = -p.y / 111_320.0
        val dLon = p.x / (111_320.0 * cos(Math.toRadians(lat)))
        return (lat + dLat) to (lon + dLon)
    }
}

data class FieldSeason(
    val id: String,
    val fieldId: String,
    val crop: String,
    val season: String,
    val sowingDate: Long,
    val rowSpacingCm: Int,
    val variety: String,
    val harvestDate: Long?
)

data class Survey(
    val id: String,
    val fieldSeasonId: String,
    val flownAt: Long,
    val role: SurveyRole,
    val altitudeM: Int,
    val sensor: String,
    val gsdCm: Float,
    val status: SurveyStatus,
    val images: Int = 0,
    val progress: Float = 1f
)

data class GridCell(
    val col: Int,
    val row: Int,
    val infestPct: Float,
    val weedClass: WeedClass,
    val confidence: Float,
    val abstained: Boolean,
    val treated: Boolean,
    val inside: Boolean = true
) {
    val severity: Severity get() = Severity.band(infestPct)
    val ref: String get() = "${colName(col)}${row + 1}"

    private fun colName(c: Int): String {
        var n = c
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + (n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }
}

data class RasterGrid(
    val cols: Int,
    val rows: Int,
    val cellMeters: Int,
    val originX: Float,
    val originY: Float,
    val cells: List<GridCell>
) {
    val total by lazy { cells.count { it.inside } }
    val flagged by lazy { cells.count { it.inside && it.treated } }
    val abstained by lazy { cells.count { it.inside && it.abstained } }
    val treatedAreaFraction: Float get() = if (total == 0) 0f else flagged.toFloat() / total
    val treatedAreaSqm: Float get() = (flagged * cellMeters * cellMeters).toFloat()
    val treatedAreaAcres: Float get() = treatedAreaSqm / 4046.86f

    fun at(col: Int, row: Int): GridCell? =
        if (col in 0 until cols && row in 0 until rows) cells[row * cols + col] else null

    fun atMeters(x: Float, y: Float): GridCell? =
        at(((x - originX) / cellMeters).toInt(), ((y - originY) / cellMeters).toInt())
}

data class TreatmentZone(
    val id: String,
    val label: String,
    val severity: Severity,
    val dominantClass: WeedClass,
    val areaSqm: Int,
    val cellCount: Int,
    val cx: Float,
    val cy: Float,
    val radiusM: Float,
    val distanceM: Int,
    val state: ZoneState,
    val meanInfestPct: Float,
    val efficacyPct: Int? = null,
    val treatedAt: Long? = null
) {
    val letter: String get() = label.takeLast(1)
    val center: Pt get() = Pt(cx, cy)
}

data class LeafScan(
    val id: String,
    val at: Long,
    val speciesLatin: String,
    val speciesLocal: String,
    val weedClass: WeedClass,
    val confidence: Float,
    val abstained: Boolean,
    val fieldId: String,
    val fieldName: String,
    val zoneLabel: String?,
    val frameId: String?,
    val lat: Double,
    val lon: Double,
    val gnssAccuracyM: Float,
    val modelVersion: String,
    val synced: Boolean,
    val leafSeed: Int,
    val runnerUp: List<Pair<String, Float>>,
    val inferenceMs: Int,
    val resolved: Boolean = false,
    val annotation: String? = null,
    val captured: android.graphics.Bitmap? = null
)

data class TreatmentRecord(
    val id: String,
    val fieldSeasonId: String,
    val fieldId: String,
    val fieldName: String,
    val zoneLabels: List<String>,
    val appliedAt: Long,
    val product: String,
    val activeIngredient: String,
    val hracGroup: HracGroup,
    val doseRecorded: String,
    val doseUnit: String,
    val applicationMode: String,
    val growthStage: String,
    val operator: String,
    val areaAcres: Float,
    val synced: Boolean,
    val waterLitres: String = "",
    val notes: String = ""
)

/**
 * Spec section 41.1. Group numbers follow the HRAC 2020 global classification and must be
 * re-verified against the current list before a production build. The module records a
 * group; it never computes or prescribes a dose.
 */
enum class HracGroup(val code: String, val moa: String) {
    G1("1", "ACCase inhibitor"),
    G2("2", "ALS inhibitor"),
    G3("3", "Microtubule inhibitor"),
    G5("5", "PS II inhibitor"),
    G15("15", "VLCFA inhibitor"),
    G9("9", "EPSP synthase inhibitor"),
    G4("4", "Auxin mimic");

    val display: String get() = "Group $code · $moa"
}

data class Product(
    val trade: String,
    val active: String,
    val hrac: HracGroup,
    val target: WeedClass,
    val crop: String,
    val formulation: String,
    val registered: Boolean = true
)

data class QuadratRecord(
    val id: String,
    val frameId: String,
    val recordedAt: Long,
    val fieldName: String,
    val zoneLabel: String,
    val speciesCounts: List<Pair<String, Int>>,
    val verifiedBy: String,
    val seed: Int
)

data class ChangeLogEntry(
    val id: String,
    val seq: Int,
    val entity: String,
    val summary: String,
    val at: Long,
    val ownedByMobile: Boolean,
    val bytes: Int
)

enum class LogKind { SCAN, TREATMENT, SURVEY, ZONE, FIELD, VERIFY }

data class LogEntry(
    val id: String,
    val kind: LogKind,
    val title: String,
    val subtitle: String,
    val at: Long,
    val accent: Color,
    val synced: Boolean,
    val fieldId: String? = null,
    val refId: String? = null
)
