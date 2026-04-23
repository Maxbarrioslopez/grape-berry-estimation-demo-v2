package com.gaiaspa.metrics_detection.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.ml.MetricsPipeline
import com.gaiaspa.metrics_detection.ml.ImageUtils
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * HomeViewModel - v11.0 FINAL OVERLAY FIX
 * Sincroniza la UI con el overlay nativo de C++.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class ImagePrediction(
        val uri: Uri,
        val normalizedPath: String? = null,
        val uploadPath: String? = null,
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

    private val repository = LoteRepository.getInstance(application)
    private val instanceSeg = MetricsPipeline(application) { Log.d("HomeVM", it) }

    init {
        availableVarieties.value = listOf(
            VarietyOption(0, "ALLISON"), VarietyOption(1, "AUTUMN CRISP"),
            VarietyOption(2, "CRIMSON"), VarietyOption(3, "IVORY"),
            VarietyOption(4, "MAGENTA"), VarietyOption(5, "RED GLOBE"),
            VarietyOption(6, "SCARLOTTA"), VarietyOption(7, "SUPERIOR"),
            VarietyOption(8, "SWEET GLOBE"), VarietyOption(9, "THOMPSON"),
            VarietyOption(10, "TIMCO"), VarietyOption(11, "TIMPSON")
        )
    }

    fun addImage(path: String) {
        val current = imagePredictions.value.orEmpty().toMutableList()
        current.add(ImagePrediction(Uri.fromFile(File(path))))
        imagePredictions.value = current
        processAll()
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
                if (item.status == Status.PENDING || item.status == Status.ERROR) {
                    processImage(index, item)
                }
            }
        }
    }

    private fun processImage(index: Int, item: ImagePrediction) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                updateItemStatus(index, Status.NORMALIZING)
                val lotesDir = File(context.filesDir, "lotes_media").apply { mkdirs() }
                val time = System.currentTimeMillis()
                
                val srcFile = File(lotesDir, "src_${time}_$index.jpg")
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    srcFile.outputStream().use { output -> input.copyTo(output) }
                }

                // 1. GENERAR UPLOAD (Base limpia 1024px)
                val uploadPath = ImageUtils.generateUpload512(srcFile.absolutePath, lotesDir) ?: ""
                
                // 2. PREPARAR RES (Copia para C++)
                val resFile = File(lotesDir, "res_${time}_$index.jpg")
                if (uploadPath.isNotEmpty()) {
                    File(uploadPath).copyTo(resFile, overwrite = true)
                }

                // Preview temporal rápido mientras procesa
                val tempPreview = ImageUtils.decodeSampledBitmap(srcFile.absolutePath, 300, 300)
                updateItemPreview(index, srcFile.absolutePath, tempPreview)

                updateItemStatus(index, Status.PROCESSING)
                
                // 3. LLAMAR A PIPELINE
                instanceSeg.invokeFromFile(
                    imagePath = srcFile.absolutePath,
                    smoothEdges = true,
                    varietyId = selectedVariety.value?.id,
                    visualOverlayBase = resFile.absolutePath,
                    onSuccess = { success ->
                        viewModelScope.launch(Dispatchers.Default) {
                            
                            Log.d("OVERLAY_UI_FLOW", "[1] Pipeline SUCCESS. resFile: ${resFile.absolutePath} | Size: ${resFile.length()}")
                            
                            // ✅ FORZADO: Regeneramos el bitmap desde el ARCHIVO después de que C++ dibujó
                            Log.d("OVERLAY_UI_FLOW", "[2] Generating final preview from C++ render...")
                            val finalPreview = ImageUtils.decodeSampledBitmap(resFile.absolutePath, 512, 512)
                            
                            val calPredict = success.predictsList.firstOrNull() ?: CalPredict(status = false, error = "Fallo")
                            
                            // Actualizamos el estado con la ruta del overlay REAL
                            updateItemSuccess(index, resFile.absolutePath, uploadPath, finalPreview ?: tempPreview!!, calPredict)
                        }
                    },
                    onFailure = { err -> updateItemError(index, err) }
                )
            } catch (e: Exception) {
                Log.e("HomeVM", "Error en processImage: ${e.message}")
                updateItemError(index, e.message ?: "Error desconocido")
            }
        }
    }

    fun saveBatch(callback: (Boolean) -> Unit) {
        val currentPredictions = imagePredictions.value.orEmpty()
        if (currentPredictions.isEmpty() || currentPredictions.any { it.status != Status.DONE }) {
            callback(false)
            return
        }

        isSavingLote.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                TokenProvider.init(getApplication())
                val lote = Lote(
                    userId = repository.getCurrentUserId(),
                    company = company.value ?: "Sin Empresa",
                    vessel = vessel.value ?: "Sin Nave",
                    block = block.value ?: "Sin Cuartel",
                    varietyId = selectedVariety.value?.id ?: -1,
                    varietyName = selectedVariety.value?.name ?: "UNKNOWN",
                    sourceImages = currentPredictions.map { it.uri.toString() },
                    normalizedImages = currentPredictions.mapNotNull { it.normalizedPath },
                    uploadImages = currentPredictions.mapNotNull { it.uploadPath },
                    overlayImages = currentPredictions.mapNotNull { it.overlayPath }, // ✅ PERSISTIMOS EL RES_ NATIVO
                    calPredicts = currentPredictions.mapNotNull { it.prediction },
                    synced = false
                )
                repository.insertLocalLote(lote)
                
                com.gaiaspa.metrics_detection.worker.SyncManager.enqueueManualSync(getApplication())

                viewModelScope.launch(Dispatchers.Main) {
                    isSavingLote.value = false
                    imagePredictions.value = emptyList()
                    callback(true)
                }
            } catch (e: Exception) {
                viewModelScope.launch(Dispatchers.Main) {
                    isSavingLote.value = false
                    callback(false)
                }
            }
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

    private fun updateItemSuccess(index: Int, overlayPath: String, uploadPath: String, preview: Bitmap, result: CalPredict) {
        viewModelScope.launch(Dispatchers.Main) {
            Log.d("OVERLAY_UI_FLOW", "[3] UI State Update. finalPath: $overlayPath")
            val list = imagePredictions.value.orEmpty().toMutableList()
            if (index in list.indices) {
                list[index] = list[index].copy(
                    status = Status.DONE,
                    overlayPath = overlayPath,
                    uploadPath = uploadPath,
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

    override fun onCleared() {
        super.onCleared()
        instanceSeg.close()
    }
}
