package com.gaiaspa.metrics_detection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gaiaspa.metrics_detection.data.model.Lote

@Dao
interface LoteDao {

    /**
     * Inserta un lote en la base de datos.
     * Reemplaza el lote si ya existe uno con el mismo ID.
     * Retorna el ID generado del lote insertado.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
     fun insertLote(lote: Lote): Long

    /**
     * Obtiene todos los lotes para un usuario específico, ordenados por ID descendente.
     */
    @Query("SELECT * FROM lote WHERE userId = :userId ORDER BY id DESC")
     fun getAllLotes(userId: String): List<Lote>

    /**
     * Obtiene lotes que aún no han sido sincronizados para un usuario específico.
     */
    @Query("SELECT * FROM lote WHERE userId = :userId AND synced = 0")
     fun getNotSyncedLotes(userId: String): List<Lote>

    /**
     * Actualiza el estado de sincronización de un lote específico.
     *
     * @param localLoteId El ID del lote local.
     * @param cloudId El ID del lote en la nube.
     * @param cloudImages Las nuevas rutas de imágenes proporcionadas por la nube (como JSON).
     * @param userId El ID único del usuario.
     * @return El número de filas actualizadas.
     */
    @Query("UPDATE lote SET cloudId = :cloudId, synced = 1, cloudImages = :cloudImages WHERE id = :localLoteId AND userId = :userId")
     fun updateCloudIdImagePathsAndSyncStatus(
        localLoteId: Long,
        cloudId: String,
        cloudImages: String,  // Se pasa la lista de imágenes ya serializada (por ejemplo, en JSON)
        userId: String
    ): Int

    /**
     * Obtiene un lote por su ID y userId.
     */
    @Query("SELECT * FROM lote WHERE id = :loteId AND userId = :userId LIMIT 1")
     fun getLoteById(loteId: Long, userId: String): Lote?

    /**
     * Elimina un lote por su ID y userId.
     */
    @Query("DELETE FROM lote WHERE id = :loteId AND userId = :userId")
     fun deleteLote(loteId: Long, userId: String): Int


    /**
     * Actualiza el campo `synced` a false y `toDelete` a true para un lote específico.
     *
     * @param loteId El ID del lote a actualizar.
     * @param userId El ID del usuario asociado al lote.
     * @return El número de filas afectadas.
     */
    @Query("UPDATE lote SET synced = 0, toDelete = 1 WHERE id = :loteId AND userId = :userId")
    fun markLoteAsNotSyncedAndToDelete(loteId: Long, userId: String): Int


    /**
     * Elimina todos los lotes para un usuario específico.
     */
    @Query("DELETE FROM lote WHERE userId = :userId")
     fun deleteAllLotes(userId: String): Int

    /**
     * Elimina todos los lotes sincronizados para un usuario específico.
     */
    @Query("DELETE FROM lote WHERE userId = :userId AND synced = 1")
    fun deleteAllLotesSynced(userId: String): Int


    /**
     * Obtiene el conteo total de lotes para un usuario específico.
     */
    @Query("SELECT COUNT(*) FROM lote WHERE userId = :userId")
     fun getLoteCount(userId: String): Int

    /**
     * Obtiene lotes dentro de un rango específico para un usuario.
     */
    @Query("SELECT * FROM lote WHERE userId = :userId ORDER BY id DESC LIMIT :pageSize OFFSET :startIndex")
     fun getLotesByRange(userId: String, startIndex: Int, pageSize: Int): List<Lote>

    /**
     * Verifica si existe un lote con el ID especificado.
     *
     * @param loteId El ID del lote a verificar.
     * @return true si el lote existe, false en caso contrario.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM lote WHERE cloudId = :cloudId)")
    fun doesLoteExist(cloudId: String): Boolean
}
