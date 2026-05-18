package com.gaiaspa.metrics_detection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import com.gaiaspa.metrics_detection.BuildConfig
import androidx.work.WorkerParameters
import com.gaiaspa.metrics_detection.data.model.toBatchLoteGrapeRequest
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.data.model.Lote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * [CoroutineWorker] responsable de subir lotes locales pendientes al backend y eliminar
 * lotes marcados para borrado.
 *
 * ## Rol en la arquitectura
 * Es el componente central de la sincronización offline-first. Se instancia desde
 * [SyncManager] periódicamente o bajo demanda, y recorre los lotes no sincronizados en
 * [LoteRepository] decidiendo por cada uno si debe subirse (insert) o eliminarse del
 * servidor (delete).
 *
 * ## Clasificación de errores
 * - Errores 4xx (excepto 401) se tratan como **terminales** para ese lote: se marca
 *   como atendido para no bloquear la cola con reintentos inútiles.
 * - Errores 5xx, IOException, y 401 provocan [Result.retry] para que WorkManager
 *   reintente más tarde con backoff exponencial.
 * - Si tras filtrar archivos en disco ninguna imagen es válida, el lote se marca como
 *   error terminal y se devuelve `true` (atendido) igualmente.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker_TRACE"
    }

    /**
     * Itera los lotes pendientes de sincronización mediante [synchronizeLotes].
     *
     * @return [Result.success] si todos los lotes se procesaron sin error;
     *         [Result.retry] si algún lote falló de forma transitoria (5xx, IOException);
     *         [Result.failure] solo ante errores críticos inesperados.
     */
    override suspend fun doWork(): Result {
        val repository = LoteRepository.getInstance(applicationContext)
        Log.d(TAG, "--- INICIO SYNC WORKER ---")
        
        return try {
            val allProcessedSuccessfully = withContext(Dispatchers.IO) {
                synchronizeLotes(repository)
            }
            
            if (allProcessedSuccessfully) {
                Log.d(TAG, "--- SYNC FINALIZADO: SUCCESS ---")
                Result.success()
            } else {
                Log.w(TAG, "--- SYNC FINALIZADO: REINTENTO REQUERIDO (Fallo parcial) ---")
                Result.retry()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error de red durante la sincronización", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico en SyncWorker", e)
            Result.failure()
        }
    }

    /**
     * Recorre [LoteRepository.getNotSynced] y despacha cada lote a [processUpload]
     * o [processDeletion] según su marca [Lote.toDelete].
     *
     * @param repository instancia única del repositorio local.
     * @return `true` si **todos** los lotes se procesaron exitosamente;
     *         `false` si al menos uno requirió reintento.
     */
    private suspend fun synchronizeLotes(repository: LoteRepository): Boolean {
        val unsyncedLotes = repository.getNotSynced()
        if (unsyncedLotes.isEmpty()) return true

        Log.d(TAG, "Lotes pendientes encontrados: ${unsyncedLotes.size}")
        var overallSuccess = true

        for (lote in unsyncedLotes) {
            try {
                val success = if (lote.toDelete) {
                    processDeletion(repository, lote.id, lote.cloudId)
                } else {
                    processUpload(repository, lote)
                }
                if (!success) overallSuccess = false
            } catch (e: Exception) {
                overallSuccess = false
                Log.e(TAG, "Fallo inesperado lote ${lote.id}: ${e.message}")
                repository.updateSyncError(lote.id, e.message ?: "Fallo inesperado")
            }
        }
        return overallSuccess
    }

    /**
     * Sube un lote al backend tras validar que los archivos de imagen existen en disco.
     *
     * ## Flujo
     * 1. Filtra [Lote.uploadImages] descartando paths que no apunten a un archivo real
     *    y no vacío. Si el filtro produce la misma cantidad de paths que
     *    [Lote.normalizedImages] (y no está vacío), se usan los upload filtrados;
     *    en caso contrario se usan los normalized filtrados.
     * 2. Si tras el filtro no queda ninguna imagen válida se marca error **terminal**
     *    (el lote no podrá subirse nunca) y se devuelve `true` para no bloquear la cola.
     * 3. Si el número de imágenes no coincide con [Lote.calPredicts] se registra un
     *    warning como posible incumplimiento de contrato con el backend.
     * 4. Llama a [LoteRepository.insertLoteGrapeCloud] y clasifica la respuesta HTTP:
     *    - 2xx con body no nulo → éxito, se actualiza el lote y se borran los archivos locales.
     *    - 2xx con body nulo → reintento.
     *    - 4xx (≠401) → error terminal.
     *    - Resto → reintento.
     *
     * @return `true` si el lote fue atendido (éxito o error terminal), `false` si debe reintentarse.
     */
    private suspend fun processUpload(repository: LoteRepository, lote: Lote): Boolean {
        // 1. Validar archivos físicos reales
        val uploadPaths = lote.uploadImages.filter { path ->
            val f = File(path.replace("file://", ""))
            f.exists() && f.length() > 0
        }

        val finalPaths = if (uploadPaths.size == lote.normalizedImages.size && uploadPaths.isNotEmpty()) {
            uploadPaths
        } else {
            lote.normalizedImages.filter { path ->
                val f = File(path.replace("file://", ""))
                f.exists() && f.length() > 0
            }
        }

        val logFinalPaths = finalPaths.joinToString(", ") { p -> p.substringAfterLast('/') }
        Log.d("SYNC_IMAGE_SOURCE", "Lote ${lote.id}: overlayCount=${lote.overlayImages.size} uploadCount=${lote.uploadImages.size} finalPaths=[$logFinalPaths]")

        // ✅ AJUSTE ROBUSTEZ: Si tras filtrar no hay nada, es un error TERMINAL.
        if (finalPaths.isEmpty()) {
            Log.e("SYNC_IMAGE_SOURCE", "Lote ${lote.id} sin archivos válidos en disco para subir")
            repository.updateSyncError(lote.id, "TERMINAL: Archivos no encontrados")
            return true // Marcamos como 'atendido' para no bloquear la cola con reintentos inútiles
        }

        Log.d("SYNC_IMAGE_SOURCE", "Lote ${lote.id} enviando ${finalPaths.size} archivos (uploadImages limpias)")
        if (finalPaths.size != lote.calPredicts.size) {
            val message = "Posible contrato backend pendiente: ${finalPaths.size} imagenes y ${lote.calPredicts.size} predicciones"
            Log.w("SYNC_IMAGE_SOURCE", "Lote ${lote.id}: $message")
            repository.updateSyncError(lote.id, message)
        }

        // 2. Llamada al Backend
        val response = repository.insertLoteGrapeCloud(
            loteRequest = lote.toBatchLoteGrapeRequest(),
            imagePaths = finalPaths
        )

        return if (response.isSuccessful) {
            val body = response.body()
            if (body != null) {
                Log.d(TAG, "API SUCCESS Lote ${lote.id}: Cloud ID ${body.loteId}")
                repository.updateLoteAfterSync(lote.id, body.loteId, body.predicts.map { it.image.imagePath })
                lote.uploadImages.forEach { path ->
                    val file = File(path.replace("file://", ""))
                    if (file.exists()) file.delete()
                }
                true
            } else {
                Log.w(TAG, "API SUCCESS Lote ${lote.id} pero body null")
                false
            }
        } else {
            val code = response.code()
            val rawErrorBody = runCatching { response.errorBody()?.string() }.getOrNull()
            val safeError = "HTTP $code"
            val syncErrorToStore = if (BuildConfig.DEBUG) {
                rawErrorBody ?: safeError
            } else {
                safeError
            }

            if (BuildConfig.DEBUG) {
                Log.e(TAG, "API FAILURE Lote ${lote.id} (HTTP $code): $rawErrorBody")
            } else {
                Log.e(TAG, "API FAILURE Lote ${lote.id}: $safeError")
            }

            // Clasificación: Errores 4xx (negocio/contrato) son terminales para este lote.
            if (code in 400..499 && code != 401) {
                repository.updateSyncError(lote.id, "TERMINAL ($code): $syncErrorToStore")
                true // No bloquear cola
            } else {
                repository.updateSyncError(lote.id, "RETRY ($code): $syncErrorToStore")
                false // Reintentar (Error 5xx o similar)
            }
        }
    }

    /**
     * Elimina un lote del servidor y posteriormente del almacenamiento local.
     *
     * Si [cloudId] está vacío se asume que el lote nunca llegó a subirse y se borra
     * solo localmente. Un HTTP 404 del servidor se interpreta como éxito (el recurso
     * ya no existe). Cualquier otro error produce [Result.retry].
     *
     * @return `true` si la eliminación (local o remota) fue exitosa, `false` si debe reintentarse.
     */
    private suspend fun processDeletion(repository: LoteRepository, localId: Long, cloudId: String): Boolean {
        return try {
            if (cloudId.isNotEmpty()) {
                val deleteResponse = repository.deleteLoteGrapeCloud(cloudId)
                if (deleteResponse.isSuccessful || deleteResponse.code() == 404) {
                    repository.deleteLocalLote(localId)
                    true
                } else {
                    val message = "DELETE RETRY (${deleteResponse.code()})"
                    Log.w(TAG, "No se pudo borrar cloudId $cloudId: $message")
                    repository.updateSyncError(localId, message)
                    false
                }
            } else {
                repository.deleteLocalLote(localId)
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error de red eliminando lote $localId cloudId=$cloudId", e)
            repository.updateSyncError(localId, "DELETE RETRY: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando lote $localId cloudId=$cloudId", e)
            repository.updateSyncError(localId, "DELETE ERROR: ${e.message}")
            false
        }
    }
}
