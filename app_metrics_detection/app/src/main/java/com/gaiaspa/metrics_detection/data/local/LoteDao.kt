package com.gaiaspa.metrics_detection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gaiaspa.metrics_detection.data.model.Lote

/**
 * Room DAO for the [Lote] entity.
 *
 * Provides CRUD operations and synchronization queries. Write operations
 * on lots belonging to other users are protected by the
 * `AND userId = :userId` clause.
 */
@Dao
interface LoteDao {

    /**
     * Inserts a lot into the local database. If one already exists with the same
     * `id` (auto-generated or received), it is fully replaced.
     *
     * @return The auto-generated `id` of the inserted lot.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLote(lote: Lote): Long

    /**
     * Updates an existing lot by its primary key.
     *
     * @return Number of affected rows (1 if updated, 0 if it did not exist).
     */
    @Update
    fun updateLote(lote: Lote): Int

    /**
     * Retrieves all lots for the user, ordered from newest to oldest.
     */
    @Query("SELECT * FROM lote WHERE userId = :userId ORDER BY id DESC")
    fun getAllLotes(userId: String): List<Lote>

    /**
     * Retrieves lots pending synchronization (marked with `synced = 0`)
     * for retry submission to the backend.
     */
    @Query("SELECT * FROM lote WHERE userId = :userId AND synced = 0")
    fun getNotSyncedLotes(userId: String): List<Lote>

    /**
     * Marks a lot as successfully synchronized: updates `cloudId`,
     * `cloudImages`, sets `synced = 1`, and clears `syncError`.
     *
     * @return Number of affected rows.
     */
    @Query("UPDATE lote SET cloudId = :cloudId, synced = 1, cloudImages = :cloudImages, syncError = NULL WHERE id = :localLoteId AND userId = :userId")
    fun updateCloudIdImagePathsAndSyncStatus(
        localLoteId: Long,
        cloudId: String,
        cloudImages: String,
        userId: String
    ): Int

    /**
     * Records a synchronization error: writes the error message,
     * marks `synced = 0` so it will be retried on the next cycle.
     *
     * @return Number of affected rows.
     */
    @Query("UPDATE lote SET syncError = :error, synced = 0 WHERE id = :localLoteId AND userId = :userId")
    fun updateSyncError(localLoteId: Long, userId: String, error: String?): Int

    /**
     * Looks up a lot by its local `id` within the user's context.
     *
     * @return The lot or `null` if it does not exist.
     */
    @Query("SELECT * FROM lote WHERE id = :loteId AND userId = :userId LIMIT 1")
    fun getLoteById(loteId: Long, userId: String): Lote?

    /**
     * Physically deletes a lot from the local database.
     *
     * @return Number of deleted rows.
     */
    @Query("DELETE FROM lote WHERE id = :loteId AND userId = :userId")
    fun deleteLote(loteId: Long, userId: String): Int

    /**
     * Marks a lot for logical deletion pending synchronization:
     * `synced = 0` and `toDelete = 1`. The backend will delete it on the next upload.
     *
     * @return Number of affected rows.
     */
    @Query("UPDATE lote SET synced = 0, toDelete = 1 WHERE id = :loteId AND userId = :userId")
    fun markLoteAsNotSyncedAndToDelete(loteId: Long, userId: String): Int

    /**
     * Deletes all lots for the user (useful for logout or reset).
     *
     * @return Number of deleted rows.
     */
    @Query("DELETE FROM lote WHERE userId = :userId")
    fun deleteAllLotes(userId: String): Int

    /**
     * Deletes only the user's already synchronized lots, preserving
     * those pending upload.
     *
     * @return Number of deleted rows.
     */
    @Query("DELETE FROM lote WHERE userId = :userId AND synced = 1")
    fun deleteAllLotesSynced(userId: String): Int

    /**
     * Returns the total number of lots for the user.
     */
    @Query("SELECT COUNT(*) FROM lote WHERE userId = :userId")
    fun getLoteCount(userId: String): Int

    /**
     * Checks whether a `cloudId` already exists in the local database,
     * useful for avoiding duplicates during import from the backend.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM lote WHERE cloudId = :cloudId)")
    fun doesLoteExist(cloudId: String): Boolean
}
