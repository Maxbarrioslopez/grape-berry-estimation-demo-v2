package com.gaiaspa.metrics_detection.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.ml.MetricsPipeline
import com.gaiaspa.metrics_detection.ml.Success
import com.gaiaspa.metrics_detection.ml.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class ImagePrediction(
        val uri: Uri,
        val normalizedPath: String? = null,
        val previewBitmap: Bitmap? = null,
        val status: Status = Status.PENDING,
        val prediction: CalPredict? = null,
        val overlayPath: String? = null,
        val errorMessage: String? = null
    )

    enum class Status { PENDING, NORMALIZING, PROCESSING, DONE, ERROR }

    val imagePredictions = MutableLiveData<List<ImagePrediction>>(emptyList())
    val selectedVariety = MutableLiveData<VarietyOption?>(null)
    val company = MutableLiveData<String>("")
    val vessel = MutableLiveData<String>("")
    val block = MutableLiveData<String>("")
    val availableVarieties = MutableLiveData<List<VarietyOption>>(emptyList())
    val isSavingLote = MutableLiveData<Boolean>(false)

    private val instanceSeg = MetricsPipeline(application) { Log.d("HomeVM", it) }

    init {
        // LISTA OFICIAL DE VARIEDADES (ORDEN CRITICO 0-11 PARA EL MODELO)
        availableVarieties.value = listOf(
            VarietyOption(0, "ALLISON"),
            VarietyOption(1, "AUTUMN CRISP"),
            VarietyOption(2, "CRIMSON"),
            VarietyOption(3, "IVORY"),
            VarietyOption(4, "MAGENTA"),
            VarietyOption(5, "RED GLOBE"),
            VarietyOption(6, "SCARLOTTA"),
            VarietyOption(7, "SUPERIOR"),
            VarietyOption(8, "SWEET GLOBE"),
            VarietyOption(9, "THOMPSON"),
            VarietyOption(10, "TIMCO"),
            VarietyOption(11, "TIMPSON")
        )
    }

    fun addImage(path: String) {
        val current = imagePredictions.value.orEmpty().toMutableList()
        current.add(ImagePrediction(Uri.fromFile(File(path))))
        imagePredictions.value = current
    }

    fun removeImageAt(index: Int) {
        val current = imagePredictions.value.orEmpty().toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            imagePredictions.value = current
        }
    }

    fun processAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = imagePredictions.value.orEmpty()
            list.forEachIndexed { index, item ->
                if (item.status != Status.DONE) {
                    processImage(index, item)
                }
            }
        }
    }

    private suspend fun processImage(index: Int, item: ImagePrediction) {
        val context = getApplication<Application>()
        try {
            updateItemStatus(index, Status.NORMALIZING)
            val normalizedPath = try {
                 val f = File(context.cacheDir, "temp_${System.currentTimeMillis()}.jpg")
                 context.contentResolver.openInputStream(item.uri)?.use { input ->
                     f.outputStream().use { output -> input.copyTo(output) }
                 }
                 f.absolutePath
            } catch (e: Exception) { null }

            if (normalizedPath == null) {
                updateItemError(index, "Error al copiar imagen")
                return
            }

            val preview = ImageUtils.decodeSampledBitmap(normalizedPath, 300, 300)
            updateItemPreview(index, normalizedPath, preview)

            updateItemStatus(index, Status.PROCESSING)
            instanceSeg.invokeFromFile(
                imagePath = normalizedPath,
                smoothEdges = true,
                varietyId = selectedVariety.value?.id,
                onSuccess = { success ->
                    viewModelScope.launch(Dispatchers.Default) {
                        val baseBitmap = success.imageOrig ?: preview ?: return@launch
                        val overlay = success.imagePro.second
                        
                        val combined = if (overlay != null) {
                            val res = Bitmap.createBitmap(baseBitmap.width, baseBitmap.height, baseBitmap.config)
                            val canvas = android.graphics.Canvas(res)
                            canvas.drawBitmap(baseBitmap, 0f, 0f, null)
                            canvas.drawBitmap(overlay, 0f, 0f, null)
                            res
                        } else {
                            baseBitmap
                        }

                        val savedPath = ImageUtils.saveBitmapToDisk(combined, context.cacheDir, "overlay")
                        val finalPreview = Bitmap.createScaledBitmap(combined, 300, 300, true)
                        val calPredict = success.predictsList.firstOrNull() ?: CalPredict(status = false, error = "No prediction")
                        
                        updateItemSuccess(index, savedPath, finalPreview, calPredict)
                        if (combined != baseBitmap && combined != overlay) combined.recycle()
                    }
                },
                onFailure = { err -> updateItemError(index, err) }
            )
        } catch (e: Exception) {
            updateItemError(index, e.message ?: "Error desconocido")
        }
    }

    private fun updateItemStatus(index: Int, status: Status) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(status = status)
                imagePredictions.value = list
            }
        }
    }

    private fun updateItemPreview(index: Int, path: String, preview: Bitmap?) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(normalizedPath = path, previewBitmap = preview)
                imagePredictions.value = list
            }
        }
    }

    private fun updateItemSuccess(index: Int, overlayPath: String, preview: Bitmap, result: CalPredict) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(
                    status = Status.DONE,
                    overlayPath = overlayPath,
                    previewBitmap = preview,
                    prediction = result
                )
                imagePredictions.value = list
            }
        }
    }

    private fun updateItemError(index: Int, error: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(status = Status.ERROR, errorMessage = error)
                imagePredictions.value = list
            }
        }
    }

    fun saveBatch(callback: (Boolean) -> Unit) {
        isSavingLote.value = true
        viewModelScope.launch {
            delay(1500) // Simular guardado
            isSavingLote.value = false
            callback(true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        instanceSeg.close()
    }
}
