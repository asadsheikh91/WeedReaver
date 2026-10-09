package com.example.andriodfypprototype.data.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire format of the station API (backend/README.md, "Integrating the clients"). camelCase,
 * ISO-8601 UTC timestamps as strings, geometry in each field's local metric frame. These are
 * also the rows the offline cache keeps, so a pulled record and a record written on the phone
 * share one shape.
 */

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val code: String, val message: String, val details: JsonElement? = null)

// ---------------------------------------------------------------------------- auth

@Serializable
data class DeviceInfo(
    val installId: String,
    val name: String,
    val model: String? = null,
    val os: String? = null,
    val appVersion: String? = null
)

@Serializable
data class LoginIn(val email: String, val password: String, val device: DeviceInfo? = null)

@Serializable
data class RefreshIn(val refreshToken: String)

@Serializable
data class UserDto(
    val id: String,
    val email: String,
    val name: String,
    val role: String,
    val roleLabel: String = "",
    val operatorCode: String? = null,
    val scopeAll: Boolean = true,
    val fieldIds: List<String> = emptyList(),
    val scopeLabel: String = ""
)

@Serializable
data class TokenOut(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int = 900,
    val user: UserDto,
    val deviceId: String? = null
)

// ---------------------------------------------------------------------------- geometry

@Serializable
data class PointDto(val x: Float, val y: Float)

@Serializable
data class LandscapeDto(
    val seed: Int = 0,
    val canal: List<PointDto>? = null,
    val road: List<PointDto>? = null,
    val watercourse: List<PointDto>? = null,
    val farmstead: PointDto? = null,
    val village: PointDto? = null,
    val tubewell: PointDto? = null,
    val mustardBias: Float = 0.12f
)

// ---------------------------------------------------------------------------- definitions

@Serializable
data class FieldDto(
    val id: String,
    val name: String,
    val village: String = "",
    val boundary: List<PointDto>,
    val lat: Double,
    val lon: Double,
    val gate: PointDto,
    val landscape: LandscapeDto = LandscapeDto(),
    val captureMethod: String = "Surveyed",
    val capturedBy: String = "",
    val archived: Boolean = false,
    val updatedAt: String? = null
)

@Serializable
data class SeasonDto(
    val id: String,
    val fieldId: String,
    val crop: String = "Wheat",
    val season: String = "",
    val sowingDate: String? = null,
    val rowSpacingCm: Int? = null,
    val variety: String? = null,
    val harvestDate: String? = null
)

@Serializable
data class SurveyDto(
    val id: String,
    val fieldSeasonId: String,
    val fieldId: String = "",
    val role: String,
    val status: String,
    val flownAt: String,
    val altitudeM: Int = 15,
    val sensor: String = "",
    val gsdCm: Float = 0f,
    val images: Int = 0,
    val progress: Float = 1f
)

@Serializable
data class ZoneDto(
    val id: String,
    val code: String,
    val label: String,
    val letter: String,
    val fieldId: String,
    val fieldSeasonId: String = "",
    val severity: String,
    val dominantClass: String,
    val areaSqm: Int,
    val cellCount: Int,
    val cx: Float,
    val cy: Float,
    val radiusM: Float,
    val distanceM: Int,
    val routeOrder: Int = 0,
    val meanInfestPct: Float,
    val state: String,
    val treatedAt: String? = null,
    val efficacyPct: Int? = null,
    val active: Boolean = true
)

@Serializable
data class ProductDto(
    val id: String = "",
    val trade: String,
    val active: String,
    val hrac: String,
    val target: String,
    val crop: String,
    val formulation: String,
    val registered: Boolean = true
)

@Serializable
data class SpeciesDto(val latin: String, val local: String, val common: String, val cls: String, val note: String = "")

@Serializable
data class StationDto(
    val orgName: String = "",
    val seasonLabel: String = "",
    val thresholdPct: Float = 10f,
    val defaultGridM: Int = 2,
    val leafAbstainBelow: Float = 0.65f,
    val modelAerial: String = "",
    val modelLeaf: String = "",
    val appLatestVersion: String? = null,
    val serverTime: String
)

// ---------------------------------------------------------------------------- observations

@Serializable
data class ScanDto(
    val id: String,
    val clientId: String? = null,
    val at: String,
    val fieldId: String,
    val fieldName: String = "",
    val zoneLabel: String? = null,
    val frameId: String? = null,
    val speciesLatin: String,
    val speciesLocal: String? = null,
    val weedClass: String,
    val confidence: Float,
    val abstained: Boolean,
    val runnerUp: List<JsonArray> = emptyList(),
    val inferenceMs: Int? = null,
    val modelVersion: String? = null,
    val leafSeed: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val gnssAccuracyM: Float? = null,
    val hasPhoto: Boolean = false,
    val annotation: String? = null,
    val resolved: Boolean = false,
    val resolution: String? = null,
    val displayLabel: String? = null
)

@Serializable
data class TreatmentDto(
    val id: String,
    val clientId: String? = null,
    val fieldSeasonId: String,
    val fieldId: String,
    val fieldName: String = "",
    val zoneLabels: List<String>,
    val appliedAt: String,
    val product: String,
    val activeIngredient: String,
    val hracGroup: String,
    val doseRecorded: String,
    val doseUnit: String,
    val applicationMode: String,
    val growthStage: String,
    val operator: String = "",
    val areaAcres: Float,
    val waterLitres: String = "",
    val notes: String = ""
)

@Serializable
data class VerificationDto(
    val id: String,
    val fieldId: String,
    val fieldSeasonId: String = "",
    val role: String = "PLUS_14D",
    val savedAt: String,
    val results: List<JsonObject> = emptyList(),
    val historical: Boolean = false
)

@Serializable
data class QuadratDto(
    val id: String,
    val frameId: String,
    val recordedAt: String,
    val fieldId: String,
    val fieldName: String = "",
    val zoneLabel: String? = null,
    val speciesCounts: List<JsonArray> = emptyList(),
    val verifiedBy: String? = null,
    val seed: Int? = null
)

// ---------------------------------------------------------------------------- sync

@Serializable
data class SyncChange(
    val clientSeq: Int,
    val entity: String,
    val op: String,
    val at: String? = null,
    val payload: JsonObject
)

@Serializable
data class SyncPush(val changes: List<SyncChange>, val pendingAfter: Int? = null)

@Serializable
data class SyncResult(
    val clientSeq: Int,
    val status: String,
    val entity: String,
    val entityId: String? = null,
    val changeId: String? = null,
    val reason: String? = null
)

@Serializable
data class SyncPushOut(
    val results: List<SyncResult>,
    val applied: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
    val serverTime: String? = null
)

@Serializable
data class SyncPullOut(
    val cursor: String,
    val full: Boolean = false,
    val settings: StationDto,
    val fields: List<FieldDto> = emptyList(),
    val seasons: List<SeasonDto> = emptyList(),
    val surveys: List<SurveyDto> = emptyList(),
    val zones: List<ZoneDto> = emptyList(),
    val products: List<ProductDto> = emptyList(),
    val species: List<SpeciesDto> = emptyList(),
    val scans: List<ScanDto> = emptyList(),
    val treatments: List<TreatmentDto> = emptyList(),
    val verifications: List<VerificationDto> = emptyList(),
    val quadrats: List<QuadratDto> = emptyList(),
    val removedFields: List<String> = emptyList()
)

@Serializable
data class Heartbeat(val battery: Int? = null, val storageMb: Int? = null, val pendingChanges: Int? = null, val appVersion: String? = null)

/** GET /reference: the picker vocabularies, so the phone never hard-codes them. */
@Serializable
data class ReferenceDto(
    val applicationModes: List<String> = emptyList(),
    val growthStages: List<String> = emptyList(),
    val doseUnits: List<String> = emptyList(),
    val captureMethods: List<String> = emptyList(),
    val acceptableControlPct: Int = 70
)
