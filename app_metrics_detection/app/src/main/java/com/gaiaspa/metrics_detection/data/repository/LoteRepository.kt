package com.gaiaspa.metrics_detection.data.repository

import android.content.Context
import android.util.Log
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

class LoteRepository private constructor(
    private val loteDao: LoteDao,
    private val apiService: ApiService,
    private val context: Context,
) {
    private val gson = Gson()
    
    companion object {
        @Volatile private var INSTANCE: LoteRepository? = null
        private const val TAG = "LoteRepository"

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

    fun getCurrentUserId(): String {
        TokenProvider.init(context)
        return TokenProvider.getUserId().ifBlank { "undefined_user" }
    }

    // --- LOCAL ROOM OPS ---
    fun insertLocalLote(lote: Lote) {
        val uid = getCurrentUserId()
        loteDao.insertLote(lote.copy(userId = uid, synced = false))
    }

    fun getAllLotes(): List<Lote> = loteDao.getAllLotes(getCurrentUserId()).sortedByDescending { it.predictedAt }
    fun getLoteCount(): Int = loteDao.getLoteCount(getCurrentUserId())
    fun getNotSynced(): List<Lote> = loteDao.getNotSyncedLotes(getCurrentUserId())
    fun getLoteById(loteId: Long): Lote? = loteDao.getLoteById(loteId, getCurrentUserId())

    /**
     * Borra un lote y sus imágenes físicas asociadas (Originales, Overlays y Uploads).
     */
    fun deleteLocalLote(loteId: Long) {
        val userId = getCurrentUserId()
        try {
            val lote = loteDao.getLoteById(loteId, userId)
            val allPaths = mutableListOf<String>()
            lote?.normalizedImages?.let { allPaths.addAll(it) }
            lote?.uploadImages?.let { allPaths.addAll(it) }
            lote?.overlayImages?.let { allPaths.addAll(it) }

            allPaths.forEach { path ->
                val file = File(path.replace("file://", ""))
                if (file.exists()) file.delete()
            }
            loteDao.deleteLote(loteId, userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleteLocalLote", e)
        }
    }

    fun markLoteAsNotSyncedAndToDelete(loteId: Long) = loteDao.markLoteAsNotSyncedAndToDelete(loteId, getCurrentUserId())
    fun updateSyncError(loteId: Long, error: String?) = loteDao.updateSyncError(loteId, getCurrentUserId(), error)
    fun deleteAllData() = loteDao.deleteAllLotes(getCurrentUserId())
    fun deleteAllDataSynced() = loteDao.deleteAllLotesSynced(getCurrentUserId())

    fun verifyAndInsertLoteFromCloud(lote: Lote): Boolean {
        return if (!loteDao.doesLoteExist(lote.cloudId)) {
            loteDao.insertLote(lote.copy(synced = true))
            true
        } else false
    }

    // --- REMOTE RETROFIT OPS ---
    suspend fun insertLoteGrapeCloud(
        loteRequest: BatchLoteGrapeRequest,
        imagePaths: List<String>
    ): Response<LoteResponse> = withContext(Dispatchers.IO) {
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

    suspend fun getLoteGrapeCloud(): Response<List<LoteResponse>> = withContext(Dispatchers.IO) {
        apiService.getBatchsDetections()
    }

    suspend fun deleteLoteGrapeCloud(cloudID: String): Response<DeleteBatchGrapeResponse> = withContext(Dispatchers.IO) {
        apiService.deleteBatchDetection(cloudID)
    }

    /**
     * Prepara las partes Multipart validando existencia física.
     * ✅ HARDENING: No carga en RAM, usa asRequestBody para streaming.
     */
    fun prepareImageParts(imagePaths: List<String>): List<MultipartBody.Part> {
        return imagePaths.mapNotNull { path ->
            val cleanPath = path.replace("file://", "")
            val file = File(cleanPath)
            if (file.exists() && file.length() > 0) {
                val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                MultipartBody.Part.createFormData("files", file.name, requestFile)
            } else {
                Log.e(TAG, "Archivo de subida inválido: $cleanPath")
                null
            }
        }
    }

    suspend fun updateLoteAfterSync(localLoteId: Long, cloudId: String, cloudImages: List<String>) {
        withContext(Dispatchers.IO) {
            loteDao.updateCloudIdImagePathsAndSyncStatus(
                localLoteId, cloudId, gson.toJson(cloudImages), getCurrentUserId()
            )
        }
    }

    /**
     * Descarga una imagen desde la nube y la guarda físicamente en el almacenamiento privado.
     * @return La ruta absoluta local si tuvo éxito, null si falló.
     */
    suspend fun downloadAndPersistImage(url: String, fileName: String): String? = withContext(Dispatchers.IO) {
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
            Log.d(TAG, "Descarga persistente exitosa: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error en descarga persistente: ${e.message}")
            null
        }
    }

    /**
     * Actualiza la ruta local de una imagen específica dentro de un lote.
     * Evita redescargas al transformar una URL remota en un archivo local permanente.
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
                Log.d(TAG, "Room: Ruta local actualizada para lote $loteId en índice $index")
            }
        }
    }
}
