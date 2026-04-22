package com.gaiaspa.metrics_detection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gaiaspa.metrics_detection.data.model.toBatchLoteGrapeRequest
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.data.model.Lote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * SyncWorker - v8.5 ROBUST UPLOAD
 * Sincroniza lotes locales con el backend.
 * Ajuste: Evita envíos vacíos y clasifica errores terminales para no bloquear la cola.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker_TRACE"
    }

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

    private suspend fun synchronizeLotes(repository: LoteRepository): Boolean {
        val unsyncedLotes = repository.getNotSynced()
        if (unsyncedLotes.isEmpty()) return true

        Log.d(TAG, "Lotes pendientes encontrados: ${unsyncedLotes.size}")
        var overallSuccess = true

        for (lote in unsyncedLotes) {
            try {
                val success = if (lote.toDelete) {
                    processDeletion(repository, lote.id, lote.cloudId)
                    true 
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

        // ✅ AJUSTE ROBUSTEZ: Si tras filtrar no hay nada, es un error TERMINAL.
        if (finalPaths.isEmpty()) {
            Log.e("UPLOAD", "Lote ${lote.id} sin archivos válidos en disco para subir")
            repository.updateSyncError(lote.id, "TERMINAL: Archivos no encontrados")
            return true // Marcamos como 'atendido' para no bloquear la cola con reintentos inútiles
        }

        Log.d("UPLOAD", "Lote ${lote.id} enviando ${finalPaths.size} archivos")

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
            } else false
        } else {
            val code = response.code()
            val errorBody = response.errorBody()?.string() ?: "Error"
            Log.e(TAG, "API FAILURE Lote ${lote.id} (HTTP $code): $errorBody")
            
            // Clasificación: Errores 4xx (negocio/contrato) son terminales para este lote.
            if (code in 400..499 && code != 401) {
                repository.updateSyncError(lote.id, "TERMINAL ($code): $errorBody")
                true // No bloquear cola
            } else {
                repository.updateSyncError(lote.id, "RETRY ($code): $errorBody")
                false // Reintentar (Error 5xx o similar)
            }
        }
    }

    private suspend fun processDeletion(repository: LoteRepository, localId: Long, cloudId: String) {
        if (cloudId.isNotEmpty()) {
            val deleteResponse = repository.deleteLoteGrapeCloud(cloudId)
            if (deleteResponse.isSuccessful || deleteResponse.code() == 404) {
                repository.deleteLocalLote(localId)
            }
        } else {
            repository.deleteLocalLote(localId)
        }
    }
}
