package com.gaiaspa.metrics_detection.ui.profile

import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import com.gaiaspa.metrics_detection.data.model.Profile
import com.gaiaspa.metrics_detection.data.model.toLocalLote
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.data.repository.ProfileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ProfileViewModel(
    private val repository: ProfileRepository,
    private val loteRepository: LoteRepository
) : ViewModel() {

    private val _profile = MutableLiveData<Profile?>()
    val profile: LiveData<Profile?> get() = _profile

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> get() = _error

    private val _isDownloading = MutableLiveData<Boolean>()
    val isDownloading: LiveData<Boolean> get() = _isDownloading

    private val _errorDownload = MutableLiveData<String?>()
    val errorDownload: LiveData<String?> get() = _errorDownload

    private val _downloadedCount = MutableLiveData<Int>()
    val downloadedCount: LiveData<Int> get() = _downloadedCount

    private val _progressMessage = MutableLiveData<String>("")
    val progressMessage: LiveData<String> get() = _progressMessage

    companion object {
        private const val TAG = "ProfileViewModel"
    }

    // Limitar la concurrencia en la descarga de imágenes (4 concurrentes)
    private val downloadSemaphore = Semaphore(4)

    init {
        loadProfile()
    }

    /**
     * Carga el perfil del usuario desde el repositorio.
     */
    fun loadProfile() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val fetchedProfile = withContext(Dispatchers.IO) { repository.getProfile() }
                _profile.value = fetchedProfile
                if (fetchedProfile == null) {
                    _error.value = "No hay datos de perfil disponibles."
                }
            } catch (e: Exception) {
                _error.value = "Error al cargar el perfil: ${e.localizedMessage}"
                Log.e(TAG, "Error en loadProfile", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Reinicia el contador de lotes descargados.
     */
    fun resetDownloadCount() {
        _downloadedCount.value = 0
    }

    /**
     * Reinicia el error de descarga.
     */
    fun resetErrorDownload() {
        _errorDownload.value = null
    }

    /**
     * Descarga la lista de lotes desde la nube, troceando las imagenes para evitar ANR.
     */
    fun downloadBatchs(context: Context) {
        // Evitar llamadas múltiples simultáneas
        if (_isDownloading.value == true) return

        _isDownloading.value = true
        viewModelScope.launch {
            try {
                val lotesResponse = withContext(Dispatchers.IO) {
                    loteRepository.getLoteGrapeCloud()
                }
                if (lotesResponse.isSuccessful) {
                    val lotesResponseBody = lotesResponse.body()
                    if (lotesResponseBody.isNullOrEmpty()) {
                        _errorDownload.value = "No hay datos de lotes disponibles."
                        Log.e(TAG, "No se encontraron lotes para descargar.")
                    } else {
                        var count = 0
                        // Total de lotes a descargar
                        val totalLotes = lotesResponseBody.size

                        // Recorremos los lotes que llegan desde la nube, con su índice
                        for ((loteIndex, loteCloud) in lotesResponseBody.withIndex()) {

                            // Mensaje: “Descargando lote X de Y”
                            _progressMessage.postValue(
                                "Descargando lote ${loteIndex + 1} de $totalLotes"
                            )
                            Log.d(TAG, "Descargando lote ${loteIndex + 1} de $totalLotes")

                            val imagePaths = loteCloud.predicts.map { it.image.imagePath }

                            // Descarga en "trozos" de 10 imágenes (ajustable según necesidades)
                            val chunkSize = 10
                            val imagePathsChunks = imagePaths.chunked(chunkSize)
                            // Lista donde iremos acumulando las rutas descargadas de este lote
                            val downloadedImages = mutableListOf<String>()

                            // Recorremos los trozos
                            for ((chunkIndex, pathsChunk) in imagePathsChunks.withIndex()) {
                                // Mensaje: “Descargando lote X/Y | Imágenes i/n”
                                _progressMessage.postValue(
                                    "Descargando lote ${loteIndex + 1}/$totalLotes | " +
                                            "Imágenes ${chunkIndex + 1}/${imagePathsChunks.size}"
                                )
                                Log.d(TAG, "Descargando lote ${loteIndex + 1}/$totalLotes | Imágenes ${chunkIndex + 1}/${imagePathsChunks.size}")

                                val chunkResults = pathsChunk.map { imgUrl ->
                                    async(Dispatchers.IO) {
                                        downloadSemaphore.withPermit {
                                            downloadImageToCache(context, imgUrl)
                                        }
                                    }
                                }.awaitAll()

                                chunkResults.forEach { downloadedPath ->
                                    if (downloadedPath != null) {
                                        downloadedImages.add(downloadedPath)
                                    } else {
                                        Log.e(TAG, "Error al descargar la imagen: $downloadedPath")
                                    }
                                }

                                // Darle “respiro” a la UI
                                yield()
                                // Opcional: si quieres un pequeño delay
                                // delay(200)
                            }

                            val loteToLocal = loteCloud.toLocalLote(downloadedImages)
                            withContext(Dispatchers.IO) {
                                val isInserted = loteRepository.verifyAndInsertLoteFromCloud(loteToLocal)
                                if (isInserted) {
                                    count++
                                    Log.d(TAG, "Lote ${loteToLocal.cloudId} insertado con éxito.")
                                } else {
                                    Log.e(TAG, "Lote ${loteToLocal.cloudId} ya existe o fallo al insertar.")
                                }
                            }

                            // Pausa leve después de cada lote
                            yield()
                        }
                        _downloadedCount.value = count
                        Log.d(TAG, "Descarga completa. Total lotes insertados: $count")
                    }
                } else {
                    _errorDownload.value = "Error en la respuesta del servidor: ${lotesResponse.code()}"
                    Log.e(TAG, "Error en downloadBatchs: Respuesta no exitosa: ${lotesResponse.code()}")
                }
            } catch (e: Exception) {
                _errorDownload.value = "Error al descargar datos: ${e.localizedMessage}"
                Log.e(TAG, "Error en downloadBatchs", e)
            } finally {
                withContext(Dispatchers.Main) {
                    _isDownloading.value = false
                }
                Log.d(TAG, "Download finalizado: isDownloading = false")
            }
        }
    }

    /**
     * Descarga la imagen y la guarda en caché sin decodificarla a Bitmap
     * (para evitar alto consumo de memoria).
     */
    suspend fun downloadImageToCache(context: Context, imageUrl: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(imageUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("downloadImageToCache", "Error: código de respuesta ${connection.responseCode} para URL $imageUrl")
                    return@withContext null
                }

                connection.inputStream.use { inputStream ->
                    val cacheDir = context.cacheDir
                    val fileName = "image_${System.currentTimeMillis()}_${imageUrl.hashCode()}.png"
                    val imageFile = File(cacheDir, fileName)

                    // Copiamos directamente el inputStream al archivo
                    FileOutputStream(imageFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    Log.d("downloadImageToCache", "Imagen descargada y guardada en ${imageFile.absolutePath}")
                    return@withContext imageFile.absolutePath
                }
            } catch (e: Exception) {
                Log.e("downloadImageToCache", "Exception al descargar la imagen: $imageUrl", e)
                return@withContext null
            }
        }

    /**
     * Elimina todos los datos locales de los lotes.
     */
    fun clearLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            loteRepository.deleteAllData()
        }
    }

    /**
     * Elimina todos los datos locales de los lotes sincronizados.
     */
    fun clearOnlyDataSynced() {
        viewModelScope.launch(Dispatchers.IO) {
            loteRepository.deleteAllDataSynced()
        }
    }

    /**
     * Realiza el logout del usuario.
     */
    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.logout()
        }
    }
}

/**
 * Factory para crear instancias de ProfileViewModel con los repositorios necesarios.
 */
class ProfileViewModelFactory(
    private val repository: ProfileRepository,
    private val loteRepository: LoteRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(repository, loteRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
