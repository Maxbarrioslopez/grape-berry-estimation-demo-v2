package com.gaiaspa.metrics_detection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gaiaspa.metrics_detection.data.model.Lote

@Dao
interface LoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLote(lote: Lote): Long

    @Query("SELECT * FROM lote WHERE userId = :userId ORDER BY id DESC")
    fun getAllLotes(userId: String): List<Lote>

    @Query("SELECT * FROM lote WHERE userId = :userId AND synced = 0 AND toDelete = 0")
    fun getNotSyncedLotes(userId: String): List<Lote>

    @Query("UPDATE lote SET cloudId = :cloudId, synced = 1, cloudImages = :cloudImages, syncError = NULL WHERE id = :localLoteId AND userId = :userId")
    fun updateCloudIdImagePathsAndSyncStatus(
        localLoteId: Long,
        cloudId: String,
        cloudImages: String,
        userId: String
    ): Int

    @Query("UPDATE lote SET syncError = :error, synced = 0 WHERE id = :localLoteId AND userId = :userId")
    fun updateSyncError(localLoteId: Long, userId: String, error: String?): Int

    @Query("SELECT * FROM lote WHERE id = :loteId AND userId = :userId LIMIT 1")
    fun getLoteById(loteId: Long, userId: String): Lote?

    @Query("DELETE FROM lote WHERE id = :loteId AND userId = :userId")
    fun deleteLote(loteId: Long, userId: String): Int

    @Query("UPDATE lote SET synced = 0, toDelete = 1 WHERE id = :loteId AND userId = :userId")
    fun markLoteAsNotSyncedAndToDelete(loteId: Long, userId: String): Int

    @Query("DELETE FROM lote WHERE userId = :userId")
    fun deleteAllLotes(userId: String): Int

    @Query("DELETE FROM lote WHERE userId = :userId AND synced = 1")
    fun deleteAllLotesSynced(userId: String): Int

    @Query("SELECT COUNT(*) FROM lote WHERE userId = :userId")
    fun getLoteCount(userId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM lote WHERE cloudId = :cloudId)")
    fun doesLoteExist(cloudId: String): Boolean
}
