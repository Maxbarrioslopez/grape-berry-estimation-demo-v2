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
 * HomeViewModel - v8.6 FIXED (Unresolved Reference & Sync Trace)
 * Arquitectura de imágenes:
 * 1. src_...: Original local.
 * 2. res_...: Imagen visual con overlay Kotlin (Alta Res).
 * 3. upload_512_...: Imagen limpia 512x512 para Backend.
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

                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(srcFile.absolutePath, opts)
                val realW = opts.outWidth
                val realH = opts.outHeight

                val tempPreview = ImageUtils.decodeSampledBitmap(srcFile.absolutePath, 512, 512)
                updateItemPreview(index, srcFile.absolutePath, tempPreview)

                updateItemStatus(index, Status.PROCESSING)
                
                instanceSeg.invokeFromFile(
                    imagePath = srcFile.absolutePath,
                    smoothEdges = true,
                    varietyId = selectedVariety.value?.id,
                    onSuccess = { success ->
                        viewModelScope.launch(Dispatchers.Default) {
                            
                            // 🚀 VALIDACIÓN DEFENSIVA POST-MODELO (POST-INFERENCE)
                            // Se corrige referencia a 'box' en lugar de 'output0' según SegmentationResult.kt
                            val detections = success.results
                            val grapeCount = detections.count { it.box.clsName.contains("grape") }
                            val totalDetections = detections.size
                            
                            // REGLAS: 
                            // 1. Debe haber al menos dos uvas para ser considerado válido.
                            if (grapeCount < 2) {
                                 val msg = if (totalDetections == 0) "No se detectó nada" else "Imagen inválida: No se detectan suficientes uvas"
                                 Log.w("HomeVM_VAL", "Validación fallida: grapes=$grapeCount, total=$totalDetections")
                                 updateItemError(index, msg)
                                 return@launch
                            }

                            // 2. GENERAR RES (Overlay Kotlin en alta resolución)
                            val visualBitmap = ImageUtils.decodeSampledBitmap(srcFile.absolutePath, 1600, 1600) 
                                ?: return@launch
                            
                            val resultImage = ImageUtils.drawDetectionsOverlay(visualBitmap, success.results, realW, realH)
                            val resPath = ImageUtils.saveBitmapToDisk(resultImage, lotesDir, "res_${time}_$index")
                            
                            // 3. GENERAR UPLOAD_512
                            val uploadPath = ImageUtils.generateUpload512(srcFile.absolutePath, lotesDir)
                            
                            val finalPreview = Bitmap.createScaledBitmap(resultImage, 300, 300, true)
                            val calPredict = success.predictsList.firstOrNull() ?: CalPredict(status = false, error = "Fallo")
                            
                            updateItemSuccess(index, resPath ?: "", uploadPath ?: "", finalPreview, calPredict)
                            
                            if (visualBitmap != resultImage) visualBitmap.recycle()
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
                    overlayImages = currentPredictions.mapNotNull { it.overlayPath },
                    calPredicts = currentPredictions.mapNotNull { it.prediction },
                    synced = false
                )
                repository.insertLocalLote(lote)
                
                // 🚀 LANZAR SYNC INMEDIATO (Validación de traza)
                Log.d("SyncWorker_TRACE", "Enqueuing manual sync tras guardar lote local")
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
