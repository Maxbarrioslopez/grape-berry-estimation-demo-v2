// LoteRepository.kt
package com.gaiaspa.metrics_detection.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.gaiaspa.metrics_detection.data.local.DatabaseProvider
import com.gaiaspa.metrics_detection.data.local.LoteDao
import com.gaiaspa.metrics_detection.data.model.CalPredict
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
import com.gaiaspa.metrics_detection.data.local.Converters
import kotlinx.coroutines.launch
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

        private  val TAG = "LoteRepository"
        fun getInstance(context: Context): LoteRepository {
            return INSTANCE ?: synchronized(this) {
                val database = DatabaseProvider.getDatabase(context)
                val apiService = ApiClient.create(context) // Pasar tokenProvider
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
    // ========== Métodos ==========

    /**
     * Inserta un lote para el usuario actual.
     *
     * @param lote El lote a insertar.
     */
     fun insertLocalLote(lote: Lote) {
        val userId = getCurrentUserId()
        val loteConUserId = lote.copy(userId = userId)
        loteDao.insertLote(loteConUserId)
    }

    /**
     * Elimina un lote por su ID para el usuario actual.
     *
     * @param loteId El ID del lote.
     */
    fun deleteLocalLote(loteId: Long) {
        val userId = getCurrentUserId()

        try {
            val lote = loteDao.getLoteById(loteId,userId)
            if (lote != null) {
                lote.images.forEach { imagePath ->
                    val file = File(Uri.parse(imagePath).path ?: "")
                    if (file.exists()) {
                        file.delete()
                    }
                }
                loteDao.deleteLote(loteId, userId)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }


    }

    fun deleteAllData() {
        val userId = getCurrentUserId()
        try {
            loteDao.deleteAllLotes(userId= userId)
            Log.d(TAG, "deleteAllData: Lotes eliminados con exito")
        } catch (e:Exception) {
            Log.d(TAG, "deleteAllData: Lotes eliminados con exito")
            e.printStackTrace()
        }
    }
    fun deleteAllDataSynced() {
        val userId = getCurrentUserId()
        try {
            loteDao.deleteAllLotesSynced(userId= userId)
            Log.d(TAG, "deleteAllData: Lotes eliminados con exito")
        } catch (e:Exception) {
            Log.d(TAG, "deleteAllData: Lotes eliminados con exito")
            e.printStackTrace()
        }
    }

    /**
     * Marca un lote para borrar si no hay internet.
     *
     * @param lote El lote a insertar.
     */
    fun markLoteAsNotSyncedAndToDelete(loteId: Long){

        val userId = getCurrentUserId()
        Log.d(TAG, "markLoteAsNotSyncedAndToDelete: Lote ${loteId} marcado para borrar con exito")
        loteDao.markLoteAsNotSyncedAndToDelete(loteId,userId)

    }

    /**
     * Inserta un lote para el usuario actual.
     *
     * @param lote El lote a insertar.}
     * @return Flag, true si lo inserto con exito, false si fallo o ya existe
     */
    fun verifyAndInsertLoteFromCloud(lote: Lote): Boolean {
        val exist = loteDao.doesLoteExist(cloudId = lote.cloudId)
        return if (!exist) {
            loteDao.insertLote(lote)
            Log.d(TAG, "insertLoteAndVerify: Lote ${lote.cloudId} insertado con éxito")
            true // Indica que el lote fue insertado
        } else {
            Log.d(TAG, "insertLoteAndVerify: Lote ${lote.cloudId} ya existe")
            false // Indica que el lote ya existía
        }
    }

    /**
     * Obtiene todos los lotes para el usuario actual.
     *
     * @return Lista de lotes del usuario.
     */
     fun getAllLotes(): List<Lote> {
        val userId = getCurrentUserId()
        return loteDao.getAllLotes(userId).sortedByDescending { it.predictedAt }
        }


    /**
     * Obtiene el conteo de lotes para el usuario actual.
     *
     * @return Número de lotes del usuario.
     */
     fun getLoteCount(): Int {
        val userId = getCurrentUserId()
        return loteDao.getLoteCount(userId)
    }

    /**
     * Obtiene lotes dentro de un rango específico para el usuario actual.
     *
     * @param startIndex El índice inicial.
     * @param pageSize El tamaño de la página.
     * @return Lista de lotes dentro del rango.
     */
    suspend fun getLotesByRange(startIndex: Int, pageSize: Int): List<Lote> {
        val userId = getCurrentUserId()
        return loteDao.getLotesByRange(userId, startIndex, pageSize)
    }

    /**
     * CLOUD STRATEGY
     */

    suspend fun insertLoteGrapeCloud(
        loteRequest: BatchLoteGrapeRequest,
        imagePaths: List<String>
    ): Response<LoteResponse> {
        return withContext(Dispatchers.IO) {
            if (loteRequest.variety.isBlank()) {
                Log.e(TAG, "insertLoteGrapeCloud: variety is required and was blank for userId=${loteRequest.userId}")
                throw IllegalArgumentException("Batch upload requires non-blank variety")
            }

            val gson = Gson()
            // Convertir el arreglo calPredicts a JSON
            val calPredictsJson = gson.toJson(loteRequest.calPredicts)

            // Preparar los RequestBody para los campos de texto
            // Para userId, company, vessel, block y variety se usa "text/plain"
            val userIdBody = loteRequest.userId.toRequestBody("text/plain".toMediaTypeOrNull())
            val companyBody = loteRequest.company.toRequestBody("text/plain".toMediaTypeOrNull())
            val vesselBody = loteRequest.vessel.toRequestBody("text/plain".toMediaTypeOrNull())
            val blockBody = loteRequest.block.toRequestBody("text/plain".toMediaTypeOrNull())
            val varietyBody = loteRequest.variety.toRequestBody("text/plain".toMediaTypeOrNull())
            val predictedAtBody = loteRequest.predictedAt.toString()
                .toRequestBody("text/plain".toMediaTypeOrNull())

            // Para el JSON se envía con media type "application/json"
            val calPredictsBody = calPredictsJson.toRequestBody("application/json".toMediaTypeOrNull())

            // Preparar las imágenes como MultipartBody.Part
            val imageParts = prepareImageParts(imagePaths)

            apiService.insertBatchDetection(
                userId = userIdBody,
                company = companyBody,
                vessel = vesselBody,
                block = blockBody,
                variety = varietyBody,
                calPredictsJson = calPredictsBody,
                files = imageParts,
                predictedAt = predictedAtBody

            )
        }
    }

    suspend fun getLoteGrapeCloud( ): Response<List<LoteResponse>> {
        return withContext(Dispatchers.IO) {
            apiService.getBatchsDetections()

        } }

    suspend fun deleteLoteGrapeCloud(cloudID: String): Response<DeleteBatchGrapeResponse> {
        return withContext(Dispatchers.IO) {
            // Primero, eliminar del servidor
            val response = apiService.deleteBatchDetection(cloudID)
            response
        }
    }

    fun prepareImageParts(imagePaths: List<String>): List<MultipartBody.Part> {
        val parts = mutableListOf<MultipartBody.Part>()
        imagePaths.forEach { path ->
            // Crea un File a partir de la ruta proporcionada
            val file = File(path)
            // Crea un RequestBody para el archivo, asumiendo que las imágenes son png (puedes ajustar el mediaType según corresponda)
            val requestFile = file.asRequestBody("image/png".toMediaTypeOrNull())
            // Crea el MultipartBody.Part, usando "files" como nombre
            val bodyPart = MultipartBody.Part.createFormData("files", file.name, requestFile)
            parts.add(bodyPart)
        }
        return parts
    }

    /**
     * Obtiene lotes que aún no han sido sincronizados para el usuario actual.
     *
     * @return Lista de lotes no sincronizados.
     */
     fun getNotSynced(): List<Lote> {
        val userId = getCurrentUserId()
        return loteDao.getNotSyncedLotes(userId)
    }

    /**
     * Actualiza el lote local después de una sincronización exitosa.
     *
     * @param localLoteId ID local del lote.
     * @param cloudId ID del lote en la nube.
     * @param newImagePaths Nuevas rutas de imágenes proporcionadas por la nube.
     */
    suspend fun updateLoteAfterSync(localLoteId: Long, cloudId: String, cloudImages: List<String>) {
        withContext(Dispatchers.IO) {
            loteDao.updateCloudIdImagePathsAndSyncStatus(localLoteId, cloudId, gson.toJson(cloudImages), getCurrentUserId())
        }
    }




    /**
     * Obtiene un lote por su ID para el usuario actual.
     *
     * @param loteId El ID del lote.
     * @return El lote correspondiente o null si no existe.
     */
     fun getLoteById(loteId: Long): Lote? {
        val userId = getCurrentUserId()
        return try {
            loteDao.getLoteById(loteId, userId)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Obtiene el userId actual desde el TokenProvider.
     *
     * @return El userId del usuario actual.
     */
    fun getCurrentUserId(): String {
        return TokenProvider.getUserId()
    }
}
