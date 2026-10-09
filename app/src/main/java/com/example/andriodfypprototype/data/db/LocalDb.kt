package com.example.andriodfypprototype.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * The phone's offline store (spec section 43.3). Three tables:
 *
 * - [RecordRow] caches every station record the screens read (fields, seasons, surveys, zones,
 *   scans, treatments, verifications, quadrats, products, species) in its wire shape, keyed by
 *   kind and id. A row written on the phone is `local` until a pull returns the station's copy.
 * - [ChangeRow] is the change log: one row per write, with a monotonic client sequence number
 *   and the payload `POST /sync/push` takes. Rows leave the log when the station applies them;
 *   rejected rows stay, with the reason, until the operator dismisses them.
 * - [MetaRow] holds the pull cursor, the sequence counter and the station's settings.
 */
@Entity(tableName = "records", primaryKeys = ["kind", "id"], indices = [Index("fieldId"), Index("clientId")])
data class RecordRow(
    val kind: String,
    val id: String,
    val fieldId: String?,
    val clientId: String?,
    val local: Boolean,
    val json: String,
    /** Leaf scans only: the captured photo on this phone. */
    val photoPath: String? = null,
    /** Leaf scans only: true once `PUT /scans/{id}/photo` succeeded. */
    val photoUploaded: Boolean = false
)

@Entity(tableName = "changes")
data class ChangeRow(
    @androidx.room.PrimaryKey val clientSeq: Int,
    val clientId: String,
    val entity: String,
    val op: String,
    val at: Long,
    val payload: String,
    val summary: String,
    val bytes: Int,
    val ownedByMobile: Boolean,
    /** pending | rejected */
    val status: String = STATUS_PENDING,
    val reason: String? = null,
    /** Kind and id of the local record this change created, so it can be swapped for the station's. */
    val localKind: String? = null,
    val localId: String? = null,
    /** The operator signed in when the change was made; only their session pushes it. */
    val userId: String? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_REJECTED = "rejected"
    }
}

@Entity(tableName = "meta")
data class MetaRow(@androidx.room.PrimaryKey val key: String, val value: String)

object Kind {
    const val FIELD = "field"
    const val SEASON = "season"
    const val SURVEY = "survey"
    const val ZONE = "zone"
    const val SCAN = "scan"
    const val TREATMENT = "treatment"
    const val VERIFICATION = "verification"
    const val QUADRAT = "quadrat"
    const val PRODUCT = "product"
    const val SPECIES = "species"
}

@Dao
interface StoreDao {

    // ---- records
    @Query("SELECT * FROM records")
    suspend fun allRecords(): List<RecordRow>

    @Query("SELECT * FROM records WHERE kind = :kind")
    suspend fun records(kind: String): List<RecordRow>

    @Query("SELECT * FROM records WHERE kind = :kind AND id = :id")
    suspend fun record(kind: String, id: String): RecordRow?

    @Query("SELECT * FROM records WHERE kind = :kind AND clientId = :clientId LIMIT 1")
    suspend fun recordByClientId(kind: String, clientId: String): RecordRow?

    @Upsert
    suspend fun upsert(rows: List<RecordRow>)

    @Query("DELETE FROM records WHERE kind = :kind AND id = :id")
    suspend fun delete(kind: String, id: String)

    @Query("DELETE FROM records WHERE kind = :kind")
    suspend fun deleteKind(kind: String)

    @Query("DELETE FROM records WHERE fieldId = :fieldId OR (kind = 'field' AND id = :fieldId)")
    suspend fun deleteField(fieldId: String)

    @Query("SELECT * FROM records WHERE kind = 'scan' AND photoPath IS NOT NULL AND photoUploaded = 0 AND local = 0")
    suspend fun photosToUpload(): List<RecordRow>

    // ---- change log
    @Upsert
    suspend fun putChange(row: ChangeRow)

    @Query("SELECT * FROM changes ORDER BY clientSeq")
    suspend fun allChanges(): List<ChangeRow>

    @Query("SELECT * FROM changes WHERE status = 'pending' ORDER BY clientSeq")
    suspend fun pendingChanges(): List<ChangeRow>

    @Query("SELECT COUNT(*) FROM changes WHERE status = 'pending'")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM changes WHERE clientSeq = :seq")
    suspend fun deleteChange(seq: Int)

    @Query("DELETE FROM changes WHERE status = 'rejected'")
    suspend fun deleteRejected()

    @Query("DELETE FROM changes")
    suspend fun deleteAllChanges()

    // ---- meta
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Upsert
    suspend fun putMeta(row: MetaRow)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)

    @Query("DELETE FROM records")
    suspend fun deleteAllRecords()

    @Query("DELETE FROM records WHERE local = 1")
    suspend fun deleteLocal()

    /** A full snapshot replaces the station's records; local ones and zones with unsent edits stay. */
    @Query("DELETE FROM records WHERE local = 0 AND NOT (kind = 'zone' AND fieldId IN (:keepZonesOf))")
    suspend fun deleteStationRecords(keepZonesOf: List<String>)

    @Query("DELETE FROM changes WHERE userId = :userId OR userId IS NULL")
    suspend fun deleteChangesOf(userId: String?)

    @Transaction
    suspend fun applyPull(full: Boolean, keepZonesOf: List<String>, deletes: List<Pair<String, String>>, removedFields: List<String>, rows: List<RecordRow>, meta: List<MetaRow>) {
        if (full) deleteStationRecords(keepZonesOf)
        deletes.forEach { (k, id) -> delete(k, id) }
        removedFields.forEach { deleteField(it) }
        upsert(rows)
        meta.forEach { putMeta(it) }
    }

    /** A local write: the record and its change-log row land together or not at all. */
    @Transaction
    suspend fun write(records: List<RecordRow>, deletes: List<Pair<String, String>>, change: ChangeRow?, seq: Int) {
        deletes.forEach { (k, id) -> delete(k, id) }
        if (records.isNotEmpty()) upsert(records)
        if (change != null) putChange(change)
        putMeta(MetaRow(META_SEQ, seq.toString()))
    }

    companion object {
        const val META_SEQ = "clientSeq"
    }
}

@Database(entities = [RecordRow::class, ChangeRow::class, MetaRow::class], version = 1, exportSchema = true)
abstract class LocalDb : RoomDatabase() {
    abstract fun dao(): StoreDao

    companion object {
        @Volatile private var instance: LocalDb? = null

        fun get(context: Context): LocalDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LocalDb::class.java, "weedreaver.db")
                .build().also { instance = it }
        }
    }
}
