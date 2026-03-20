// SyncWorker.kt
package com.gaiaspa.metrics_detection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gaiaspa.metrics_detection.data.model.toBatchLoteGrapeRequest
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker"
    }

    override suspend fun doWork(): Result {
        val repository = LoteRepository.getInstance(applicationContext)
        return try {
            // Ejecuta la sincronización en el dispatcher IO si es necesario
            withContext(Dispatchers.IO) {
                synchronizeLotes(repository)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error en la sincronización de lotes", e)
            // En caso de error, se retorna retry para que WorkManager reintente la ejecución.
            Result.retry()
        }
    }

    private suspend fun synchronizeLotes(repository: LoteRepository) {
        val unsyncedLotes = repository.getNotSynced()
        Log.d(TAG, "Unsynced lotes: $unsyncedLotes")

        for (lote in unsyncedLotes) {
            Log.d(TAG, "Procesando lote id: ${lote.id}")
            try {
                if (lote.toDelete) {
                    // Lógica de eliminación
                    lote.cloudId.let { cloudId ->
                        if (cloudId != ""){
                            Log.d(TAG, "Eliminando lote en la nube con cloudId: $cloudId")
                            val deleteResponse = repository.deleteLoteGrapeCloud(cloudId)
                            Log.d(TAG, "Respuesta delete: $deleteResponse")
                            // Si la respuesta es exitosa o el backend responde 404 (lote no existe)
                            if (deleteResponse.isSuccessful || deleteResponse.code() == 404) {
                                repository.deleteLocalLote(lote.id)
                                Log.d(TAG, "Lote eliminado localmente: ${lote.id}")
                            } else {
                                Log.e(TAG, "Error al eliminar lote $cloudId: ${deleteResponse.errorBody()}")
                            }
                        }
                    }
                } else {
                    // Lógica de inserción
                    Log.d(TAG, "Insertando lote en la nube para id local: ${lote.id}")
                    val insertResponse = repository.insertLoteGrapeCloud(
                        loteRequest = lote.toBatchLoteGrapeRequest(),
                        imagePaths = lote.images
                    )
                    Log.d(TAG, "Respuesta insert: $insertResponse")

                    if (insertResponse.isSuccessful) {
                        val insertResponseBody = insertResponse.body()
                        if (insertResponseBody != null) {
                            val cloudId = insertResponseBody.loteId
                            val imagePaths: List<String> = insertResponseBody.predicts.map { it.image.imagePath }
                            repository.updateLoteAfterSync(lote.id, cloudId, imagePaths)
                            Log.d(TAG, "Lote actualizado después de sincronizar: ${lote.id} con cloudId: $cloudId")
                        } else {
                            Log.e(TAG, "Error al insertar lote: respuesta sin ID de la nube para lote ${lote.id}")
                        }
                    } else {
                        Log.e(TAG, "Error al insertar lote ${lote.id}: ${insertResponse.errorBody()}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Excepción procesando lote id: ${lote.id}", e)
            }
        }
    }
}
