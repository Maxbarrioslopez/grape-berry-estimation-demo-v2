package com.gaiaspa.metrics_detection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.gaiaspa.metrics_detection.data.model.toLocalLote
import com.gaiaspa.metrics_detection.data.repository.LoteRepository

/**
 * [CoroutineWorker] que descarga lotes desde la API remota y los persiste localmente.
 *
 * ## Rol en la arquitectura
 * Complemento de [SyncWorker] para la dirección servidor → dispositivo. Obtiene la
 * lista completa de lotes disponibles en el backend mediante [LoteRepository.getLoteGrapeCloud],
 * descarga las imágenes de cada predicción en paralelo con un semáforo de 4 permisos,
 * y las inserta localmente a través de [LoteRepository.verifyAndInsertLoteFromCloud].
 *
 * ## Concurrencia
 * Las imágenes se procesan en chunks de 10 para evitar saturar la conexión.
 * Un [Semaphore](4) limita las descargas simultáneas a 4 hilos de IO, y se intercalan
 * llamadas a [yield] para mantener la equidad entre corrutinas.
 */
class BatchDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BatchDownloadWorker"
    }

    private val loteRepository = LoteRepository.getInstance(context)
    private val downloadSemaphore = Semaphore(4)

    /**
     * Obtiene la lista de lotes del backend, descarga sus imágenes y los persiste.
     *
     * Emite progreso mediante [setProgress] con una clave `"progress"` en el [Data]
     * de salida, permitiendo a la UI mostrar el avance de la descarga.
     *
     * @return [Result.success] con el conteo de lotes descargados,
     *         [Result.failure] si la respuesta del servidor no es exitosa o está vacía.
     */
    override suspend fun doWork(): Result = coroutineScope {
        try {
            val lotesResponse = loteRepository.getLoteGrapeCloud()
            
            if (!lotesResponse.isSuccessful) {
                Log.e(TAG, "Respuesta no exitosa: ${lotesResponse.code()}")
                return@coroutineScope Result.failure(createProgressData("Error en el servidor: ${lotesResponse.code()}"))
            }

            val lotesResponseBody = lotesResponse.body()
            if (lotesResponseBody.isNullOrEmpty()) {
                return@coroutineScope Result.failure(createProgressData("No hay lotes disponibles."))
            }

            val totalLotes = lotesResponseBody.size
            var count = 0

            for ((loteIndex, loteCloud) in lotesResponseBody.withIndex()) {
                val progressMsg = "Descargando lote ${loteIndex + 1} de $totalLotes"
                setProgress(createProgressData(progressMsg))
                
                val imagePaths = loteCloud.predicts.map { it.image.imagePath }
                val downloadedImages = mutableListOf<String>()

                // Procesar imágenes en trozos para evitar saturación
                for (chunk in imagePaths.chunked(10)) {
                    val deferreds = chunk.map { url ->
                        async(Dispatchers.IO) {
                            downloadSemaphore.withPermit { downloadImage(url) }
                        }
                    }
                    downloadedImages.addAll(deferreds.awaitAll().filterNotNull())
                    yield()
                }

                val loteLocal = loteCloud.toLocalLote(downloadedImages)
                if (loteRepository.verifyAndInsertLoteFromCloud(loteLocal)) {
                    count++
                }
                yield()
            }

            Result.success(createProgressData("Descarga completa: $count lotes"))
        } catch (e: Exception) {
            Log.e(TAG, "Error en BatchDownloadWorker", e)
            Result.failure(createProgressData("Error: ${e.localizedMessage}"))
        }
    }

    /**
     * Descarga una imagen desde [imageUrl] al directorio de caché de la aplicación.
     *
     * Los timeouts de conexión y lectura están fijados a 15 s. Si la respuesta no es
     * 200 OK o cualquier etapa falla, se devuelve `null` y la imagen simplemente se omite.
     *
     * @return Ruta absoluta del archivo descargado, o `null` en caso de error.
     */
    private suspend fun downloadImage(imageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val file = File(applicationContext.cacheDir, "img_${System.currentTimeMillis()}_${imageUrl.hashCode()}.png")
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Construye un [Data] con la clave `"progress"` para alimentar [setProgress].
     */
    private fun createProgressData(message: String) = Data.Builder().putString("progress", message).build()
}
