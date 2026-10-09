package com.example.andriodfypprototype.data

import com.example.andriodfypprototype.data.net.FieldDto
import com.example.andriodfypprototype.data.net.LandscapeDto
import com.example.andriodfypprototype.data.net.PointDto
import com.example.andriodfypprototype.data.net.ProductDto
import com.example.andriodfypprototype.data.net.QuadratDto
import com.example.andriodfypprototype.data.net.ScanDto
import com.example.andriodfypprototype.data.net.SeasonDto
import com.example.andriodfypprototype.data.net.SpeciesDto
import com.example.andriodfypprototype.data.net.SurveyDto
import com.example.andriodfypprototype.data.net.TreatmentDto
import com.example.andriodfypprototype.data.net.ZoneDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** ISO-8601 on the wire, epoch milliseconds in the app. */
object Iso {
    fun ms(s: String?): Long? = s?.let {
        runCatching { Instant.parse(it).toEpochMilli() }
            .recoverCatching { _ -> OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            .recoverCatching { _ -> LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()
    }

    fun of(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

private fun PointDto.pt() = Pt(x, y)
private fun Pt.dto() = PointDto(x, y)

private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
    value?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: fallback

fun FieldDto.toDomain() = FieldParcel(
    id = id, name = name, village = village, boundary = boundary.map { it.pt() }, lat = lat, lon = lon,
    capturedBy = capturedBy,
    landscape = Landscape(
        seed = landscape.seed,
        canal = landscape.canal?.map { it.pt() },
        road = landscape.road?.map { it.pt() },
        watercourse = landscape.watercourse?.map { it.pt() },
        farmstead = landscape.farmstead?.pt(),
        village = landscape.village?.pt(),
        tubewell = landscape.tubewell?.pt(),
        mustardBias = landscape.mustardBias
    ),
    gate = gate.pt(), captureMethod = captureMethod
)

fun FieldParcel.toDto() = FieldDto(
    id = id, name = name, village = village, boundary = boundary.map { it.dto() }, lat = lat, lon = lon,
    gate = gate.dto(), landscape = landscape.toDto(), captureMethod = captureMethod, capturedBy = capturedBy
)

fun Landscape.toDto() = LandscapeDto(
    seed = seed, canal = canal?.map { it.dto() }, road = road?.map { it.dto() },
    watercourse = watercourse?.map { it.dto() }, farmstead = farmstead?.dto(), village = village?.dto(),
    tubewell = tubewell?.dto(), mustardBias = mustardBias
)

fun SeasonDto.toDomain() = FieldSeason(
    id = id, fieldId = fieldId, crop = crop, season = season,
    sowingDate = Iso.ms(sowingDate) ?: 0L, rowSpacingCm = rowSpacingCm ?: 22, variety = variety?.ifBlank { null } ?: "—",
    harvestDate = Iso.ms(harvestDate)
)

fun FieldSeason.toDto() = SeasonDto(
    id = id, fieldId = fieldId, crop = crop, season = season, sowingDate = Iso.date(sowingDate),
    rowSpacingCm = rowSpacingCm, variety = variety.takeIf { it != "—" }, harvestDate = harvestDate?.let { Iso.date(it) }
)

fun SurveyDto.toDomain() = Survey(
    id = id, fieldSeasonId = fieldSeasonId, flownAt = Iso.ms(flownAt) ?: 0L,
    role = enumOr(role, SurveyRole.PRE), altitudeM = altitudeM, sensor = sensor, gsdCm = gsdCm,
    status = enumOr(status, SurveyStatus.SCHEDULED), images = images, progress = progress
)

fun Survey.toDto(fieldId: String) = SurveyDto(
    id = id, fieldSeasonId = fieldSeasonId, fieldId = fieldId, role = role.name, status = status.name,
    flownAt = Iso.of(flownAt), altitudeM = altitudeM, sensor = sensor, gsdCm = gsdCm, images = images, progress = progress
)

/** The phone keys a zone by its code ("Z-A"), as the on-device zoning did. */
fun ZoneDto.toDomain() = TreatmentZone(
    id = code, label = label, severity = enumOr(severity, Severity.MODERATE),
    dominantClass = enumOr(dominantClass, WeedClass.GRASS), areaSqm = areaSqm, cellCount = cellCount,
    cx = cx, cy = cy, radiusM = radiusM, distanceM = distanceM, state = enumOr(state, ZoneState.FLAGGED),
    meanInfestPct = meanInfestPct, efficacyPct = efficacyPct, treatedAt = Iso.ms(treatedAt)
)

private fun JsonArray.pair(): Pair<String, Float>? {
    val name = (getOrNull(0) as? JsonPrimitive)?.content ?: return null
    val p = (getOrNull(1) as? JsonPrimitive)?.floatOrNull ?: return null
    return name to p
}

fun ScanDto.toDomain(field: FieldParcel?, captured: android.graphics.Bitmap?, local: Boolean): LeafScan {
    val label = resolution ?: annotation
    val (fLat, fLon) = field?.let { it.lat to it.lon } ?: (0.0 to 0.0)
    return LeafScan(
        id = id, at = Iso.ms(at) ?: 0L, speciesLatin = speciesLatin, speciesLocal = speciesLocal ?: "",
        weedClass = enumOr(weedClass, WeedClass.GRASS), confidence = confidence, abstained = abstained,
        fieldId = fieldId, fieldName = field?.name ?: fieldName, zoneLabel = zoneLabel, frameId = frameId,
        lat = lat ?: fLat, lon = lon ?: fLon, gnssAccuracyM = gnssAccuracyM ?: 0f, modelVersion = modelVersion ?: "",
        synced = !local, leafSeed = leafSeed ?: id.hashCode(), runnerUp = runnerUp.mapNotNull { it.pair() },
        inferenceMs = inferenceMs ?: 0, resolved = resolved || label != null, annotation = label, captured = captured
    )
}

fun TreatmentDto.toDomain(local: Boolean) = TreatmentRecord(
    id = id, fieldSeasonId = fieldSeasonId, fieldId = fieldId, fieldName = fieldName, zoneLabels = zoneLabels,
    appliedAt = Iso.ms(appliedAt) ?: 0L, product = product, activeIngredient = activeIngredient,
    hracGroup = enumOr(hracGroup, HracGroup.G1), doseRecorded = doseRecorded, doseUnit = doseUnit,
    applicationMode = applicationMode, growthStage = growthStage, operator = operator, areaAcres = areaAcres,
    synced = !local, waterLitres = waterLitres, notes = notes
)

fun TreatmentRecord.toDto(clientId: String) = TreatmentDto(
    id = id, clientId = clientId, fieldSeasonId = fieldSeasonId, fieldId = fieldId, fieldName = fieldName,
    zoneLabels = zoneLabels, appliedAt = Iso.of(appliedAt), product = product, activeIngredient = activeIngredient,
    hracGroup = hracGroup.name, doseRecorded = doseRecorded, doseUnit = doseUnit, applicationMode = applicationMode,
    growthStage = growthStage, operator = operator, areaAcres = areaAcres, waterLitres = waterLitres, notes = notes
)

fun ProductDto.toDomain() = Product(
    trade = trade, active = active, hrac = enumOr(hrac, HracGroup.G1), target = enumOr(target, WeedClass.GRASS),
    crop = crop, formulation = formulation, registered = registered
)

fun SpeciesDto.toDomain() = Demo.SpeciesEntry(latin, local, common, enumOr(cls, WeedClass.GRASS), note)

fun QuadratDto.toDomain() = QuadratRecord(
    id = id, frameId = frameId, recordedAt = Iso.ms(recordedAt) ?: 0L, fieldName = fieldName, zoneLabel = zoneLabel ?: "",
    speciesCounts = speciesCounts.mapNotNull { a ->
        val name = (a.getOrNull(0) as? JsonPrimitive)?.content ?: return@mapNotNull null
        val n = (a.getOrNull(1) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: return@mapNotNull null
        name to n
    },
    verifiedBy = verifiedBy ?: "", seed = seed ?: id.hashCode()
)
