package com.gaiaspa.metrics_detection.ui.profile

import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import com.gaiaspa.metrics_detection.R
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
    private val loteRepository: LoteRepository,
    private val context: Context
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

    private val downloadSemaphore = Semaphore(4)

    init {
        loadProfile()
    }

    fun loadProfile() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val fetchedProfile = withContext(Dispatchers.IO) { repository.getProfile() }
                _profile.value = fetchedProfile
                if (fetchedProfile == null) {
                    _error.value = context.getString(R.string.no_profile_data)
                }
            } catch (e: Exception) {
                _error.value = context.getString(R.string.error_loading_profile)
                Log.e(TAG, "Error en loadProfile", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetDownloadCount() {
        _downloadedCount.value = 0
    }

    fun resetErrorDownload() {
        _errorDownload.value = null
    }

    fun downloadBatchs(context: Context) {
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
                        _errorDownload.value = context.getString(R.string.no_lotes_data)
                    } else {
                        var count = 0
                        val totalLotes = lotesResponseBody.size

                        for ((loteIndex, loteCloud) in lotesResponseBody.withIndex()) {
                            _progressMessage.postValue("Descargando lote ${loteIndex + 1} de $totalLotes")

                            val imagePaths = loteCloud.predicts.map { it.image.imagePath }
                            val chunkSize = 10
                            val imagePathsChunks = imagePaths.chunked(chunkSize)
                            val downloadedImages = mutableListOf<String>()

                            for ((chunkIndex, pathsChunk) in imagePathsChunks.withIndex()) {
                                _progressMessage.postValue(
                                    "Descargando lote ${loteIndex + 1}/$totalLotes | " +
                                            "Imágenes ${chunkIndex + 1}/${imagePathsChunks.size}"
                                )

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
                                    }
                                }
                                yield()
                            }

                            val loteToLocal = loteCloud.toLocalLote(downloadedImages)
                            withContext(Dispatchers.IO) {
                                val isInserted = loteRepository.verifyAndInsertLoteFromCloud(loteToLocal)
                                if (isInserted) count++
                            }
                            yield()
                        }
                        _downloadedCount.value = count
                    }
                } else {
                    _errorDownload.value = "Error: ${lotesResponse.code()}"
                }
            } catch (e: Exception) {
                _errorDownload.value = context.getString(R.string.error_downloading)
                Log.e(TAG, "Error en downloadBatchs", e)
            } finally {
                _isDownloading.value = false
            }
        }
    }

    suspend fun downloadImageToCache(context: Context, imageUrl: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(imageUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

                connection.inputStream.use { inputStream ->
                    val cacheDir = context.cacheDir
                    val fileName = "image_${System.currentTimeMillis()}_${imageUrl.hashCode()}.png"
                    val imageFile = File(cacheDir, fileName)

                    FileOutputStream(imageFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    return@withContext imageFile.absolutePath
                }
            } catch (e: Exception) {
                return@withContext null
            }
        }

    fun clearLocalData() {
        viewModelScope.launch(Dispatchers.IO) {
            loteRepository.deleteAllData()
        }
    }

    fun clearOnlyDataSynced() {
        viewModelScope.launch(Dispatchers.IO) {
            loteRepository.deleteAllDataSynced()
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.logout()
        }
    }
}

class ProfileViewModelFactory(
    private val repository: ProfileRepository,
    private val loteRepository: LoteRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(repository, loteRepository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
