package com.photocleanse.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.photocleanse.dupes.Candidate
import kotlinx.coroutines.flow.Flow

const val STATUS_NEW = 0
const val STATUS_DONE = 1 // reviewed and kept, waiting to be filed into the photos folder
const val STATUS_FILED = 2 // moved into the photos folder

@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["path"], unique = true),
        Index(value = ["status", "hidden", "modified"]),
    ],
)
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val modified: Long,
    val size: Long,
    val hidden: Boolean,
    val status: Int = STATUS_NEW,
)

/** Perceptual fingerprint of one photo. Valid only while [fileSize]/[fileModified] match the photo row. */
@Entity(
    tableName = "photo_features",
    foreignKeys = [ForeignKey(Photo::class, ["id"], ["photoId"], onDelete = ForeignKey.CASCADE)],
)
data class PhotoFeatures(
    @PrimaryKey val photoId: Long,
    val valid: Boolean,
    val phash: Long,
    val colorSig: Long,
    val width: Int,
    val height: Int,
    val takenAt: Long,
    val sharpness: Float,
    val hasCameraExif: Boolean,
    val fileSize: Long,
    val fileModified: Long,
)

/** A cluster of duplicate photos. Rebuilt wholesale after every analysis run. [kind] = DupKind ordinal. */
@Entity(tableName = "dup_groups")
data class DupGroupEntity(@PrimaryKey val id: Long, val kind: Int)

/** A photo belongs to at most one group (photoId is the primary key). */
@Entity(
    tableName = "dup_members",
    foreignKeys = [
        ForeignKey(Photo::class, ["id"], ["photoId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(DupGroupEntity::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["groupId"])],
)
data class DupMemberEntity(
    @PrimaryKey val photoId: Long,
    val groupId: Long,
    val score: Float,
    val isBest: Boolean,
)

/** Photos the user has already decided on in a duplicate review; never clustered again. */
@Entity(
    tableName = "dup_dismissed",
    foreignKeys = [ForeignKey(Photo::class, ["id"], ["photoId"], onDelete = ForeignKey.CASCADE)],
)
data class DupDismissed(@PrimaryKey val photoId: Long)

data class IdPath(val id: Long, val path: String)

data class Counts(
    val total: Int,
    val done: Int,
    val remaining: Int,
    @ColumnInfo(name = "hiddenRemaining") val hiddenRemaining: Int,
)

data class DupRow(
    val groupId: Long,
    val kind: Int,
    val score: Float,
    val isBest: Boolean,
    val width: Int,
    val height: Int,
    val sharpness: Float,
    @ColumnInfo(name = "hasCameraExif") val hasCameraExif: Boolean,
    @Embedded val photo: Photo,
)

@Dao
abstract class PhotoDao {
    /** Existing rows (and their review status) are never touched: path is unique, conflicts are ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertAll(photos: List<Photo>)

    @Query("SELECT id, path FROM photos")
    abstract suspend fun idPaths(): List<IdPath>

    @Query("DELETE FROM photos WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<Long>)

    @Query(
        "SELECT * FROM photos WHERE status = 0 AND (:includeHidden = 1 OR hidden = 0) " +
            "ORDER BY modified DESC, id ASC LIMIT :limit",
    )
    abstract suspend fun nextBatch(includeHidden: Int, limit: Int): List<Photo>

    @Query("UPDATE photos SET status = 1 WHERE id IN (:ids)")
    abstract suspend fun markDone(ids: List<Long>)

    /** Kept photos that have not been filed yet, newest first. */
    @Query(
        "SELECT * FROM photos WHERE status = 1 AND (:includeHidden = 1 OR hidden = 0) " +
            "ORDER BY modified DESC, id ASC LIMIT :limit",
    )
    abstract suspend fun nextFileBatch(includeHidden: Int, limit: Int): List<Photo>

    @Query("SELECT COUNT(*) FROM photos WHERE status = 1 AND (:includeHidden = 1 OR hidden = 0)")
    abstract fun toFileCount(includeHidden: Int): Flow<Int>

    /** REPLACE only ever drops a stale row for a file that is no longer there; the unique path stays unique. */
    @Query("UPDATE OR REPLACE photos SET path = :path, status = 2 WHERE id = :id")
    abstract suspend fun markFiled(id: Long, path: String)

    @Transaction
    open suspend fun applyFiling(moved: List<Pair<Long, String>>) {
        for ((id, path) in moved) markFiled(id, path)
    }

    @Query("UPDATE photos SET status = 0 WHERE status = 1")
    abstract suspend fun resetStatuses()

    @Query(
        "SELECT COUNT(*) AS total, " +
            "COALESCE(SUM(status >= 1), 0) AS done, " +
            "COALESCE(SUM(status = 0), 0) AS remaining, " +
            "COALESCE(SUM(status = 0 AND hidden = 1), 0) AS hiddenRemaining " +
            "FROM photos",
    )
    abstract fun counts(): Flow<Counts>

    // ---- duplicate detection ----

    @Query(
        "SELECT p.* FROM photos p LEFT JOIN photo_features f ON f.photoId = p.id " +
            "WHERE f.photoId IS NULL OR f.fileSize != p.size OR f.fileModified != p.modified",
    )
    abstract suspend fun needingFeatures(): List<Photo>

    /**
     * Single-statement insert that silently does nothing when the photo row is already gone (deleted by the user
     * while its fingerprint was being computed). A plain insert would violate the foreign key and crash.
     */
    @Query(
        "INSERT OR REPLACE INTO photo_features (photoId, valid, phash, colorSig, width, height, takenAt, sharpness, " +
            "hasCameraExif, fileSize, fileModified) SELECT :photoId, :valid, :phash, :colorSig, :width, :height, " +
            ":takenAt, :sharpness, :hasCameraExif, :fileSize, :fileModified " +
            "WHERE EXISTS (SELECT 1 FROM photos WHERE id = :photoId)",
    )
    abstract suspend fun upsertFeatureIfPhotoExists(
        photoId: Long, valid: Boolean, phash: Long, colorSig: Long, width: Int, height: Int,
        takenAt: Long, sharpness: Float, hasCameraExif: Boolean, fileSize: Long, fileModified: Long,
    )

    @Transaction
    open suspend fun upsertFeatures(features: List<PhotoFeatures>) {
        for (f in features) {
            upsertFeatureIfPhotoExists(
                f.photoId, f.valid, f.phash, f.colorSig, f.width, f.height, f.takenAt, f.sharpness,
                f.hasCameraExif, f.fileSize, f.fileModified,
            )
        }
    }

    @Query(
        "SELECT p.id AS id, p.path AS path, p.size AS size, p.hidden AS hidden, f.width AS width, " +
            "f.height AS height, f.takenAt AS takenAt, f.phash AS phash, f.colorSig AS colorSig, " +
            "f.sharpness AS sharpness, f.hasCameraExif AS hasCameraExif " +
            "FROM photos p JOIN photo_features f ON f.photoId = p.id " +
            "WHERE p.status = 0 AND f.valid = 1 AND f.fileSize = p.size AND f.fileModified = p.modified " +
            "AND p.id NOT IN (SELECT photoId FROM dup_dismissed)",
    )
    abstract suspend fun candidates(): List<Candidate>

    @Query("DELETE FROM dup_groups")
    abstract suspend fun deleteAllGroups()

    @Insert
    abstract suspend fun insertGroups(groups: List<DupGroupEntity>)

    @Insert
    abstract suspend fun insertMembers(members: List<DupMemberEntity>)

    /** Like [insertMembers] but skips photos deleted in the meantime instead of violating the foreign key. */
    @Query(
        "INSERT OR IGNORE INTO dup_members (photoId, groupId, score, isBest) SELECT :photoId, :groupId, :score, " +
            ":isBest WHERE EXISTS (SELECT 1 FROM photos WHERE id = :photoId)",
    )
    abstract suspend fun insertMemberIfPhotoExists(photoId: Long, groupId: Long, score: Float, isBest: Boolean)

    @Transaction
    open suspend fun replaceGroups(groups: List<DupGroupEntity>, members: List<DupMemberEntity>) {
        deleteAllGroups()
        insertGroups(groups)
        for (m in members) insertMemberIfPhotoExists(m.photoId, m.groupId, m.score, m.isBest)
        pruneGroups()
    }

    @Query(
        "SELECT g.id AS groupId, g.kind AS kind, m.score AS score, m.isBest AS isBest, f.width AS width, " +
            "f.height AS height, f.sharpness AS sharpness, f.hasCameraExif AS hasCameraExif, p.* " +
            "FROM dup_groups g JOIN dup_members m ON m.groupId = g.id JOIN photos p ON p.id = m.photoId " +
            "JOIN photo_features f ON f.photoId = p.id WHERE p.status = 0 ORDER BY g.id ASC, m.score DESC",
    )
    abstract suspend fun dupRows(): List<DupRow>

    /** Number of groups that still have 2+ unreviewed photos (and no hidden-folder photo unless included). */
    @Query(
        "SELECT COUNT(*) FROM dup_groups g WHERE " +
            "(SELECT COUNT(*) FROM dup_members m JOIN photos p ON p.id = m.photoId " +
            "WHERE m.groupId = g.id AND p.status = 0) >= 2 AND " +
            "(:includeHidden = 1 OR NOT EXISTS (SELECT 1 FROM dup_members m JOIN photos p ON p.id = m.photoId " +
            "WHERE m.groupId = g.id AND p.hidden = 1))",
    )
    abstract fun dupGroupCount(includeHidden: Int): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun dismiss(list: List<DupDismissed>)

    @Query("DELETE FROM dup_members WHERE photoId IN (SELECT photoId FROM dup_dismissed)")
    abstract suspend fun deleteDismissedMembers()

    @Query(
        "DELETE FROM dup_groups WHERE id NOT IN " +
            "(SELECT groupId FROM dup_members GROUP BY groupId HAVING COUNT(*) >= 2)",
    )
    abstract suspend fun pruneGroups()

    /**
     * Applies the outcome of a confirmed review atomically: [deletedIds] (files really gone) leave the index,
     * [doneIds] are marked reviewed. With [dismissDuplicates] the kept photos also never form duplicate groups again.
     */
    @Transaction
    open suspend fun applyReview(deletedIds: List<Long>, doneIds: List<Long>, dismissDuplicates: Boolean) {
        if (deletedIds.isNotEmpty()) deleteByIds(deletedIds)
        if (doneIds.isNotEmpty()) markDone(doneIds)
        if (dismissDuplicates && doneIds.isNotEmpty()) {
            dismiss(doneIds.map { DupDismissed(it) })
            deleteDismissedMembers()
        }
        pruneGroups()
    }
}

@Database(
    entities = [Photo::class, PhotoFeatures::class, DupGroupEntity::class, DupMemberEntity::class, DupDismissed::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): PhotoDao
}
