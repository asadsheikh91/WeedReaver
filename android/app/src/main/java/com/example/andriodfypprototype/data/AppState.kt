package com.example.andriodfypprototype.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import com.example.andriodfypprototype.data.db.ChangeRow
import com.example.andriodfypprototype.data.db.Kind
import com.example.andriodfypprototype.data.net.ScanDto
import com.example.andriodfypprototype.data.net.TreatmentDto
import com.example.andriodfypprototype.data.net.VerificationDto
import com.example.andriodfypprototype.ui.theme.Wr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * What the screens read. It is the in-memory projection of the Room store ([Store]): hydrated
 * from it at launch and after every sync, and every write here is persisted together with a
 * change-log row carrying a monotonic client sequence number and a client id (spec section
 * 43.3), which [Store.sync] pushes to the station when the phone has signal.
 */
object AppState {

    // ---- session -----------------------------------------------------------------------
    var signedIn by mutableStateOf(false)
    var operatorName by mutableStateOf(Demo.OPERATOR)
    var operatorRole by mutableStateOf(Demo.OPERATOR_ROLE)
    var operatorCode by mutableStateOf(Demo.OPERATOR_ID)
    var trainee by mutableStateOf(false)
    /** The station ended the session (refresh token reused, account disabled); sign in again. */
    var sessionEnded by mutableIntStateOf(0)

    // ---- settings ----------------------------------------------------------------------
    var language by mutableStateOf("English")
    var useLocalUnits by mutableStateOf(true)          // acre / kanal / marla vs hectare
    var voicePrompts by mutableStateOf(true)
    var hapticProximity by mutableStateOf(true)
    /** The operator chose to hold everything on the phone, even with signal (section 14). */
    var workOffline by mutableStateOf(false)
    /** The handset currently has a validated internet connection. */
    var networkUp by mutableStateOf(false)
    val offline: Boolean get() = workOffline || !networkUp
    var tilesCached by mutableStateOf(true)
    var gridSize by mutableStateOf(GridSize.G2)
    var useDeviceCamera by mutableStateOf(true)
    var keepScreenOn by mutableStateOf(true)

    // ---- station definitions (read from the API, section 43.2; the handset never edits them)
    /** Set on the web dashboard; the handset displays it read-only. */
    var prescriptionThresholdPct by mutableStateOf(10f)
        private set
    var orgName by mutableStateOf(Demo.ORG)
    var seasonLabel by mutableStateOf(Demo.SEASON)
    var modelAerial by mutableStateOf(Demo.MODEL_AERIAL)
    var modelLeaf by mutableStateOf(Demo.MODEL_LEAF)
    val products = mutableStateListOf<Product>()
    val species = mutableStateListOf<Demo.SpeciesEntry>()
    val applicationModes = Demo.applicationModes.toMutableStateList()
    val growthStages = Demo.growthStages.toMutableStateList()
    val doseUnits = Demo.doseUnits.toMutableStateList()

    // ---- entities ----------------------------------------------------------------------
    val fields = mutableStateListOf<FieldParcel>()
    val seasons = mutableStateListOf<FieldSeason>()
    val surveys = mutableStateListOf<Survey>()
    private val zonesByField = mutableStateMapOf<String, MutableList<TreatmentZone>>()
    val scans = mutableStateListOf<LeafScan>()
    val treatments = mutableStateListOf<TreatmentRecord>()
    /** Applications from earlier seasons; drives the rotation warning of spec section 41.2. */
    val priorTreatments = mutableStateListOf<TreatmentRecord>()
    val quadrats = mutableStateListOf<QuadratRecord>()
    /** Changes waiting to upload, oldest first. */
    val changeLog = mutableStateListOf<ChangeLogEntry>()
    /** Changes the station refused, with its reason. They stay until the operator dismisses them. */
    val rejected = mutableStateListOf<RejectedChange>()
    val verifications = mutableStateMapOf<String, Long>()
    var lastSyncAt by mutableStateOf(0L)
    var syncing by mutableStateOf(false)
    /** Hydrated from the local store; screens wait for it on a cold start. */
    var loaded by mutableStateOf(false)

    internal var seq = 0
    private var scanIndex by mutableIntStateOf(0)

    // ---- transient work in progress ----------------------------------------------------
    /** Held here rather than in the shell: the home destination leaves composition whenever a
     *  detail screen is pushed, so a remembered tab index would reset on every back press. */
    var homeTab by mutableIntStateOf(0)
    var activeFieldId by mutableStateOf("F-047")
    var activeZoneId by mutableStateOf<String?>(null)
    var draft by mutableStateOf<TreatmentDraft?>(null)
    /** Last known operator position per field, so a route resumes from where they stand. */
    val operatorAt = mutableStateMapOf<String, Pt>()

    // ---- transient messages ------------------------------------------------------------
    data class UiMessage(val id: Long, val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)
    var message by mutableStateOf<UiMessage?>(null)
        private set

    fun toast(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        message = UiMessage(System.nanoTime(), text, action, onAction)
    }

    fun consumeMessage(id: Long) {
        if (message?.id == id) message = null
    }

    // ---- derived -----------------------------------------------------------------------
    /** Before the first download the welcome imagery falls back to the bundled parcel. */
    fun field(id: String): FieldParcel = fields.firstOrNull { it.id == id } ?: fields.firstOrNull() ?: Demo.fields.first()
    fun seasonOf(fieldId: String): FieldSeason? = seasons.firstOrNull { it.fieldId == fieldId }
    fun surveysOf(fieldId: String): List<Survey> {
        val fs = seasonOf(fieldId)?.id ?: return emptyList()
        return surveys.filter { it.fieldSeasonId == fs }.sortedBy { it.flownAt }
    }

    fun hasSurvey(fieldId: String): Boolean =
        surveysOf(fieldId).any { it.role == SurveyRole.PRE && it.status == SurveyStatus.READY }

    fun priorFor(fieldId: String) = priorTreatments.filter { it.fieldId == fieldId }

    private val infestCache = HashMap<String, InfestationField>()
    private val gridCache = HashMap<String, RasterGrid>()

    fun infestation(fieldId: String): InfestationField =
        infestCache.getOrPut(fieldId) { Demo.fieldFor(fieldId, field(fieldId).boundary) }

    /** The station's zones for the field's current season, in route order. */
    fun zones(fieldId: String): MutableList<TreatmentZone> =
        zonesByField.getOrPut(fieldId) { mutableStateListOf() }

    fun zone(fieldId: String, zoneId: String): TreatmentZone? =
        zones(fieldId).firstOrNull { it.id == zoneId }

    fun grid(fieldId: String, size: GridSize = gridSize): RasterGrid =
        gridCache.getOrPut("$fieldId/${size.meters}/pre") {
            infestation(fieldId).rasterise(size, prescriptionThresholdPct)
        }

    fun followUpGrid(fieldId: String, size: GridSize = gridSize, late: Boolean = false): RasterGrid =
        gridCache.getOrPut("$fieldId/${size.meters}/${if (late) "28" else "14"}") {
            val factors = if (late) Demo.efficacyFactors.mapValues { (it.value * 0.85f) }
            else Demo.efficacyFactors
            infestation(fieldId).scaled(factors, 0.5f).rasterise(size, prescriptionThresholdPct)
        }

    fun pressureOf(fieldId: String): Severity {
        val z = zones(fieldId).filter { !it.state.done }
        return when {
            z.any { it.severity == Severity.HEAVY } -> Severity.HEAVY
            z.any { it.severity == Severity.MODERATE } -> Severity.MODERATE
            else -> Severity.CLEAN
        }
    }

    val pendingSync: Int get() = changeLog.size
    val abstentionQueue: List<LeafScan> get() = scans.filter { it.abstained && !it.resolved }
    val seasonAcres: Float get() = fields.sumOf { it.areaAcres.toDouble() }.toFloat()

    // ---- writes ------------------------------------------------------------------------
    /**
     * Appends to the change log and persists it with the records it touches. The sequence
     * number only ever grows, even across undo and sign-out: the station de-duplicates on it.
     */
    private fun log(
        entity: String, op: String, summary: String, payload: kotlinx.serialization.json.JsonObject,
        records: List<Store.Put> = emptyList(), mobileOwned: Boolean = true, bytes: Int = 420,
        localKind: String? = null, localId: String? = null, clientId: String = UUID.randomUUID().toString(),
        at: Long = Demo.now()
    ): ChangeRow {
        seq += 1
        val row = ChangeRow(
            clientSeq = seq, clientId = clientId, entity = entity, op = op, at = at,
            payload = payload.toString(), summary = summary, bytes = bytes, ownedByMobile = mobileOwned,
            localKind = localKind, localId = localId, userId = Store.userId
        )
        changeLog.add(ChangeLogEntry("CL-$seq", seq, entity, summary, at, mobileOwned, bytes))
        Store.persist(records, change = row, seq = seq)
        return row
    }

    fun nextScanOutcome(): Demo.ScanOutcome {
        val o = Demo.scanOutcomes[scanIndex % Demo.scanOutcomes.size]
        scanIndex += 1
        return o
    }

    /** Next id in the station's grammar; the station's own id replaces it once the record syncs. */
    private fun nextId(prefix: String, ids: List<String>, width: Int, floor: Int): String {
        val n = ids.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: (floor - 1)
        return prefix + (n + 1).toString().padStart(width, '0')
    }

    fun nextTreatmentId(): String = nextId("T-", (treatments + priorTreatments).map { it.id }, 4, 118)

    fun recordScan(
        outcome: Demo.ScanOutcome,
        fieldId: String,
        zoneLabel: String?,
        captured: android.graphics.Bitmap?
    ): LeafScan {
        val f = field(fieldId)
        val id = nextId("SC-", scans.map { it.id }, 3, 41)
        val zone = zones(fieldId).firstOrNull { it.label == zoneLabel }
        val at = zone?.center ?: Geo.centroid(f.boundary)
        val (lat, lon) = f.toLatLon(Pt(at.x + (scans.size % 5) * 1.3f, at.y - (scans.size % 3) * 0.9f))
        val scan = LeafScan(
            id = id,
            at = Demo.now(),
            speciesLatin = outcome.species.latin,
            speciesLocal = outcome.species.local,
            weedClass = outcome.species.cls,
            confidence = outcome.confidence,
            abstained = outcome.abstain,
            fieldId = fieldId,
            fieldName = f.name,
            zoneLabel = zoneLabel,
            frameId = outcome.frameId,
            lat = lat,
            lon = lon,
            gnssAccuracyM = 3.4f + (scans.size % 3) * 0.6f,
            modelVersion = modelLeaf,
            synced = false,
            leafSeed = outcome.leafSeed,
            runnerUp = outcome.runnerUp,
            inferenceMs = outcome.ms,
            captured = captured
        )
        scans.add(0, scan)
        val clientId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("clientId", clientId)
            put("capturedAt", Iso.of(scan.at))
            put("fieldId", fieldId)
            zoneLabel?.let { put("zoneLabel", it) }
            scan.frameId?.let { put("frameId", it) }
            put("speciesLatin", scan.speciesLatin)
            put("speciesLocal", scan.speciesLocal)
            put("weedClass", scan.weedClass.name)
            put("confidence", scan.confidence)
            put("abstained", scan.abstained)
            putJsonArray("runnerUp") { scan.runnerUp.forEach { (n, p) -> add(buildJsonArray { add(JsonPrimitive(n)); add(JsonPrimitive(p)) }) } }
            put("inferenceMs", scan.inferenceMs)
            put("modelVersion", scan.modelVersion)
            put("leafSeed", scan.leafSeed)
            put("lat", scan.lat)
            put("lon", scan.lon)
            put("gnssAccuracyM", scan.gnssAccuracyM)
        }
        val dto = ScanDto(
            id = id, clientId = clientId, at = Iso.of(scan.at), fieldId = fieldId, fieldName = f.name,
            zoneLabel = zoneLabel, frameId = scan.frameId, speciesLatin = scan.speciesLatin, speciesLocal = scan.speciesLocal,
            weedClass = scan.weedClass.name, confidence = scan.confidence, abstained = scan.abstained,
            runnerUp = scan.runnerUp.map { (n, p) -> JsonArray(listOf(JsonPrimitive(n), JsonPrimitive(p))) },
            inferenceMs = scan.inferenceMs, modelVersion = scan.modelVersion, leafSeed = scan.leafSeed,
            lat = scan.lat, lon = scan.lon, gnssAccuracyM = scan.gnssAccuracyM
        )
        log(
            "leaf_scan", "create",
            if (scan.abstained) "Scan $id sent to review queue" else "Scan $id · ${scan.speciesLatin}",
            payload,
            records = listOf(Store.Put(Kind.SCAN, id, fieldId, clientId, Store.encode(ScanDto.serializer(), dto), photo = captured)),
            bytes = if (captured != null) 184_000 else 1_200,
            localKind = Kind.SCAN, localId = id, clientId = clientId, at = scan.at
        )
        return scan
    }

    fun scan(id: String): LeafScan? = scans.firstOrNull { it.id == id }

    fun markZone(fieldId: String, zoneId: String, state: ZoneState) {
        val list = zones(fieldId)
        val i = list.indexOfFirst { it.id == zoneId }
        if (i < 0) return
        val z = list[i]
        val now = Demo.now()
        list[i] = z.copy(state = state, treatedAt = if (state == ZoneState.TREATED) now else z.treatedAt)
        log(
            "treatment_zone", "state", "${z.label} marked ${state.label.lowercase()}",
            buildJsonObject { put("fieldId", fieldId); put("zone", z.letter); put("state", state.name) },
            records = Store.zonePuts(fieldId, listOf(list[i])), at = now
        )
    }

    /**
     * Reverts the last zone write. If its change has not left the phone it is withdrawn;
     * if it already reached the station, the revert is itself a change (back to the earlier state).
     */
    fun undoZone(fieldId: String, previous: TreatmentZone) {
        val list = zones(fieldId)
        val i = list.indexOfFirst { it.id == previous.id }
        if (i < 0) return
        list[i] = previous
        val last = changeLog.lastOrNull()
        if (last != null && last.entity == "treatment_zone" && last.summary.startsWith(previous.label) && Store.withdraw(last.seq)) {
            changeLog.removeAt(changeLog.lastIndex)
            Store.persist(Store.zonePuts(fieldId, listOf(previous)), seq = seq)
        } else {
            log(
                "treatment_zone", "state", "${previous.label} back to ${previous.state.label.lowercase()}",
                buildJsonObject { put("fieldId", fieldId); put("zone", previous.letter); put("state", previous.state.name) },
                records = Store.zonePuts(fieldId, listOf(previous))
            )
        }
    }

    fun routeAllZones(fieldId: String) {
        val list = zones(fieldId)
        if (list.none { it.state == ZoneState.FLAGGED }) return
        for (i in list.indices) {
            if (list[i].state == ZoneState.FLAGGED) list[i] = list[i].copy(state = ZoneState.ROUTED)
        }
        log(
            "treatment_zone", "route", "Spray route created · ${field(fieldId).name}",
            buildJsonObject { put("fieldId", fieldId) },
            records = Store.zonePuts(fieldId, list)
        )
    }

    /** [clash] is true when the operator confirmed a repeat of the last mode of action. */
    fun saveTreatment(rec: TreatmentRecord, clash: Boolean = false) {
        treatments.add(0, rec)
        val clientId = UUID.randomUUID().toString()
        log(
            "treatment", "create", "${rec.product} · ${Fmt.plural(rec.zoneLabels.size, "zone")}",
            buildJsonObject {
                put("clientId", clientId)
                put("fieldId", rec.fieldId)
                putJsonArray("zoneLabels") { rec.zoneLabels.forEach { add(JsonPrimitive(it)) } }
                put("appliedAt", Iso.of(rec.appliedAt))
                put("product", rec.product)
                put("doseRecorded", rec.doseRecorded)
                put("doseUnit", rec.doseUnit)
                put("applicationMode", rec.applicationMode)
                put("growthStage", rec.growthStage)
                put("areaAcres", rec.areaAcres)
                put("waterLitres", rec.waterLitres)
                put("notes", rec.notes)
                put("rotationOverride", clash)
            },
            records = listOf(Store.Put(Kind.TREATMENT, rec.id, rec.fieldId, clientId, Store.encode(TreatmentDto.serializer(), rec.toDto(clientId)))),
            bytes = 860, localKind = Kind.TREATMENT, localId = rec.id, clientId = clientId, at = rec.appliedAt
        )
    }

    fun saveVerification(fieldId: String, results: List<Pair<String, Int>>) {
        val list = zones(fieldId)
        for ((label, pct) in results) {
            val i = list.indexOfFirst { it.label == label }
            if (i >= 0) list[i] = list[i].copy(state = ZoneState.RESURVEYED, efficacyPct = pct)
        }
        val now = Demo.now()
        verifications[fieldId] = now
        val clientId = UUID.randomUUID().toString()
        val id = "V-$clientId".take(14)
        val dto = VerificationDto(
            id = id, fieldId = fieldId, fieldSeasonId = seasonOf(fieldId)?.id ?: "", savedAt = Iso.of(now),
            results = results.map { (label, pct) ->
                buildJsonObject { put("letter", label.takeLast(1)); put("label", label); put("efficacyPct", pct) }
            }
        )
        log(
            "verification", "create", "Per-zone efficacy · ${field(fieldId).name}",
            buildJsonObject { put("fieldId", fieldId); put("role", SurveyRole.PLUS_14D.name); put("clientId", clientId) },
            records = Store.zonePuts(fieldId, list) +
                Store.Put(Kind.VERIFICATION, id, fieldId, clientId, Store.encode(VerificationDto.serializer(), dto)),
            localKind = Kind.VERIFICATION, localId = id, clientId = clientId, at = now
        )
    }

    fun addField(name: String, village: String, boundary: List<Pt>, landscape: Landscape, method: String): FieldParcel {
        // Normalise so the parcel's north-west corner sits at the frame origin.
        val b = Geo.bounds(boundary)
        val shift = Pt(b[0], b[1])
        val poly = boundary.map { it - shift }
        val shiftLand = landscape.copy(
            road = landscape.road?.map { it - shift },
            watercourse = landscape.watercourse?.map { it - shift },
            canal = landscape.canal?.map { it - shift },
            farmstead = landscape.farmstead?.minus(shift),
            village = landscape.village?.minus(shift),
            tubewell = landscape.tubewell?.minus(shift)
        )
        // A placeholder id until the station assigns the parcel its own.
        val id = nextId("F-", fields.map { it.id }, 3, 900).let { if (fields.any { f -> f.id == it }) "F-L${fields.size}" else it }
        val parcel = FieldParcel(
            id, name, village.ifBlank { "Pindi Bhattian, Hafizabad" }, poly,
            31.8950, 73.2740, operatorName, shiftLand, poly.first(), method
        )
        val season = FieldSeason("FS-$id", id, "Wheat", seasonLabel, Demo.now(), 22, "—", null)
        val survey = Survey("S-$id", "FS-$id", Demo.now() + 5 * 86_400_000L, SurveyRole.PRE, 15, "DJI Mavic 3M · 20 MP RGB", 0f, SurveyStatus.SCHEDULED)
        fields.add(parcel)
        seasons.add(season)
        surveys.add(survey)
        zonesByField[id] = mutableStateListOf()
        val clientId = UUID.randomUUID().toString()
        log(
            "field", "create", "$name added · $method",
            buildJsonObject {
                put("clientId", clientId)
                put("name", name)
                put("village", parcel.village)
                put("captureMethod", method)
                putJsonArray("boundary") { poly.forEach { p -> add(buildJsonObject { put("x", p.x); put("y", p.y) }) } }
                put("lat", parcel.lat)
                put("lon", parcel.lon)
                putJsonObject("gate") { put("x", parcel.gate.x); put("y", parcel.gate.y) }
                put("landscape", Store.json.encodeToJsonElement(com.example.andriodfypprototype.data.net.LandscapeDto.serializer(), shiftLand.toDto()))
                put("crop", "Wheat")
            },
            records = listOf(
                Store.Put(Kind.FIELD, id, id, clientId, Store.encode(com.example.andriodfypprototype.data.net.FieldDto.serializer(), parcel.toDto())),
                Store.Put(Kind.SEASON, season.id, id, null, Store.encode(com.example.andriodfypprototype.data.net.SeasonDto.serializer(), season.toDto())),
                Store.Put(Kind.SURVEY, survey.id, id, null, Store.encode(com.example.andriodfypprototype.data.net.SurveyDto.serializer(), survey.toDto(id)))
            ),
            bytes = 2_400, localKind = Kind.FIELD, localId = id, clientId = clientId
        )
        return parcel
    }

    fun resolveAbstention(scanId: String, species: String) {
        val i = scans.indexOfFirst { it.id == scanId }
        if (i < 0) return
        scans[i] = scans[i].copy(annotation = species, resolved = true)
        val clientId = Store.scanClientId(scanId)
        log(
            "abstention", "annotate", "Scan $scanId annotated · $species",
            buildJsonObject {
                if (clientId != null) put("scanClientId", clientId) else put("scanId", scanId)
                put("species", species)
            },
            records = Store.scanAnnotationPut(scanId, species)
        )
    }

    fun timeline(): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        val pendingIds = changeLog.map { it.summary }
        treatments.forEach {
            out.add(
                LogEntry(
                    it.id, LogKind.TREATMENT, it.product,
                    "${it.fieldName} · ${Fmt.plural(it.zoneLabels.size, "zone")} · HRAC ${it.hracGroup.code}",
                    it.appliedAt, Wr.Forest, it.synced, it.fieldId, it.id
                )
            )
        }
        scans.forEach {
            out.add(
                LogEntry(
                    it.id, LogKind.SCAN,
                    if (it.abstained && !it.resolved) "Scan needs review" else it.annotation ?: it.speciesLatin,
                    "${it.fieldName}${it.zoneLabel?.let { z -> " · $z" } ?: ""} · ${Fmt.pct(it.confidence)} confidence",
                    it.at, if (it.abstained) Wr.Wheat else Wr.Slate, it.synced, it.fieldId, it.id
                )
            )
        }
        fields.forEach { f ->
            zones(f.id).filter { it.state.done && it.treatedAt != null }.forEach { z ->
                out.add(
                    LogEntry(
                        "${f.id}-${z.id}", LogKind.ZONE, "${z.label} treated",
                        "${f.name} · ${Fmt.sqm(z.areaSqm)}", z.treatedAt!!, Wr.Moss,
                        pendingIds.none { s -> s.startsWith(z.label) }, f.id, z.id
                    )
                )
            }
            verifications[f.id]?.let { at ->
                out.add(
                    LogEntry(
                        "V-${f.id}", LogKind.VERIFY, "Treatment verified",
                        "${f.name} · per-zone efficacy saved", at, Wr.Forest2,
                        pendingIds.none { s -> s.startsWith("Per-zone") }, f.id
                    )
                )
            }
        }
        surveys.filter { it.status == SurveyStatus.READY }.forEach { s ->
            val f = fields.firstOrNull { seasonOf(it.id)?.id == s.fieldSeasonId }
            out.add(
                LogEntry(
                    s.id, LogKind.SURVEY, "${s.role.label} survey ready",
                    "${f?.name ?: "Field"} · ${s.images} images · ${s.gsdCm} cm/px", s.flownAt + 5 * 3_600_000L,
                    Wr.Slate, true, f?.id
                )
            )
        }
        priorTreatments.forEach {
            out.add(
                LogEntry(
                    it.id, LogKind.TREATMENT, it.product,
                    "${it.fieldName} · earlier season · HRAC ${it.hracGroup.code}",
                    it.appliedAt, Wr.Ink3, true, it.fieldId, it.id
                )
            )
        }
        return out.sortedByDescending { it.at }
    }

    /** Discards what has not been uploaded and takes the station's copy again (see [Store.reset]). */
    fun reset() {
        scanIndex = 0; draft = null; activeZoneId = null
        operatorAt.clear()
        Store.reset()
    }

    // ---- hydration ---------------------------------------------------------------------
    /** Replaces the projection with a fresh read of the store. Main thread only. */
    internal fun apply(s: Store.Snapshot) {
        if (s.thresholdPct != prescriptionThresholdPct || fields.map { it.id } != s.fields.map { it.id }) {
            infestCache.clear(); gridCache.clear()
        }
        prescriptionThresholdPct = s.thresholdPct
        s.orgName?.let { orgName = it }
        s.seasonLabel?.let { seasonLabel = it }
        s.modelAerial?.let { modelAerial = it }
        s.modelLeaf?.let { modelLeaf = it }
        if (s.products.isNotEmpty()) products.replaceWith(s.products)
        if (s.species.isNotEmpty()) species.replaceWith(s.species)
        s.applicationModes?.let { applicationModes.replaceWith(it) }
        s.growthStages?.let { growthStages.replaceWith(it) }
        s.doseUnits?.let { doseUnits.replaceWith(it) }

        fields.replaceWith(s.fields)
        seasons.replaceWith(s.seasons)
        surveys.replaceWith(s.surveys)
        zonesByField.keys.retainAll(s.zones.keys)
        s.zones.forEach { (fid, list) ->
            val current = zonesByField[fid]
            if (current == null) zonesByField[fid] = list.toMutableStateList() else current.replaceWith(list)
        }
        s.fields.forEach { zonesByField.getOrPut(it.id) { mutableStateListOf() } }
        scans.replaceWith(s.scans)
        treatments.replaceWith(s.treatments)
        priorTreatments.replaceWith(s.priorTreatments)
        quadrats.replaceWith(s.quadrats)
        verifications.clear(); verifications.putAll(s.verifications)
        changeLog.replaceWith(s.changeLog)
        rejected.replaceWith(s.rejected)
        seq = maxOf(seq, s.seq)
        lastSyncAt = s.lastSyncAt
        s.remap[activeFieldId]?.let { activeFieldId = it }
        loaded = true
    }

    private fun <T> MutableList<T>.replaceWith(items: List<T>) {
        if (this == items) return
        clear(); addAll(items)
    }
}

/** A change the station refused (spec section 43.3: ownership, never last-write-wins). */
data class RejectedChange(val seq: Int, val entity: String, val summary: String, val reason: String, val at: Long)

data class TreatmentDraft(
    val fieldId: String,
    val zoneLabels: List<String>,
    val product: Product? = null,
    val dose: String = "",
    val doseUnit: String = AppState.doseUnits.firstOrNull() ?: Demo.doseUnits[0],
    val mode: String = AppState.applicationModes.firstOrNull() ?: Demo.applicationModes[0],
    val growthStage: String = AppState.growthStages.getOrNull(1) ?: Demo.growthStages[1],
    val areaAcres: Float = 0f,
    val water: String = "100",
    val notes: String = "",
    val appliedAt: Long = Demo.now()
)
