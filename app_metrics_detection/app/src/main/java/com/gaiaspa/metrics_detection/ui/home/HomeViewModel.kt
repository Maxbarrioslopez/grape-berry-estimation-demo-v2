package com.gaiaspa.metrics_detection.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.gaiaspa.metrics_detection.data.model.CalPredict
import com.gaiaspa.metrics_detection.data.model.Lote
import com.gaiaspa.metrics_detection.data.repository.LoteRepository
import com.gaiaspa.metrics_detection.ml.MetricsPipeline
import com.gaiaspa.metrics_detection.ml.RuntimeVarietyCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    data class ImagePrediction(
        val image: Bitmap,
        val prediction: CalPredict? = null,
        val isProcessing: Boolean = false
    )

    val imagePredictions = MutableLiveData<MutableList<ImagePrediction>>(mutableListOf())

    // Step1 fields
    val company = MutableLiveData("")
    val vessel = MutableLiveData("")
    val block = MutableLiveData("")

    // Variety
    val availableVarieties = MutableLiveData<List<VarietyOption>>(emptyList())
    val selectedVariety = MutableLiveData<VarietyOption?>(null)

    private val loteRepository = LoteRepository.getInstance(application)
    private val instanceSeg: MetricsPipeline

    init {
        // 1) Load the live runtime variety IDs used by the native regressor.
        val vars = RuntimeVarietyCatalog.entries()
            .map { VarietyOption(it.id, RuntimeVarietyCatalog.toUiName(it.name)) }

        availableVarieties.value = vars

        // (Opcional pro) set default si existe y no hay selección
        if (selectedVariety.value == null && vars.isNotEmpty()) {
            selectedVariety.value = vars.first()
        }

        // 2) Initialize ML
        instanceSeg = MetricsPipeline(
            context = application.applicationContext,
            providerPreference = MetricsPipeline.DEFAULT_PROVIDER
        ) { msg -> Log.d("InstanceSeg", msg) }
    }

    fun addImage(bitmap: Bitmap) {
        val list = imagePredictions.value ?: mutableListOf()
        list.add(ImagePrediction(image = bitmap))
        imagePredictions.value = list

        processItemAt(list.size - 1)
    }

    fun processItemAt(index: Int) {
        val list = imagePredictions.value ?: return
        if (index !in list.indices) return

        val oldItem = list[index]
        list[index] = oldItem.copy(isProcessing = true)
        imagePredictions.postValue(list)

        // ✅ este es el varId correcto
        val varId: Int? = selectedVariety.value?.id?.takeIf { it >= 0 }
        Log.d("HomeViewModel", "processItemAt($index): varId=$varId, varietyName=${selectedVariety.value?.name}")

        viewModelScope.launch(Dispatchers.Default) {
            Log.d("HomeViewModel", "Antes de invocar instanceSeg.invoke() para index $index")
            instanceSeg.invoke(
                frame = oldItem.image,
                smoothEdges = true,
                varietyId = varId,   // ✅ ahora sí compila
                onSuccess = { success ->
                    Log.d("HomeViewModel", "onSuccess: predicciones=${success.predictsList.size}")
                    val combinedBitmap = combineBitmapsOverlay(
                        success.imagePro.first,
                        success.imagePro.second
                    )

                    val calPredict = success.predictsList.firstOrNull()
                    val finalPred = calPredict ?: CalPredict(
                        bunchColor = "null",
                        qty = 0,
                        pred = emptyList(),
                        bins = emptyList(),
                        mean = 0f,
                        mode = 0f,
                        std = 0f,
                        error = "No prediction",
                        status = false
                    )
                    Log.d("HomeViewModel", "Actualizando item con qty=${finalPred.qty}")
                    updateItemAfterProcessing(index, combinedBitmap, finalPred)
                },
                onFailure = { err ->
                    Log.e("HomeViewModel", "onFailure: $err")
                    updateItemAfterProcessing(
                        index,
                        oldItem.image,
                        CalPredict(
                            bunchColor = "null",
                            qty = 0,
                            pred = emptyList(),
                            bins = emptyList(),
                            mean = 0f,
                            mode = 0f,
                            std = 0f,
                            error = err,
                            status = false
                        )
                    )
                }
            )
        }
    }

    private fun updateItemAfterProcessing(index: Int, finalBitmap: Bitmap, calPredict: CalPredict) {
        val list = imagePredictions.value ?: return
        if (index !in list.indices) return

        val old = list[index]
        list[index] = old.copy(
            image = finalBitmap,
            prediction = calPredict,
            isProcessing = false
        )
        imagePredictions.postValue(list)
    }

    fun removeImageAt(index: Int) {
        val list = imagePredictions.value ?: return
        if (index in list.indices) {
            list.removeAt(index)
            imagePredictions.value = list
        }
    }

    fun combineBitmapsOverlay(bmp1: Bitmap, bmp2: Bitmap?): Bitmap {
        if (bmp2 == null) return bmp1
        val finalWidth = maxOf(bmp1.width, bmp2.width)
        val finalHeight = maxOf(bmp1.height, bmp2.height)
        val output = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        canvas.drawBitmap(bmp1, 0f, 0f, null)
        canvas.drawBitmap(bmp2, 0f, 0f, null)
        return output
    }

    fun getLote(): Lote {
        val context = getApplication<Application>().applicationContext

        val imageUris = imagePredictions.value?.map { item ->
            saveBitmapToLocal(context, item.image)
        } ?: emptyList()

        val allPredicts = imagePredictions.value?.mapNotNull { it.prediction } ?: emptyList()
        val varOpt = selectedVariety.value

        return Lote(
            userId = loteRepository.getCurrentUserId(),
            cloudId = "",

            company = company.value.orEmpty(),
            vessel = vessel.value.orEmpty(),
            block = block.value.orEmpty(),

            images = imageUris,
            calPredicts = allPredicts,
            synced = false,

            // ✅ lo que pediste
            varietyId = varOpt?.id ?: -1,
            varietyName = varOpt?.name.orEmpty()
        )
    }

    fun addLote(lote: Lote) {
        viewModelScope.launch(Dispatchers.IO) {
            loteRepository.insertLocalLote(lote)
        }
    }

    private fun saveBitmapToLocal(context: android.content.Context, bitmap: Bitmap): String {
        val fileName = "img_${System.currentTimeMillis()}.png"
        val file = java.io.File(context.filesDir, fileName)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        return file.absolutePath
    }
}
