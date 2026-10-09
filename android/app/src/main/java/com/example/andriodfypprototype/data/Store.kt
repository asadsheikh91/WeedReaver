package com.example.andriodfypprototype.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.example.andriodfypprototype.data.db.ChangeRow
import com.example.andriodfypprototype.data.db.Kind
import com.example.andriodfypprototype.data.db.LocalDb
import com.example.andriodfypprototype.data.db.MetaRow
import com.example.andriodfypprototype.data.db.RecordRow
import com.example.andriodfypprototype.data.db.StoreDao
import com.example.andriodfypprototype.data.net.ApiException
import com.example.andriodfypprototype.data.net.DeviceInfo
import com.example.andriodfypprototype.data.net.FieldDto
import com.example.andriodfypprototype.data.net.LoginIn
import com.example.andriodfypprototype.data.net.Network
import com.example.andriodfypprototype.data.net.OfflineException
import com.example.andriodfypprototype.data.net.ProductDto
import com.example.andriodfypprototype.data.net.QuadratDto
import com.example.andriodfypprototype.data.net.ReferenceDto
import com.example.andriodfypprototype.data.net.RefreshIn
import com.example.andriodfypprototype.data.net.ScanDto
import com.example.andriodfypprototype.data.net.SeasonDto
import com.example.andriodfypprototype.data.net.SecureStore
import com.example.andriodfypprototype.data.net.SpeciesDto
import com.example.andriodfypprototype.data.net.StationDto
import com.example.andriodfypprototype.data.net.SurveyDto
import com.example.andriodfypprototype.data.net.SyncChange
import com.example.andriodfypprototype.data.net.SyncPush
import com.example.andriodfypprototype.data.net.TreatmentDto
import com.example.andriodfypprototype.data.net.UserDto
import com.example.andriodfypprototype.data.net.VerificationDto
import com.example.andriodfypprototype.data.net.WrJson
import com.example.andriodfypprototype.data.net.ZoneDto
import com.example.andriodfypprototype.data.net.apiCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The phone's side of spec section 43.3: the Room store behind [AppState], the session, and the
 * sync protocol of backend/README.md ("Offline sync").
 *
 * Writes land on this phone first, always. [sync] then, in order: pushes the change log with
 * `POST /sync/push` (each change applied, a duplicate of one already received, or rejected
 * with the station's reason), uploads captured leaf photos with `PUT /scans/{id}/photo` once
 * their scan exists on the station, and pulls everything changed since the last cursor with
 * `GET /sync/pull`. A first pull has no cursor and returns a full snapshot.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object Store {

    /** A record to write with a change, in its wire shape. */
    data class Put(
        val kind: String, val id: String, val fieldId: String?, val clientId: String?, val json: String,
        val photo: Bitmap? = null, val local: Boolean = true
    )

    class Snapshot(
        val thresholdPct: Float,
        val orgName: String?, val seasonLabel: String?, val modelAerial: String?, val modelLeaf: String?,
        val products: List<Product>, val species: List<Demo.SpeciesEntry>,
        val applicationModes: List<String>?, val growthStages: List<String>?, val doseUnits: List<String>?,
        val fields: List<FieldParcel>, val seasons: List<FieldSeason>, val surveys: List<Survey>,
        val zones: Map<String, List<TreatmentZone>>, val scans: List<LeafScan>,
        val treatments: List<TreatmentRecord>, val priorTreatments: List<TreatmentRecord>,
        val quadrats: List<QuadratRecord>, val verifications: Map<String, Long>,
        val changeLog: List<ChangeLogEntry>, val rejected: List<RejectedChange>,
        val seq: Int, val lastSyncAt: Long, val remap: Map<String, String>,
        internal val zoneRows: Map<String, ZoneDto>, internal val scanRows: Map<String, RecordRow>
    )

    data class SyncReport(val applied: Int, val duplicates: Int, val rejected: Int, val photos: Int)

    val json = WrJson
    fun <T> encode(serializer: KSerializer<T>, value: T): String = json.encodeToString(serializer, value)

    private const val TAG = "WeedReaverSync"
    private const val META_CURSOR = "cursor"
    private const val META_STATION = "station"
    private const val META_REFERENCE = "reference"
    private const val META_LAST_SYNC = "lastSyncAt"
    private const val META_CLOCK = "clockOffset"
    private const val META_USER = "userId"
    private const val META_REMAP = "fieldRemap"
    private const val PUSH_BATCH = 200

    private lateinit var app: Context
    private lateinit var dao: StoreDao
    lateinit var secure: SecureStore
        private set
    private lateinit var net: Network

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** One writer, so local writes reach the database in the order the operator made them. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val writes = Dispatchers.IO.limitedParallelism(1)
    private val writer = CoroutineScope(SupervisorJob() + writes)
    /** Bumped by every local write (main thread); a hydration that raced one is redone. */
    private var writeVersion = 0
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val syncLock = Mutex()

    /** Guards the hand-off between an undo withdrawing a change and a push sending it. */
    private val sendLock = Any()
    private val inFlight = HashSet<Int>()
    private val withdrawn = HashSet<Int>()

    private val bitmaps = ConcurrentHashMap<String, Bitmap>()

    // Main-thread mirrors of the rows a write has to rewrite.
    private var zoneRows: Map<String, ZoneDto> = emptyMap()
    private var scanRows: Map<String, RecordRow> = emptyMap()

    @Volatile var userId: String? = null
        private set

    val initialized: Boolean get() = ::dao.isInitialized

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        dao = LocalDb.get(app).dao()
        secure = SecureStore(app)
        net = Network(secure) { main.launch { onSessionEnded() } }
        restoreProfile()
        AppState.workOffline = prefs().getBoolean("workOffline", false)
        watchConnectivity()
        main.launch { hydrate() }
        SyncWorker.schedulePeriodic(app)
    }

    private fun prefs() = app.getSharedPreferences("wr_prefs", Context.MODE_PRIVATE)

    // ================================================================== session

    private fun restoreProfile() {
        val user = secure.userJson?.let { runCatching { json.decodeFromString(UserDto.serializer(), it) }.getOrNull() } ?: return
        userId = user.id
        applyProfile(user)
    }

    private fun applyProfile(u: UserDto) {
        AppState.operatorName = u.name
        AppState.operatorRole = u.roleLabel.ifBlank { u.role.lowercase().replaceFirstChar { it.uppercase() } }
        AppState.operatorCode = u.operatorCode ?: ""
        AppState.trainee = u.role == "TRAINEE"
        if (AppState.trainee) AppState.voicePrompts = true
    }

    /** The account whose records are on this phone, if one ever signed in here. */
    val lastUser: UserDto? get() = secure.userJson?.let { runCatching { json.decodeFromString(UserDto.serializer(), it) }.getOrNull() }
    val hasSession: Boolean get() = initialized && secure.signedIn

    private fun deviceInfo(): DeviceInfo {
        val name = runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        return DeviceInfo(
            installId = secure.installId, name = name, model = "${Build.MANUFACTURER} ${Build.MODEL}",
            os = "Android ${Build.VERSION.RELEASE}", appVersion = Demo.APP_VERSION.substringBefore(" ")
        )
    }

    /**
     * Signs in with the station and registers this handset. The first sign-in downloads the
     * operator's fields; after that the phone works without signal for as long as the refresh
     * token lasts. Another operator signing in starts from a fresh download, and the previous
     * operator's unsent changes wait on the phone for their next session.
     */
    suspend fun signIn(email: String, password: String) = withContext(Dispatchers.IO) {
        val t = apiCall { net.api.login(LoginIn(email.trim(), password, deviceInfo())) }
        val previous = dao.meta(META_USER)
        if (previous != null && previous != t.user.id) {
            dao.deleteStationRecords(emptyList())
            dao.deleteMeta(META_CURSOR)
        }
        secure.saveSession(t, encode(UserDto.serializer(), t.user))
        dao.putMeta(MetaRow(META_USER, t.user.id))
        userId = t.user.id
        withContext(Dispatchers.Main) { applyProfile(t.user) }
        sync()
    }

    fun signOut() {
        val refresh = secure.refreshToken
        secure.clearSession()
        AppState.signedIn = false
        if (refresh != null) io.launch { runCatching { apiCall { net.api.logout(RefreshIn(refresh)) } } }
    }

    private fun onSessionEnded() {
        if (!AppState.signedIn) return
        AppState.signedIn = false
        AppState.sessionEnded += 1
        AppState.toast("The station ended this session. Sign in again; nothing on the phone is lost.")
    }

    // ================================================================== connectivity

    private fun watchConnectivity() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        fun usable(c: NetworkCapabilities?) = c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        AppState.networkUp = usable(cm.getNetworkCapabilities(cm.activeNetwork))
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: AndroidNetwork, caps: NetworkCapabilities) {
                val up = usable(caps)
                main.launch {
                    val was = AppState.networkUp
                    AppState.networkUp = up
                    if (up && !was) requestSync()
                }
            }

            override fun onLost(network: AndroidNetwork) {
                main.launch { AppState.networkUp = false }
            }
        })
    }

    fun setWorkOffline(value: Boolean) {
        AppState.workOffline = value
        prefs().edit().putBoolean("workOffline", value).apply()
        if (!value) requestSync()
    }

    /** Background sync as soon as the network allows. [delaySeconds] leaves room for an undo. */
    fun requestSync(delaySeconds: Long = 0) {
        if (!initialized || !secure.signedIn) return
        SyncWorker.scheduleNow(app, delaySeconds)
    }

    // ================================================================== local writes

    private fun photoPath(clientId: String) = File(File(app.filesDir, "scans"), "$clientId.jpg").path

    /**
     * Persists a write made on the phone: the records and the change-log row in one transaction,
     * on the single writer, then asks for a sync. Main thread.
     */
    fun persist(records: List<Put>, deletes: List<Pair<String, String>> = emptyList(), change: ChangeRow? = null, seq: Int) {
        writeVersion++
        val rows = records.map { p ->
            val path = if (p.photo != null && p.clientId != null) photoPath(p.clientId).also { bitmaps[it] = p.photo } else null
            val keep = if (p.kind == Kind.SCAN) scanRows[p.id] else null
            RecordRow(
                p.kind, p.id, p.fieldId, p.clientId ?: keep?.clientId, p.local && (keep?.local ?: true), p.json,
                photoPath = path ?: keep?.photoPath, photoUploaded = keep?.photoUploaded ?: false
            )
        }
        rows.forEach { r ->
            when (r.kind) {
                Kind.ZONE -> json.decodeFromString(ZoneDto.serializer(), r.json).let { z -> zoneRows = zoneRows + (zoneKey(z.fieldId, z.code) to z) }
                Kind.SCAN -> scanRows = scanRows + (r.id to r)
            }
        }
        val photos = records.mapNotNull { p -> if (p.photo != null && p.clientId != null) p.photo to photoPath(p.clientId) else null }
        writer.launch {
            photos.forEach { (bmp, path) ->
                runCatching {
                    File(path).parentFile?.mkdirs()
                    File(path).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                }.onFailure { Log.w(TAG, "Could not keep the leaf photo", it) }
            }
            dao.write(rows, deletes, change, seq)
        }
        if (change != null) requestSync(delaySeconds = 8)
    }

    /** Withdraws a change that has not left the phone. False when it is already on its way. */
    fun withdraw(seq: Int): Boolean {
        synchronized(sendLock) {
            if (seq in inFlight) return false
            withdrawn.add(seq)
        }
        writer.launch { dao.deleteChange(seq) }
        return true
    }

    private fun zoneKey(fieldId: String, code: String) = "$fieldId/$code"

    /** The zone rows to rewrite after the phone changed zones' state. */
    fun zonePuts(fieldId: String, zones: List<TreatmentZone>): List<Put> = zones.mapNotNull { z ->
        val row = zoneRows[zoneKey(fieldId, z.id)] ?: return@mapNotNull null
        val dto = row.copy(state = z.state.name, treatedAt = z.treatedAt?.let { Iso.of(it) }, efficacyPct = z.efficacyPct)
        Put(Kind.ZONE, dto.id, fieldId, null, encode(ZoneDto.serializer(), dto), local = false)
    }

    fun scanClientId(scanId: String): String? = scanRows[scanId]?.clientId

    fun scanAnnotationPut(scanId: String, species: String): List<Put> {
        val row = scanRows[scanId] ?: return emptyList()
        val dto = json.decodeFromString(ScanDto.serializer(), row.json).copy(annotation = species)
        return listOf(Put(Kind.SCAN, scanId, row.fieldId, row.clientId, encode(ScanDto.serializer(), dto), local = row.local))
    }

    // ================================================================== hydration

    /**
     * Re-reads the store into [AppState]. The read runs on the writer, behind every write already
     * queued; if the operator wrote something while it ran, it is read again so nothing flickers away.
     */
    suspend fun hydrate() {
        while (true) {
            val version = withContext(Dispatchers.Main) { writeVersion }
            val s = withContext(writes) { snapshot() }
            val applied = withContext(Dispatchers.Main) {
                if (writeVersion != version) return@withContext false
                zoneRows = s.zoneRows
                scanRows = s.scanRows
                AppState.apply(s)
                true
            }
            if (applied) return
        }
    }

    private inline fun <T> decodeAll(rows: List<RecordRow>?, serializer: KSerializer<T>): List<Pair<RecordRow, T>> =
        rows.orEmpty().mapNotNull { r ->
            runCatching { r to json.decodeFromString(serializer, r.json) }.onFailure { Log.w(TAG, "Unreadable ${r.kind} ${r.id}", it) }.getOrNull()
        }

    private fun bitmap(path: String?): Bitmap? = path?.let { p ->
        bitmaps[p] ?: runCatching {
            val f = File(p)
            if (!f.exists()) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
            BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()?.also { bitmaps[p] = it }
    }

    private suspend fun snapshot(): Snapshot {
        Demo.setClockOffset(dao.meta(META_CLOCK)?.toLongOrNull())
        val byKind = dao.allRecords().groupBy { it.kind }
        val station = dao.meta(META_STATION)?.let { runCatching { json.decodeFromString(StationDto.serializer(), it) }.getOrNull() }
        val reference = dao.meta(META_REFERENCE)?.let { runCatching { json.decodeFromString(ReferenceDto.serializer(), it) }.getOrNull() }

        val fieldDtos = decodeAll(byKind[Kind.FIELD], FieldDto.serializer()).filter { !it.second.archived }
        val fields = fieldDtos.map { it.second.toDomain() }.sortedBy { it.id }
        val byId = fields.associateBy { it.id }

        // One current season per field: the station's season label, else the latest sown.
        val allSeasons = decodeAll(byKind[Kind.SEASON], SeasonDto.serializer()).map { it.second }
        val current = allSeasons.groupBy { it.fieldId }.mapValues { (_, list) ->
            list.firstOrNull { station != null && it.season == station.seasonLabel } ?: list.maxBy { Iso.ms(it.sowingDate) ?: 0L }
        }
        val currentIds = current.values.map { it.id }.toSet()
        val seasons = current.values.filter { it.fieldId in byId }.map { it.toDomain() }

        val surveys = decodeAll(byKind[Kind.SURVEY], SurveyDto.serializer()).map { it.second.toDomain() }

        val zoneDtos = decodeAll(byKind[Kind.ZONE], ZoneDto.serializer()).map { it.second }
            .filter { it.active && (it.fieldSeasonId.isBlank() || it.fieldSeasonId in currentIds) }
        val zones = zoneDtos.groupBy { it.fieldId }
            .mapValues { (_, list) -> list.sortedWith(compareBy({ it.routeOrder }, { it.letter })).map { it.toDomain() } }

        val scanPairs = decodeAll(byKind[Kind.SCAN], ScanDto.serializer())
        val scans = scanPairs.map { (r, d) -> d.toDomain(byId[d.fieldId], bitmap(r.photoPath), r.local) }.sortedByDescending { it.at }

        val treatmentPairs = decodeAll(byKind[Kind.TREATMENT], TreatmentDto.serializer())
        val (now, prior) = treatmentPairs.partition { (r, d) -> r.local || d.fieldSeasonId in currentIds }
        val nameOf = { t: TreatmentRecord -> t.copy(fieldName = byId[t.fieldId]?.name ?: t.fieldName) }

        val verifications = decodeAll(byKind[Kind.VERIFICATION], VerificationDto.serializer())
            .filter { (r, d) -> !d.historical && (r.local || d.fieldSeasonId in currentIds) }
            .groupBy { it.second.fieldId }
            .mapValues { (_, list) -> list.maxOf { Iso.ms(it.second.savedAt) ?: 0L } }

        val changes = dao.allChanges().filter { it.userId == null || it.userId == userId }
        val seq = maxOf(dao.meta(StoreDao.META_SEQ)?.toIntOrNull() ?: 0, dao.allChanges().maxOfOrNull { it.clientSeq } ?: 0)
        val remap = dao.meta(META_REMAP)?.let { runCatching { json.parseToJsonElement(it).jsonObject.mapValues { e -> e.value.jsonPrimitive.content } }.getOrNull() }.orEmpty()
        if (remap.isNotEmpty()) dao.deleteMeta(META_REMAP)

        return Snapshot(
            thresholdPct = station?.thresholdPct ?: AppState.prescriptionThresholdPct,
            orgName = station?.orgName?.ifBlank { null }, seasonLabel = station?.seasonLabel?.ifBlank { null },
            modelAerial = station?.modelAerial?.ifBlank { null }, modelLeaf = station?.modelLeaf?.ifBlank { null },
            products = decodeAll(byKind[Kind.PRODUCT], ProductDto.serializer()).map { it.second }.filter { it.registered }.map { it.toDomain() },
            species = decodeAll(byKind[Kind.SPECIES], SpeciesDto.serializer()).map { it.second.toDomain() },
            applicationModes = reference?.applicationModes?.ifEmpty { null },
            growthStages = reference?.growthStages?.ifEmpty { null },
            doseUnits = reference?.doseUnits?.ifEmpty { null },
            fields = fields, seasons = seasons, surveys = surveys, zones = zones, scans = scans,
            treatments = now.map { (r, d) -> nameOf(d.toDomain(r.local)) }.sortedByDescending { it.appliedAt },
            priorTreatments = prior.map { (r, d) -> nameOf(d.toDomain(r.local)) }.sortedByDescending { it.appliedAt },
            quadrats = decodeAll(byKind[Kind.QUADRAT], QuadratDto.serializer()).map { it.second.toDomain() }.sortedBy { it.recordedAt },
            verifications = verifications,
            changeLog = changes.filter { it.status == ChangeRow.STATUS_PENDING }
                .map { ChangeLogEntry("CL-${it.clientSeq}", it.clientSeq, it.entity, it.summary, it.at, it.ownedByMobile, it.bytes) },
            rejected = changes.filter { it.status == ChangeRow.STATUS_REJECTED }
                .map { RejectedChange(it.clientSeq, it.entity, it.summary, it.reason ?: "Rejected by the station", it.at) },
            seq = seq,
            lastSyncAt = dao.meta(META_LAST_SYNC)?.toLongOrNull() ?: 0L,
            remap = remap,
            zoneRows = zoneDtos.associateBy { zoneKey(it.fieldId, it.code) },
            scanRows = scanPairs.associate { it.first.id to it.first }
        )
    }

    // ================================================================== sync

    /** Push, photos, pull. Safe to call from the Sync screen and the worker at once. */
    suspend fun sync(): SyncReport = syncLock.withLock {
        withContext(Dispatchers.Main) { AppState.syncing = true }
        try {
            withContext(Dispatchers.IO) {
                if (!secure.signedIn) throw ApiException(401, "unauthorized", "Sign in to sync")
                val (applied, duplicates, rejected) = push()
                val photos = uploadPhotos()
                pull()
                runCatching { apiCall { net.api.reference() } }.getOrNull()?.let {
                    dao.putMeta(MetaRow(META_REFERENCE, encode(ReferenceDto.serializer(), it)))
                }
                SyncReport(applied, duplicates, rejected, photos)
            }
        } finally {
            hydrate()
            withContext(Dispatchers.Main) { AppState.syncing = false }
        }
    }

    private fun ChangeRow.wire() = SyncChange(
        clientSeq = clientSeq, entity = entity, op = op, at = Iso.of(at),
        payload = json.parseToJsonElement(payload).jsonObject
    )

    private suspend fun push(): Triple<Int, Int, Int> {
        var applied = 0
        var duplicates = 0
        var rejected = 0
        while (true) {
            val pending = dao.pendingChanges().filter { it.userId == null || it.userId == userId }
            if (pending.isEmpty()) break
            // A parcel created on the phone gets its id from the station; the changes that follow
            // it go in the next batch, rewritten to that id.
            val cut = pending.indexOfFirst { it.entity == "field" && it.op == "create" }
            val candidates = pending.take(if (cut >= 0) cut + 1 else PUSH_BATCH).take(PUSH_BATCH)
            val batch = synchronized(sendLock) {
                candidates.filter { it.clientSeq !in withdrawn }.also { b -> inFlight.addAll(b.map { it.clientSeq }) }
            }
            if (batch.isEmpty()) break
            try {
                val out = apiCall { net.api.push(SyncPush(batch.map { it.wire() }, pendingAfter = pending.size - batch.size)) }
                val bySeq = out.results.associateBy { it.clientSeq }
                var settled = 0
                for (c in batch) {
                    val r = bySeq[c.clientSeq] ?: continue
                    settled++
                    when (r.status) {
                        "applied", "duplicate" -> {
                            if (r.status == "applied") applied++ else duplicates++
                            if (c.localKind != null && c.localId != null && r.entityId != null) confirm(c.localKind, c.localId, r.entityId)
                            dao.deleteChange(c.clientSeq)
                        }
                        else -> {
                            rejected++
                            dao.putChange(c.copy(status = ChangeRow.STATUS_REJECTED, reason = r.reason ?: "Rejected by the station"))
                        }
                    }
                }
                if (settled == 0) break
            } finally {
                synchronized(sendLock) { inFlight.removeAll(batch.map { it.clientSeq }.toSet()) }
            }
        }
        return Triple(applied, duplicates, rejected)
    }

    private fun withField(raw: String, fieldId: String): String {
        val o = json.parseToJsonElement(raw).jsonObject
        return JsonObject(o + ("fieldId" to JsonPrimitive(fieldId))).toString()
    }

    private fun withId(raw: String, id: String): String {
        val o = json.parseToJsonElement(raw).jsonObject
        return JsonObject(o + ("id" to JsonPrimitive(id))).toString()
    }

    /** The station accepted a record the phone created: swap the placeholder for its id. */
    private suspend fun confirm(kind: String, localId: String, serverId: String) {
        if (kind == Kind.FIELD) return remapField(localId, serverId)
        val row = dao.record(kind, localId) ?: return
        if (serverId != localId) {
            dao.record(kind, serverId)?.takeIf { it.local && it.clientId != row.clientId }?.let { moveAside(it) }
            dao.delete(kind, localId)
        }
        dao.upsert(listOf(row.copy(id = serverId, local = false, json = withId(row.json, serverId))))
    }

    /** A local record whose placeholder id the station has since given to another record. */
    private suspend fun moveAside(row: RecordRow) {
        val prefix = row.id.substringBefore('-') + "-"
        val taken = dao.records(row.kind).mapNotNull { it.id.removePrefix(prefix).toIntOrNull() }
        val width = row.id.removePrefix(prefix).length
        val fresh = prefix + ((taken.maxOrNull() ?: 0) + 1).toString().padStart(width, '0')
        dao.delete(row.kind, row.id)
        dao.upsert(listOf(row.copy(id = fresh, json = withId(row.json, fresh))))
        dao.allChanges().filter { it.localKind == row.kind && it.localId == row.id }.forEach { dao.putChange(it.copy(localId = fresh)) }
    }

    /** A parcel drawn or walked on the phone now has the station's id; everything pointing at it follows. */
    private suspend fun remapField(localId: String, serverId: String) {
        dao.allRecords().filter { it.fieldId == localId && it.kind in setOf(Kind.SCAN, Kind.TREATMENT, Kind.VERIFICATION) }
            .forEach { dao.upsert(listOf(it.copy(fieldId = serverId, json = withField(it.json, serverId)))) }
        dao.deleteField(localId) // the placeholder parcel, season and flight; the pull brings the station's
        dao.allChanges().forEach { c ->
            val p = runCatching { json.parseToJsonElement(c.payload).jsonObject }.getOrNull() ?: return@forEach
            if ((p["fieldId"] as? JsonPrimitive)?.content == localId) dao.putChange(c.copy(payload = withField(c.payload, serverId)))
        }
        val remap = dao.meta(META_REMAP)?.let { json.parseToJsonElement(it).jsonObject }.orEmpty()
        dao.putMeta(MetaRow(META_REMAP, JsonObject(remap + (localId to JsonPrimitive(serverId))).toString()))
    }

    private suspend fun uploadPhotos(): Int {
        var n = 0
        val jpeg = "image/jpeg".toMediaType()
        for (row in dao.photosToUpload()) {
            val file = row.photoPath?.let(::File)
            if (file == null || !file.exists()) {
                dao.upsert(listOf(row.copy(photoUploaded = true)))
                continue
            }
            try {
                val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(jpeg))
                apiCall { net.api.putScanPhoto(row.id, part) }
                n++
                dao.upsert(listOf(row.copy(photoUploaded = true)))
            } catch (e: ApiException) {
                // The station will never take this file (too large, scan gone); stop retrying it.
                if (e.status in 400..499 && e.status != 401 && e.status != 429) {
                    Log.w(TAG, "Photo for ${row.id} refused: ${e.code} ${e.message}")
                    dao.upsert(listOf(row.copy(photoUploaded = true)))
                } else throw e
            }
        }
        return n
    }

    private suspend fun pull() {
        val since = dao.meta(META_CURSOR)
        val out = apiCall { net.api.pull(since) }
        val existing = dao.allRecords()
        val pending = dao.pendingChanges()
        // Zones the phone has changed but not yet sent keep the phone's state until they are.
        val heldZones = pending.filter { it.entity in setOf("treatment_zone", "verification") }
            .mapNotNull { runCatching { json.parseToJsonElement(it.payload).jsonObject["fieldId"]?.jsonPrimitive?.content }.getOrNull() }
            .distinct()
        val photoById = existing.filter { it.kind == Kind.SCAN && it.photoPath != null }.associateBy { it.id }
        val localByClient = existing.filter { it.local && it.clientId != null }.associateBy { "${it.kind}/${it.clientId}" }
        val localIds = existing.filter { it.local }.groupBy { it.kind }.mapValues { (_, v) -> v.associateBy { it.id } }

        val rows = ArrayList<RecordRow>()
        val deletes = ArrayList<Pair<String, String>>()
        fun row(kind: String, id: String, fieldId: String?, clientId: String?, body: String) =
            rows.add(RecordRow(kind, id, fieldId, clientId, local = false, json = body))

        out.fields.forEach { row(Kind.FIELD, it.id, it.id, null, encode(FieldDto.serializer(), it)) }
        out.seasons.forEach { row(Kind.SEASON, it.id, it.fieldId, null, encode(SeasonDto.serializer(), it)) }
        out.surveys.forEach { row(Kind.SURVEY, it.id, it.fieldId.ifBlank { null }, null, encode(SurveyDto.serializer(), it)) }
        out.zones.filter { it.fieldId !in heldZones }.forEach { row(Kind.ZONE, it.id, it.fieldId, null, encode(ZoneDto.serializer(), it)) }
        out.products.forEach { row(Kind.PRODUCT, it.id.ifBlank { it.trade }, null, null, encode(ProductDto.serializer(), it)) }
        out.species.forEach { row(Kind.SPECIES, it.latin, null, null, encode(SpeciesDto.serializer(), it)) }
        out.quadrats.forEach { row(Kind.QUADRAT, it.id, it.fieldId, null, encode(QuadratDto.serializer(), it)) }
        out.scans.forEach { s ->
            val mine = s.clientId?.let { localByClient["${Kind.SCAN}/$it"] }
            if (mine != null && mine.id != s.id) deletes.add(Kind.SCAN to mine.id)
            localIds[Kind.SCAN]?.get(s.id)?.takeIf { it.clientId != s.clientId }?.let { moveAside(it) }
            val photo = mine ?: photoById[s.id]
            rows.add(
                RecordRow(Kind.SCAN, s.id, s.fieldId, s.clientId, false, encode(ScanDto.serializer(), s),
                    photoPath = photo?.photoPath, photoUploaded = photo?.photoUploaded ?: false)
            )
        }
        out.treatments.forEach { t ->
            t.clientId?.let { localByClient["${Kind.TREATMENT}/$it"] }?.takeIf { it.id != t.id }?.let { deletes.add(Kind.TREATMENT to it.id) }
            localIds[Kind.TREATMENT]?.get(t.id)?.takeIf { it.clientId != t.clientId }?.let { moveAside(it) }
            row(Kind.TREATMENT, t.id, t.fieldId, t.clientId, encode(TreatmentDto.serializer(), t))
        }
        out.verifications.forEach { v ->
            if (!v.historical) existing.filter { it.kind == Kind.VERIFICATION && it.local && it.fieldId == v.fieldId }
                .forEach { deletes.add(Kind.VERIFICATION to it.id) }
            row(Kind.VERIFICATION, v.id, v.fieldId, null, encode(VerificationDto.serializer(), v))
        }

        val serverMs = Iso.ms(out.settings.serverTime)
        val offset = serverMs?.let { it - System.currentTimeMillis() }
        Demo.setClockOffset(offset)
        val meta = listOfNotNull(
            MetaRow(META_CURSOR, out.cursor),
            MetaRow(META_STATION, encode(StationDto.serializer(), out.settings)),
            offset?.let { MetaRow(META_CLOCK, it.toString()) },
            MetaRow(META_LAST_SYNC, Demo.now().toString())
        )
        dao.applyPull(out.full, heldZones, deletes, out.removedFields, rows, meta)
        Log.i(TAG, "Pulled ${if (out.full) "a full snapshot" else "changes since $since"}: ${rows.size} records")
    }

    /** Dismissing a rejection drops the change and the record it would have created. */
    fun dismissRejected() {
        val gone = AppState.rejected.toList()
        AppState.rejected.clear()
        main.launch {
            withContext(writes) {
                val rows = dao.allChanges().filter { r -> r.status == ChangeRow.STATUS_REJECTED && gone.any { it.seq == r.clientSeq } }
                rows.forEach { c ->
                    if (c.localKind != null && c.localId != null) dao.record(c.localKind, c.localId)?.takeIf { it.local }?.let {
                        if (c.localKind == Kind.FIELD) dao.deleteField(c.localId) else dao.delete(c.localKind, c.localId)
                    }
                    dao.deleteChange(c.clientSeq)
                }
            }
            hydrate()
        }
    }

    /**
     * Discards this operator's unsent changes and the records only the phone had, then takes a
     * fresh full copy from the station (now, or at the next sync when there is no signal).
     */
    fun reset() {
        main.launch {
            withContext(Dispatchers.IO) {
                syncLock.withLock {
                    dao.deleteChangesOf(userId)
                    dao.deleteLocal()
                    dao.deleteMeta(META_CURSOR)
                }
            }
            hydrate()
            if (!AppState.offline && secure.signedIn) {
                runCatching { sync() }.onFailure { Log.w(TAG, "Reset download failed", it) }
            }
            AppState.toast(if (AppState.offline) "Unsent changes discarded · the station's copy downloads at the next sync" else "Station data downloaded again")
        }
    }

    fun isOffline(t: Throwable) = t is OfflineException
}
