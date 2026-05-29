/**
 * Singleton repository that abstracts read/write operations on grape detection
 * lots, combining local storage (Room) and remote storage (Retrofit to the backend).
 *
 * Architecture:
 * - Data layer (data/repository): orchestrates LoteDao (local) and ApiService (remote).
 * - Singleton pattern with double-checked locking for thread-safe access from
 *   multiple components (Activities, Workers, ViewModels).
 * - All remote operations are suspend and run on Dispatchers.IO.
 *
 * User scope: each local operation filters by the userId of the currently
 * authenticated user (obtained from TokenProvider), ensuring data isolation
 * between accounts.
 */
package com.gaiaspa.metrics_detection.data.repository

import android.content.Context
import android.util.Log
import com.gaiaspa.metrics_detection.BuildConfig
import com.gaiaspa.metrics_detection.data.local.DatabaseProvider
import com.gaiaspa.metrics_detection.data.local.LoteDao
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.model.request.BatchLoteGrapeRequest
import com.gaiaspa.metrics_detection.data.model.response.DeleteBatchGrapeResponse
import com.gaiaspa.metrics_detection.data.model.response.LoteResponse
import com.gaiaspa.metrics_detection.network.ApiClient
import com.gaiaspa.metrics_detection.network.ApiService
import com.gaiaspa.metrics_detection.network.TokenProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import retrofit2.Response

/**
 * Result of a local deletion operation.
 * @property deleted true if at least one row was deleted in Room.
 * @property missingLote true if the lot did not exist in the local database.
 * @property failedFileCount number of physical files that could not be deleted from the filesystem.
 */
data class LocalDeleteResult(
    val deleted: Boolean,
    val missingLote: Boolean = false,
    val failedFileCount: Int = 0
)

class LoteRepository private constructor(
    private val loteDao: LoteDao,
    private val apiService: ApiService,
    private val context: Context,
) {
    private val gson = Gson()
    
    companion object {
        @Volatile private var INSTANCE: LoteRepository? = null
        private const val TAG = "LoteRepository"

        /**
         * Obtains the unique repository instance, creating it if necessary.
         * @param context used to initialize the Room database and API client.
         * @return the singleton instance of LoteRepository.
         */
        fun getInstance(context: Context): LoteRepository {
            return INSTANCE ?: synchronized(this) {
                val database = DatabaseProvider.getDatabase(context)
                val apiService = ApiClient.create(context)
                val instance = LoteRepository(
                    loteDao = database.loteDao(),
                    apiService = apiService,
                    context = context.applicationContext
                )
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * @return the ID of the currently authenticated user, or "undefined_user" if no session.
     */
    fun getCurrentUserId(): String {
        TokenProvider.init(context)
        return TokenProvider.getUserId().ifBlank { "undefined_user" }
    }

    // ── Local operations (Room) ───────────────────────────────────────────────

    /**
     * Inserts a new lot into Room, associating it with the current user and
     * marking it as unsynchronized.
     */
    fun insertLocalLote(lote: Lote) {
        val uid = getCurrentUserId()
        loteDao.insertLote(lote.copy(userId = uid, synced = false))
    }

    /** @return all lots for the current user, sorted by descending prediction date. */
    fun getAllLotes(): List<Lote> = loteDao.getAllLotes(getCurrentUserId()).sortedByDescending { it.predictedAt }
    /** @return total count of lots for the current user in Room. */
    fun getLoteCount(): Int = loteDao.getLoteCount(getCurrentUserId())
    /** @return lots pending synchronization (synced = false). */
    fun getNotSynced(): List<Lote> = loteDao.getNotSyncedLotes(getCurrentUserId())
    /** @return the lot with the given local ID, or null if it does not exist or belongs to another user. */
    fun getLoteById(loteId: Long): Lote? = loteDao.getLoteById(loteId, getCurrentUserId())

    /**
     * Deletes a lot and its associated physical images without touching backend or remote cloudId.
     */
    fun deleteLocalLote(loteId: Long): LocalDeleteResult {
        val userId = getCurrentUserId()
        return try {
            val lote = loteDao.getLoteById(loteId, userId)
            if (lote == null) {
                Log.w(TAG, "deleteLocalLote: lot $loteId not found for user $userId")
                return LocalDeleteResult(deleted = false, missingLote = true)
            }

            val allPaths = mutableListOf<String>()
            allPaths.addAll(lote.sourceImages)
            allPaths.addAll(lote.normalizedImages)
            allPaths.addAll(lote.uploadImages)
            allPaths.addAll(lote.overlayImages)

            val failedFiles = deleteLocalFiles(allPaths)
            val deletedRows = loteDao.deleteLote(loteId, userId)
            LocalDeleteResult(
                deleted = deletedRows > 0,
                failedFileCount = failedFiles
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error deleteLocalLote", e)
            LocalDeleteResult(deleted = false)
        }
    }

    /**
     * Marks a lot as unsynchronized and pending remote deletion.
     * Useful when a synchronization did not complete and a retry is needed.
     */
    fun markLoteAsNotSyncedAndToDelete(loteId: Long) = loteDao.markLoteAsNotSyncedAndToDelete(loteId, getCurrentUserId())
    /** Records a synchronization error message for the specified lot. */
    fun updateSyncError(loteId: Long, error: String?) = loteDao.updateSyncError(loteId, getCurrentUserId(), error)
    /**
     * Deletes all lots and their associated physical files for the current user.
     * @return number of rows deleted from Room.
     */
    fun deleteAllData(): Int {
        val userId = getCurrentUserId()
        return try {
            val lotes = loteDao.getAllLotes(userId)
            lotes.forEach { deleteLocalFiles(it.localFilePaths()) }
            loteDao.deleteAllLotes(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleteAllData", e)
            0
        }
    }

    /**
     * Deletes only the already synchronized lots (their local files and cached images).
     * @return number of rows deleted.
     */
    fun deleteAllDataSynced(): Int {
        val userId = getCurrentUserId()
        return try {
            val lotes = loteDao.getAllLotes(userId).filter { it.synced }
            lotes.forEach { deleteLocalFiles(it.localFilePaths()) }
            loteDao.deleteAllLotesSynced(userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleteAllDataSynced", e)
            0
        }
    }

    /**
     * Inserts a lot from the cloud only if it does not already exist locally (by cloudId).
     * @return true if inserted, false if it already existed.
     */
    fun verifyAndInsertLoteFromCloud(lote: Lote): Boolean {
        return if (!loteDao.doesLoteExist(lote.cloudId)) {
            loteDao.insertLote(lote.copy(synced = true))
            true
        } else false
    }

    private fun Lote.localFilePaths(): List<String> {
        return sourceImages + normalizedImages + uploadImages + overlayImages
    }

    private fun deleteLocalFiles(paths: List<String>): Int {
        return paths.distinct().count { path ->
            val cleanPath = path.replace("file://", "").trim()
            if (cleanPath.isBlank() ||
                cleanPath.startsWith("http://", ignoreCase = true) ||
                cleanPath.startsWith("https://", ignoreCase = true)
            ) {
                return@count false
            }

            runCatching {
                val file = File(cleanPath)
                if (!file.exists()) {
                    false
                } else if (!file.delete()) {
                    Log.w(TAG, "Could not delete local file: $cleanPath")
                    true
                } else {
                    false
                }
            }.getOrElse { e ->
                Log.w(TAG, "Error deleting local file: $cleanPath", e)
                true
            }
        }
    }

    // ── Remote operations (Retrofit) ─────────────────────────────────────────

    /**
     * Uploads a complete lot to the backend, including metadata and images.
     * Runs on [Dispatchers.IO] to avoid blocking the main thread.
     *
     * @param loteRequest DTO with lot metadata (userId, company, vessel, etc.).
     * @param imagePaths absolute local paths of the JPEG images to upload.
     * @return [Response] with [LoteResponse] containing the cloudId and remote URLs.
     */
    suspend fun insertLoteGrapeCloud(
        loteRequest: BatchLoteGrapeRequest,
        imagePaths: List<String>
    ): Response<LoteResponse> = withContext(Dispatchers.IO) {
        if (BuildConfig.DEMO_MODE) {
            Log.d(TAG, "DEMO_MODE: Cloud upload skipped.")
            @Suppress("DEPRECATION")
            return@withContext Response.error(503, okhttp3.ResponseBody.create(null, ""))
        }
        val calPredictsJson = gson.toJson(loteRequest.calPredicts)
        apiService.insertBatchDetection(
            userId = loteRequest.userId.toRequestBody("text/plain".toMediaTypeOrNull()),
            company = loteRequest.company.toRequestBody("text/plain".toMediaTypeOrNull()),
            vessel = loteRequest.vessel.toRequestBody("text/plain".toMediaTypeOrNull()),
            block = loteRequest.block.toRequestBody("text/plain".toMediaTypeOrNull()),
            variety = loteRequest.variety.toRequestBody("text/plain".toMediaTypeOrNull()),
            predictedAt = loteRequest.predictedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull()),
            calPredictsJson = calPredictsJson.toRequestBody("application/json".toMediaTypeOrNull()),
            files = prepareImageParts(imagePaths).toTypedArray()
        )
    }

    /**
     * Retrieves all lots for the user from the backend.
     * @return [Response] with a list of [LoteResponse].
     */
    suspend fun getLoteGrapeCloud(): Response<List<LoteResponse>> = withContext(Dispatchers.IO) {
        if (BuildConfig.DEMO_MODE) {
            Log.d(TAG, "DEMO_MODE: Cloud download skipped.")
            @Suppress("DEPRECATION")
            return@withContext Response.error(503, okhttp3.ResponseBody.create(null, ""))
        }
        apiService.getBatchsDetections()
    }

    /**
     * Deletes a lot from the backend by its cloudId.
     * @param cloudID remote identifier of the lot.
     * @return [Response] with [DeleteBatchGrapeResponse].
     */
    suspend fun deleteLoteGrapeCloud(cloudID: String): Response<DeleteBatchGrapeResponse> = withContext(Dispatchers.IO) {
        if (BuildConfig.DEMO_MODE) {
            Log.d(TAG, "DEMO_MODE: Cloud delete skipped.")
            @Suppress("DEPRECATION")
            return@withContext Response.error(503, okhttp3.ResponseBody.create(null, ""))
        }
        apiService.deleteBatchDetection(cloudID)
    }

    /**
     * Prepares the Multipart parts by validating physical existence.
     * HARDENING: Does not load into RAM, uses asRequestBody for streaming.
     */
    fun prepareImageParts(imagePaths: List<String>): List<MultipartBody.Part> {
        return imagePaths.mapNotNull { path ->
            val cleanPath = path.replace("file://", "")
            val file = File(cleanPath)
            if (file.exists() && file.length() > 0) {
                val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                MultipartBody.Part.createFormData("files", file.name, requestFile)
            } else {
                Log.e(TAG, "Invalid upload file: $cleanPath")
                null
            }
        }
    }

    /**
     * After a successful upload, updates in Room the cloudId, remote image paths,
     * and marks the lot as synchronized.
     *
     * @param localLoteId local lot ID in Room.
     * @param cloudId remote identifier assigned by the backend.
     * @param cloudImages list of remote URLs of the uploaded images.
     */
    suspend fun updateLoteAfterSync(localLoteId: Long, cloudId: String, cloudImages: List<String>) {
        withContext(Dispatchers.IO) {
            loteDao.updateCloudIdImagePathsAndSyncStatus(
                localLoteId, cloudId, gson.toJson(cloudImages), getCurrentUserId()
            )
        }
    }

    /**
     * Downloads an image from the cloud and persists it physically in private storage.
     * @return The local absolute path if successful, null if it failed.
     */
    suspend fun downloadAndPersistImage(url: String, fileName: String): String? = withContext(Dispatchers.IO) {
        if (BuildConfig.DEMO_MODE) {
            Log.d(TAG, "DEMO_MODE: Cloud image download skipped.")
            return@withContext null
        }
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            
            val directory = File(context.filesDir, "lotes_media")
            if (!directory.exists()) directory.mkdirs()
            
            val file = File(directory, fileName)
            
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Persistent download successful: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Persistent download error: ${e.message}")
            null
        }
    }

    /**
     * Updates the local path of a specific image within a lot.
     * Avoids re-downloads by converting a remote URL into a permanent local file.
     */
    suspend fun updateLocalImagePath(loteId: Long, index: Int, newPath: String) {
        withContext(Dispatchers.IO) {
            val lote = loteDao.getLoteById(loteId, getCurrentUserId())
            if (lote != null) {
                val updatedUploadImages = lote.uploadImages.toMutableList()
                
                if (index in updatedUploadImages.indices) {
                    updatedUploadImages[index] = newPath
                } else if (index == updatedUploadImages.size) {
                    updatedUploadImages.add(newPath)
                }

                val updatedLote = lote.copy(uploadImages = updatedUploadImages)
                loteDao.updateLote(updatedLote)
                Log.d(TAG, "Room: Local path updated for lot $loteId at index $index")
            }
        }
    }
}
