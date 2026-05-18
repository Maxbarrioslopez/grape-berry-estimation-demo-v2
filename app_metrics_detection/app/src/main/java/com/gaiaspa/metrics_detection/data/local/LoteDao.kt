package com.gaiaspa.metrics_detection.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.gaiaspa.metrics_detection.data.model.Lote

/**
 * DAO de Room para la entidad [Lote].
 *
 * Proporciona operaciones CRUD y consultas de sincronización. Las operaciones
 * de escritura sobre lotes ajenos al usuario actual están protegidas por la
 * cláusula `AND userId = :userId`.
 */
@Dao
interface LoteDao {

    /**
     * Inserta un lote en la base de datos local. Si ya existe uno con el mismo
     * `id` (autogenerado o recibido), lo reemplaza completamente.
     *
     * @return El `id` autogenerado del lote insertado.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLote(lote: Lote): Long

    /**
     * Actualiza un lote existente buscándolo por su clave primaria.
     *
     * @return Cantidad de filas afectadas (1 si se actualizó, 0 si no existía).
     */
    @Update
    fun updateLote(lote: Lote): Int

    /**
     * Obtiene todos los lotes del usuario ordenados del más reciente al más antiguo.
     */
    @Query("SELECT * FROM lote WHERE userId = :userId ORDER BY id DESC")
    fun getAllLotes(userId: String): List<Lote>

    /**
     * Obtiene los lotes pendientes de sincronización (marcados con `synced = 0`)
     * para reintentar el envío al backend.
     */
    @Query("SELECT * FROM lote WHERE userId = :userId AND synced = 0")
    fun getNotSyncedLotes(userId: String): List<Lote>

    /**
     * Marca un lote como sincronizado exitosamente: actualiza `cloudId`,
     * `cloudImages`, pone `synced = 1` y limpia `syncError`.
     *
     * @return Cantidad de filas afectadas.
     */
    @Query("UPDATE lote SET cloudId = :cloudId, synced = 1, cloudImages = :cloudImages, syncError = NULL WHERE id = :localLoteId AND userId = :userId")
    fun updateCloudIdImagePathsAndSyncStatus(
        localLoteId: Long,
        cloudId: String,
        cloudImages: String,
        userId: String
    ): Int

    /**
     * Registra un error de sincronización: escribe el mensaje de error,
     * marca `synced = 0` para que se reintente en el siguiente ciclo.
     *
     * @return Cantidad de filas afectadas.
     */
    @Query("UPDATE lote SET syncError = :error, synced = 0 WHERE id = :localLoteId AND userId = :userId")
    fun updateSyncError(localLoteId: Long, userId: String, error: String?): Int

    /**
     * Busca un lote por su `id` local dentro del contexto del usuario.
     *
     * @return El lote o `null` si no existe.
     */
    @Query("SELECT * FROM lote WHERE id = :loteId AND userId = :userId LIMIT 1")
    fun getLoteById(loteId: Long, userId: String): Lote?

    /**
     * Elimina físicamente un lote de la base de datos local.
     *
     * @return Cantidad de filas eliminadas.
     */
    @Query("DELETE FROM lote WHERE id = :loteId AND userId = :userId")
    fun deleteLote(loteId: Long, userId: String): Int

    /**
     * Marca un lote para eliminación lógica pendiente de sincronización:
     * `synced = 0` y `toDelete = 1`. El backend lo borrará en el próximo envío.
     *
     * @return Cantidad de filas afectadas.
     */
    @Query("UPDATE lote SET synced = 0, toDelete = 1 WHERE id = :loteId AND userId = :userId")
    fun markLoteAsNotSyncedAndToDelete(loteId: Long, userId: String): Int

    /**
     * Elimina todos los lotes del usuario (útil para logout o reseteo).
     *
     * @return Cantidad de filas eliminadas.
     */
    @Query("DELETE FROM lote WHERE userId = :userId")
    fun deleteAllLotes(userId: String): Int

    /**
     * Elimina únicamente los lotes ya sincronizados del usuario, preservando
     * aquellos pendientes de envío.
     *
     * @return Cantidad de filas eliminadas.
     */
    @Query("DELETE FROM lote WHERE userId = :userId AND synced = 1")
    fun deleteAllLotesSynced(userId: String): Int

    /**
     * Retorna la cantidad total de lotes del usuario.
     */
    @Query("SELECT COUNT(*) FROM lote WHERE userId = :userId")
    fun getLoteCount(userId: String): Int

    /**
     * Verifica si un `cloudId` ya existe en la base de datos local,
     * útil para evitar duplicados durante la importación desde el backend.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM lote WHERE cloudId = :cloudId)")
    fun doesLoteExist(cloudId: String): Boolean
}
