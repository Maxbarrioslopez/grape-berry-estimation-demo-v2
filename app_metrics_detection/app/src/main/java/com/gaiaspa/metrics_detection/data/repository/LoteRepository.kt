/**
 * LoteRepository.kt
 *
 * Propósito: Gestionar el ciclo de vida de los datos de los Lotes (Batch) de uvas.
 * Responsabilidad: Actuar como mediador entre la base de datos local (Room) y el servidor (Retrofit).
 * Relación: Utilizado por el SyncWorker para la sincronización offline y por los ViewModels para consulta.
 *
 * Flujo: Los lotes se guardan localmente primero y luego SyncWorker llama a insertLoteGrapeCloud para persistirlos.
 */
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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import retrofit2.Response

class LoteRepository private constructor(
    private val loteDao: LoteDao,
    private val apiService: ApiService,
    private val context: Context,
) {
    private val gson = Gson()
    
    companion object {
        @Volatile
        private var INSTANCE: LoteRepository? = null
        private const val TAG = "LoteRepository"

        /** Patrón Singleton para asegurar una única instancia del repositorio en toda la app. */
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

    /** Guarda un lote en Room para su posterior procesamiento o visualización offline. */
    fun insertLocalLote(lote: Lote) {
        val userId = getCurrentUserId()
        loteDao.insertLote(lote.copy(userId = userId, synced = false, syncError = null))
    }

    /** 
     * Borra un lote local y sus archivos de imagen asociados del almacenamiento del dispositivo.
     * Solo borra archivos con prefijo file:// para evitar colisiones con otros procesos del sistema.
     */
    fun deleteLocalLote(loteId: Long) {
        val userId = getCurrentUserId()
        try {
            val lote = loteDao.getLoteById(loteId, userId)
            lote?.normalizedImages?.forEach { path ->
                val file = File(path.replace("file://", ""))
                if (file.exists()) file.delete()
            }
            lote?.sourceImages?.forEach { path ->
                val file = File(path.replace("file://", ""))
                if (file.exists()) file.delete()
            }
            loteDao.deleteLote(loteId, userId)
        } catch (e: Exception) {
            Log.e(TAG, "Error deleteLocalLote", e)
        }
    }

    /** Actualiza el mensaje de error de sincronización para informar al usuario en la UI de Historial. */
    fun updateSyncError(loteId: Long, error: String?) {
        loteDao.updateSyncError(loteId, getCurrentUserId(), error)
    }

    /** Limpia toda la base de datos de lotes del usuario actual. */
    fun deleteAllData() {
        try {
            loteDao.deleteAllLotes(getCurrentUserId())
        } catch (e: Exception) {
            Log.e(TAG, "Error deleteAllData", e)
        }
    }

    /** Borra solo los lotes que ya fueron confirmados por el servidor. */
    fun deleteAllDataSynced() {
        try {
            loteDao.deleteAllLotesSynced(getCurrentUserId())
        } catch (e: Exception) {
            Log.e(TAG, "Error deleteAllDataSynced", e)
        }
    }

    /** Marca un lote como pendiente de eliminación sincronizada (soft-delete local). */
    fun markLoteAsNotSyncedAndToDelete(loteId: Long) {
        loteDao.markLoteAsNotSyncedAndToDelete(loteId, getCurrentUserId())
    }

    /** Inserta un lote descargado desde la nube, evitando duplicados por cloudId. */
    fun verifyAndInsertLoteFromCloud(lote: Lote): Boolean {
        return if (!loteDao.doesLoteExist(lote.cloudId)) {
            loteDao.insertLote(lote.copy(synced = true))
            true
        } else false
    }

    /** Obtiene el historial completo ordenado por fecha de creación. */
    fun getAllLotes(): List<Lote> = loteDao.getAllLotes(getCurrentUserId()).sortedByDescending { it.predictedAt }

    /** Filtra los lotes que aún no residen en el servidor. */
    fun getNotSynced(): List<Lote> = loteDao.getNotSyncedLotes(getCurrentUserId())

    fun getLoteCount(): Int = loteDao.getLoteCount(getCurrentUserId())

    /**
     * Sincroniza un lote con el servidor mediante una petición Multipart.
     *
     * Antes: El cliente enviaba 'company' y eso definía la pertenencia del lote.
     * Ahora: El tenant real lo decide el backend mediante el token del usuario (req.user.companyId).
     * Motivo: Implementación multi-tenant centralizada para evitar manipulación desde el cliente.
     * Compatibilidad: El campo 'company' se mantiene con valor por contrato actual obligatorio en el backend.
     * Riesgo: El valor enviado aquí no debe interpretarse como fuente de autorización tenant.
     */
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
            files = prepareImageParts(imagePaths)
        )
    }

    /** Obtiene los lotes del servidor para reconstruir el historial. */
    suspend fun getLoteGrapeCloud(): Response<List<LoteResponse>> = withContext(Dispatchers.IO) {
        apiService.getBatchsDetections()
    }

    /** Elimina un lote en la nube. */
    suspend fun deleteLoteGrapeCloud(cloudID: String): Response<DeleteBatchGrapeResponse> = withContext(Dispatchers.IO) {
        apiService.deleteBatchDetection(cloudID)
    }

    /** Transforma rutas de archivos en partes MultipartBody para subida de imágenes binarias. */
    fun prepareImageParts(imagePaths: List<String>): List<MultipartBody.Part> {
        return imagePaths.filter { !it.startsWith("http") }.mapNotNull { path ->
            val file = File(path.replace("file://", ""))
            if (file.exists()) {
                val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                MultipartBody.Part.createFormData("files", file.name, requestFile)
            } else null
        }
    }

    /** Actualiza el lote local con los IDs y rutas finales tras una subida exitosa. */
    suspend fun updateLoteAfterSync(localLoteId: Long, cloudId: String, cloudImages: List<String>) {
        withContext(Dispatchers.IO) {
            loteDao.updateCloudIdImagePathsAndSyncStatus(
                localLoteId, cloudId, gson.toJson(cloudImages), getCurrentUserId()
            )
        }
    }

    fun getLoteById(loteId: Long): Lote? = loteDao.getLoteById(loteId, getCurrentUserId())

    fun getCurrentUserId(): String = TokenProvider.getUserId()
}
