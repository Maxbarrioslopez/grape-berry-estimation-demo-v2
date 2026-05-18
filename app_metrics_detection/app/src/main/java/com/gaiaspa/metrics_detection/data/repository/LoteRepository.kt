/**
 * Repositorio singleton que abstrae las operaciones de lectura/escritura sobre
 * lotes de detección de uvas, combinando almacenamiento local (Room) y remoto
 * (Retrofit hacia el backend).
 *
 * Arquitectura:
 * - Capa de datos (data/repository): orquesta LoteDao (local) y ApiService (remoto).
 * - Patrón singleton con double-checked locking para acceso thread-safe desde
 *   múltiples componentes (Activities, Workers, ViewModels).
 * - Todas las operaciones remotas son suspend y se ejecutan en Dispatchers.IO.
 *
 * Ámbito de usuario: cada operación local filtra por el userId del usuario
 * autenticado actual (obtenido de TokenProvider), garantizando aislamiento
 * de datos entre cuentas.
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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import retrofit2.Response

/**
 * Resultado de una operación de borrado local.
 * @property deleted true si se eliminó al menos una fila en Room.
 * @property missingLote true si el lote no existía en la base de datos local.
 * @property failedFileCount número de archivos físicos que no pudieron eliminarse del sistema de archivos.
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
         * Obtiene la instancia única del repositorio, creándola si es necesario.
         * @param context usado para inicializar la base de datos Room y el cliente API.
         * @return la instancia singleton de LoteRepository.
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
     * @return el ID del usuario autenticado actual, o "undefined_user" si no hay sesión.
     */
    fun getCurrentUserId(): String {
        TokenProvider.init(context)
        return TokenProvider.getUserId().ifBlank { "undefined_user" }
    }

    // ── Operaciones locales (Room) ───────────────────────────────────────────

    /**
     * Inserta un lote nuevo en Room, asociándolo al usuario actual y marcándolo
     * como no sincronizado.
     */
    fun insertLocalLote(lote: Lote) {
        val uid = getCurrentUserId()
        loteDao.insertLote(lote.copy(userId = uid, synced = false))
    }

    /** @return todos los lotes del usuario actual, ordenados por fecha de predicción descendente. */
    fun getAllLotes(): List<Lote> = loteDao.getAllLotes(getCurrentUserId()).sortedByDescending { it.predictedAt }
    /** @return cantidad total de lotes del usuario actual en Room. */
    fun getLoteCount(): Int = loteDao.getLoteCount(getCurrentUserId())
    /** @return lotes pendientes de sincronización (synced = false). */
    fun getNotSynced(): List<Lote> = loteDao.getNotSyncedLotes(getCurrentUserId())
    /** @return el lote con el ID local dado, o null si no existe o pertenece a otro usuario. */
    fun getLoteById(loteId: Long): Lote? = loteDao.getLoteById(loteId, getCurrentUserId())

    /**
     * Borra un lote y sus imágenes físicas asociadas sin tocar backend ni cloudId remoto.
     */
    fun deleteLocalLote(loteId: Long): LocalDeleteResult {
        val userId = getCurrentUserId()
        return try {
            val lote = loteDao.getLoteById(loteId, userId)
            if (lote == null) {
                Log.w(TAG, "deleteLocalLote: lote $loteId no encontrado para usuario $userId")
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
     * Marca un lote como no sincronizado y pendiente de eliminación remota.
     * Útil cuando una sincronización no se completó y se necesita reintentar.
     */
    fun markLoteAsNotSyncedAndToDelete(loteId: Long) = loteDao.markLoteAsNotSyncedAndToDelete(loteId, getCurrentUserId())
    /** Registra un mensaje de error de sincronización para el lote indicado. */
    fun updateSyncError(loteId: Long, error: String?) = loteDao.updateSyncError(loteId, getCurrentUserId(), error)
    /**
     * Elimina todos los lotes y sus archivos físicos asociados del usuario actual.
     * @return número de filas eliminadas de Room.
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
     * Elimina solo los lotes ya sincronizados (sus archivos locales e imágenes en caché).
     * @return número de filas eliminadas.
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
     * Inserta un lote desde la nube solo si no existe ya localmente (por cloudId).
     * @return true si se insertó, false si ya existía.
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
                    Log.w(TAG, "No se pudo borrar archivo local: $cleanPath")
                    true
                } else {
                    false
                }
            }.getOrElse { e ->
                Log.w(TAG, "Error borrando archivo local: $cleanPath", e)
                true
            }
        }
    }

    // ── Operaciones remotas (Retrofit) ──────────────────────────────────────

    /**
     * Sube un lote completo al backend, incluyendo metadatos e imágenes.
     * Se ejecuta en [Dispatchers.IO] para no bloquear el hilo principal.
     *
     * @param loteRequest DTO con los metadatos del lote (userId, company, vessel, etc.).
     * @param imagePaths rutas locales absolutas de las imágenes JPEG a subir.
     * @return [Response] con [LoteResponse] conteniendo el cloudId y URLs remotas.
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
            files = prepareImageParts(imagePaths).toTypedArray()
        )
    }

    /**
     * Obtiene todos los lotes del usuario desde el backend.
     * @return [Response] con lista de [LoteResponse].
     */
    suspend fun getLoteGrapeCloud(): Response<List<LoteResponse>> = withContext(Dispatchers.IO) {
        apiService.getBatchsDetections()
    }

    /**
     * Elimina un lote del backend por su cloudId.
     * @param cloudID identificador remoto del lote.
     * @return [Response] con [DeleteBatchGrapeResponse].
     */
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

    /**
     * Tras una subida exitosa, actualiza en Room el cloudId, las rutas de imágenes
     * remotas y marca el lote como sincronizado.
     *
     * @param localLoteId ID local del lote en Room.
     * @param cloudId identificador remoto asignado por el backend.
     * @param cloudImages lista de URLs remotas de las imágenes subidas.
     */
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
