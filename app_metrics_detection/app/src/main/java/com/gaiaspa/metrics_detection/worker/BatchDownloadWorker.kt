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

class BatchDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BatchDownloadWorker"
    }

    private val loteRepository = LoteRepository.getInstance(context)
    private val downloadSemaphore = Semaphore(4)

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

    private fun createProgressData(message: String) = Data.Builder().putString("progress", message).build()
}
