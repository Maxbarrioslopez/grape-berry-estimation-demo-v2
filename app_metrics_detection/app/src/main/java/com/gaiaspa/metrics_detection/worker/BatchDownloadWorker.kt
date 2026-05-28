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
import com.gaiaspa.metrics_detection.BuildConfig
import com.gaiaspa.metrics_detection.data.model.toLocalLote
import com.gaiaspa.metrics_detection.data.repository.LoteRepository

/**
 * [CoroutineWorker] that downloads lots from the remote API and persists them locally.
 *
 * ## Role in the architecture
 * Complement to [SyncWorker] for the server-to-device direction. Retrieves the
 * complete list of lots available on the backend via [LoteRepository.getLoteGrapeCloud],
 * downloads the images for each prediction in parallel with a semaphore of 4 permits,
 * and inserts them locally via [LoteRepository.verifyAndInsertLoteFromCloud].
 *
 * ## Concurrency
 * Images are processed in chunks of 10 to avoid saturating the connection.
 * A [Semaphore](4) limits simultaneous downloads to 4 IO threads, and calls
 * to [yield] are interspersed to maintain fairness between coroutines.
 */
class BatchDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "BatchDownloadWorker"
    }

    private val loteRepository by lazy { LoteRepository.getInstance(applicationContext) }
    private val downloadSemaphore = Semaphore(4)

    /**
     * Retrieves the lot list from the backend, downloads their images, and persists them.
     *
     * Emits progress via [setProgress] with a `"progress"` key in the output [Data],
     * allowing the UI to display download progress.
     *
     * @return [Result.success] with the count of downloaded lots,
     *         [Result.failure] if the server response is not successful or is empty.
     */
    override suspend fun doWork(): Result = coroutineScope {
        if (BuildConfig.DEMO_MODE) {
            Log.d(TAG, "DEMO_MODE: batch download skipped, cloud operations disabled.")
            return@coroutineScope Result.success(createProgressData("Cloud download disabled in demo mode."))
        }

        try {
            val lotesResponse = loteRepository.getLoteGrapeCloud()
            
            if (!lotesResponse.isSuccessful) {
                Log.e(TAG, "Unsuccessful response: ${lotesResponse.code()}")
                return@coroutineScope Result.failure(createProgressData("Server error: ${lotesResponse.code()}"))
            }

            val lotesResponseBody = lotesResponse.body()
            if (lotesResponseBody.isNullOrEmpty()) {
                return@coroutineScope Result.failure(createProgressData("No lots available."))
            }

            val totalLotes = lotesResponseBody.size
            var count = 0

            for ((loteIndex, loteCloud) in lotesResponseBody.withIndex()) {
                val progressMsg = "Downloading lot ${loteIndex + 1} of $totalLotes"
                setProgress(createProgressData(progressMsg))
                
                val imagePaths = loteCloud.predicts.map { it.image.imagePath }
                val downloadedImages = mutableListOf<String>()

                // Process images in chunks to avoid saturation
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

            Result.success(createProgressData("Download complete: $count lots"))
        } catch (e: Exception) {
            Log.e(TAG, "Error in BatchDownloadWorker", e)
            Result.failure(createProgressData("Error: ${e.localizedMessage}"))
        }
    }

    /**
     * Downloads an image from [imageUrl] to the application's cache directory.
     *
     * Connection and read timeouts are fixed at 15 s. If the response is not
     * 200 OK or any stage fails, `null` is returned and the image is simply skipped.
     *
     * @return Absolute path of the downloaded file, or `null` on error.
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
     * Builds a [Data] with the `"progress"` key for [setProgress].
     */
    private fun createProgressData(message: String) = Data.Builder().putString("progress", message).build()
}
