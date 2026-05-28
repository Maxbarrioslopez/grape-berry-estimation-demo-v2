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
 * [CoroutineWorker] responsible for uploading pending local lots to the backend and deleting
 * lots marked for deletion.
 *
 * ## Role in the architecture
 * It is the core component of offline-first synchronization. It is instantiated from
 * [SyncManager] periodically or on demand, and iterates over the unsynchronized lots in
 * [LoteRepository], deciding for each one whether it should be uploaded (insert) or deleted from
 * the server (delete).
 *
 * ## Error classification
 * - 4xx errors (excluding 401) are treated as **terminal** for that lot: it is marked
 *   as handled to avoid blocking the queue with useless retries.
 * - 5xx errors, IOException, and 401 trigger [Result.retry] so WorkManager
 *   retries later with exponential backoff.
 * - If after filtering files on disk no image is valid, the lot is marked as
 *   terminal error and `true` (handled) is returned regardless.
 */
class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SyncWorker_TRACE"
    }

    /**
     * Iterates over lots pending synchronization via [synchronizeLotes].
     *
     * @return [Result.success] if all lots were processed without error;
     *         [Result.retry] if any lot failed transiently (5xx, IOException);
     *         [Result.failure] only for unexpected critical errors.
     */
    override suspend fun doWork(): Result {
        if (BuildConfig.DEMO_MODE) {
            Log.d(TAG, "DEMO_MODE: Sync skipped, cloud operations disabled.")
            return Result.success()
        }

        val repository = LoteRepository.getInstance(applicationContext)
        Log.d(TAG, "--- SYNC WORKER START ---")
        
        return try {
            val allProcessedSuccessfully = withContext(Dispatchers.IO) {
                synchronizeLotes(repository)
            }
            
            if (allProcessedSuccessfully) {
                Log.d(TAG, "--- SYNC FINISHED: SUCCESS ---")
                Result.success()
            } else {
                Log.w(TAG, "--- SYNC FINISHED: RETRY REQUIRED (Partial failure) ---")
                Result.retry()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during synchronization", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in SyncWorker", e)
            Result.failure()
        }
    }

    /**
     * Iterates over [LoteRepository.getNotSynced] and dispatches each lot to [processUpload]
     * or [processDeletion] according to its [Lote.toDelete] flag.
     *
     * @param repository unique instance of the local repository.
     * @return `true` if **all** lots were processed successfully;
     *         `false` if at least one required a retry.
     */
    private suspend fun synchronizeLotes(repository: LoteRepository): Boolean {
        val unsyncedLotes = repository.getNotSynced()
        if (unsyncedLotes.isEmpty()) return true

        Log.d(TAG, "Pending lots found: ${unsyncedLotes.size}")
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
                Log.e(TAG, "Unexpected failure lot ${lote.id}: ${e.message}")
                repository.updateSyncError(lote.id, e.message ?: "Unexpected failure")
            }
        }
        return overallSuccess
    }

    /**
     * Uploads a lot to the backend after validating that image files exist on disk.
     *
     * ## Flow
     * 1. Filters [Lote.uploadImages] discarding paths that do not point to a real,
     *    non-empty file. If the filter yields the same number of paths as
     *    [Lote.normalizedImages] (and is not empty), the filtered upload paths are used;
     *    otherwise the filtered normalized paths are used.
     * 2. If after filtering no valid image remains, a **terminal** error is marked
     *    (the lot will never be uploadable) and `true` is returned to avoid blocking the queue.
     * 3. If the number of images does not match [Lote.calPredicts], a warning is logged
     *    as a possible backend contract mismatch.
     * 4. Calls [LoteRepository.insertLoteGrapeCloud] and classifies the HTTP response:
     *    - 2xx with non-null body → success, lot is updated and local files are deleted.
     *    - 2xx with null body → retry.
     *    - 4xx (≠401) → terminal error.
     *    - Rest → retry.
     *
     * @return `true` if the lot was handled (success or terminal error), `false` if it must be retried.
     */
    private suspend fun processUpload(repository: LoteRepository, lote: Lote): Boolean {
        // 1. Validate real physical files
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
        Log.d("SYNC_IMAGE_SOURCE", "Lot ${lote.id}: overlayCount=${lote.overlayImages.size} uploadCount=${lote.uploadImages.size} finalPaths=[$logFinalPaths]")

        // ROBUSTNESS: If after filtering there is nothing, it is a TERMINAL error.
        if (finalPaths.isEmpty()) {
            Log.e("SYNC_IMAGE_SOURCE", "Lot ${lote.id} has no valid files on disk to upload")
            repository.updateSyncError(lote.id, "TERMINAL: Files not found")
            return true // Mark as 'handled' to avoid blocking the queue with useless retries
        }

        Log.d("SYNC_IMAGE_SOURCE", "Lot ${lote.id} sending ${finalPaths.size} files (clean uploadImages)")
        if (finalPaths.size != lote.calPredicts.size) {
            val message = "Possible backend contract pending: ${finalPaths.size} images and ${lote.calPredicts.size} predictions"
            Log.w("SYNC_IMAGE_SOURCE", "Lot ${lote.id}: $message")
            repository.updateSyncError(lote.id, message)
        }

        // 2. Backend call
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

            // Classification: 4xx errors (business/contract) are terminal for this lot.
            if (code in 400..499 && code != 401) {
                repository.updateSyncError(lote.id, "TERMINAL ($code): $syncErrorToStore")
                true // Do not block queue
            } else {
                repository.updateSyncError(lote.id, "RETRY ($code): $syncErrorToStore")
                false // Retry (5xx error or similar)
            }
        }
    }

    /**
     * Deletes a lot from the server and subsequently from local storage.
     *
     * If [cloudId] is empty, it is assumed the lot was never uploaded and it is
     * deleted only locally. An HTTP 404 from the server is interpreted as success
     * (the resource no longer exists). Any other error triggers [Result.retry].
     *
     * @return `true` if deletion (local or remote) was successful, `false` if it must be retried.
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
                    Log.w(TAG, "Could not delete cloudId $cloudId: $message")
                    repository.updateSyncError(localId, message)
                    false
                }
            } else {
                repository.deleteLocalLote(localId)
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error deleting lot $localId cloudId=$cloudId", e)
            repository.updateSyncError(localId, "DELETE RETRY: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting lot $localId cloudId=$cloudId", e)
            repository.updateSyncError(localId, "DELETE ERROR: ${e.message}")
            false
        }
    }
}
