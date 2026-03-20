package com.gaiaspa.metrics_detection.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
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

    override suspend fun doWork(): Result {
        return try {
            val lotesResponse = withContext(Dispatchers.IO) {
                loteRepository.getLoteGrapeCloud()
            }
            if (!lotesResponse.isSuccessful) {
                Log.e(TAG, "Respuesta no exitosa: ${lotesResponse.code()}")
                return Result.failure(
                    Data.Builder().putString("progress", "Error en la respuesta del servidor").build()
                )
            }
            val lotesResponseBody = lotesResponse.body()
            if (lotesResponseBody.isNullOrEmpty()) {
                Log.e(TAG, "No hay datos de lotes disponibles.")
                return Result.failure(
                    Data.Builder().putString("progress", "No hay datos de lotes disponibles.").build()
                )
            }

            var count = 0
            val totalLotes = lotesResponseBody.size

            for ((loteIndex, loteCloud) in lotesResponseBody.withIndex()) {
                setProgressAsync(Data.Builder().putString("progress",
                    "Descargando lote ${loteIndex + 1} de $totalLotes").build())
                Log.d(TAG, "Descargando lote ${loteIndex + 1} de $totalLotes")

                val imagePaths = loteCloud.predicts.map { it.image.imagePath }
                val chunkSize = 10
                val imagePathsChunks = imagePaths.chunked(chunkSize)
                val downloadedImages = mutableListOf<String>()

                for ((chunkIndex, pathsChunk) in imagePathsChunks.withIndex()) {
                    setProgressAsync(Data.Builder().putString("progress",
                        "Descargando lote ${loteIndex + 1} de $totalLotes | Imágenes ${chunkIndex + 1} de ${imagePathsChunks.size}").build())
                    Log.d(TAG, "Descargando lote ${loteIndex + 1} de $totalLotes | Imágenes ${chunkIndex + 1} de ${imagePathsChunks.size}")

                    val chunkResults = kotlinx.coroutines.coroutineScope {
                        pathsChunk.map { imgUrl ->
                            async(Dispatchers.IO) {
                                downloadSemaphore.withPermit {
                                    downloadImageToCache(applicationContext, imgUrl)
                                }
                            }
                        }.awaitAll()
                    }
                    chunkResults.forEach { downloadedPath ->
                        if (downloadedPath != null) {
                            downloadedImages.add(downloadedPath)
                        } else {
                            Log.e(TAG, "Error al descargar una imagen.")
                        }
                    }
                    yield()
                }

                val loteToLocal = loteCloud.toLocalLote(downloadedImages)
                withContext(Dispatchers.IO) {
                    val isInserted = loteRepository.verifyAndInsertLoteFromCloud(loteToLocal)
                    if (isInserted) {
                        count++
                        Log.d(TAG, "Lote ${loteToLocal.cloudId} insertado con éxito.")
                    } else {
                        Log.d(TAG, "Lote ${loteToLocal.cloudId} ya existe o fallo al insertar.")
                    }
                }
                yield()
            }
            setProgressAsync(Data.Builder().putString("progress",
                "Descarga completa: $count lotes").build())
            Log.d(TAG, "Descarga completa. Total lotes insertados: $count")
            Result.success(Data.Builder().putString("progress", "Descarga completa: $count lotes").build())
        } catch (e: Exception) {
            Log.e(TAG, "Error en doWork()", e)
            Result.failure(Data.Builder().putString("progress", "Error: ${e.localizedMessage}").build())
        }
    }

    private suspend fun downloadImageToCache(context: Context, imageUrl: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(imageUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "Error: código de respuesta ${connection.responseCode} para URL $imageUrl")
                    return@withContext null
                }

                connection.inputStream.use { inputStream ->
                    val cacheDir = context.cacheDir
                    val fileName = "image_${System.currentTimeMillis()}_${imageUrl.hashCode()}.png"
                    val imageFile = File(cacheDir, fileName)

                    FileOutputStream(imageFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    Log.d(TAG, "Imagen descargada en ${imageFile.absolutePath}")
                    return@withContext imageFile.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception en downloadImageToCache para URL $imageUrl", e)
                return@withContext null
            }
        }

}
