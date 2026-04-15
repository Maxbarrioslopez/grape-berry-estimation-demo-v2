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
import java.io.IOException

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
            withContext(Dispatchers.IO) {
                synchronizeLotes(repository)
            }
            Result.success()
        } catch (e: IOException) {
            Log.e(TAG, "Error de red durante la sincronización", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Error crítico en SyncWorker", e)
            Result.failure()
        }
    }

    private suspend fun synchronizeLotes(repository: LoteRepository) {
        val unsyncedLotes = repository.getNotSynced()
        Log.d(TAG, "Lotes pendientes: ${unsyncedLotes.size}")

        for (lote in unsyncedLotes) {
            try {
                if (lote.toDelete) {
                    if (lote.cloudId.isNotEmpty()) {
                        val deleteResponse = repository.deleteLoteGrapeCloud(lote.cloudId)
                        if (deleteResponse.isSuccessful || deleteResponse.code() == 404) {
                            repository.deleteLocalLote(lote.id)
                        } else {
                            repository.updateSyncError(lote.id, "Error al eliminar: ${deleteResponse.code()}")
                        }
                    } else {
                        repository.deleteLocalLote(lote.id)
                    }
                } else {
                    // ✅ CORRECCIÓN: Usar normalizedImages en lugar del campo inexistente images
                    val insertResponse = repository.insertLoteGrapeCloud(
                        loteRequest = lote.toBatchLoteGrapeRequest(),
                        imagePaths = lote.normalizedImages
                    )

                    if (insertResponse.isSuccessful) {
                        insertResponse.body()?.let { body ->
                            val cloudImages = body.predicts.map { it.image.imagePath }
                            repository.updateLoteAfterSync(lote.id, body.loteId, cloudImages)
                        }
                    } else {
                        val errorBody = insertResponse.errorBody()?.string() ?: "Error desconocido"
                        repository.updateSyncError(lote.id, "Error servidor (${insertResponse.code()}): $errorBody")
                    }
                }
            } catch (e: IOException) {
                repository.updateSyncError(lote.id, "Sin conexión")
                throw e // Propagar para retry
            } catch (e: Exception) {
                Log.e(TAG, "Fallo lote ${lote.id}", e)
                repository.updateSyncError(lote.id, e.message ?: "Fallo inesperado")
            }
        }
    }
}
