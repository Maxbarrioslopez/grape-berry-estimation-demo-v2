package com.gaiaspa.metrics_detection

import android.app.Application
import android.util.Log
import com.gaiaspa.metrics_detection.network.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MetricsDetectionApp : Application() {

    override fun onCreate() {
        super.onCreate()
        TokenProvider.init(this)
        
        // Extraer modelos en segundo plano de forma atómica para evitar archivos corruptos
        CoroutineScope(Dispatchers.IO).launch {
            prepareModels()
        }
    }

    private fun prepareModels() {
        val models = listOf(
            "weights/modelos/legacy/seg_best.onnx",
            "weights/modelos/qty_model_rgbdt.onnx",
            "weights/modelos/hist_rgbdt_bimodal.onnx"
        )
        
        models.forEach { assetPath ->
            extractAssetAtomic(assetPath)
            // Extraer pesos externos .data si existen
            extractAssetAtomic("$assetPath.data")
        }
    }

    private fun extractAssetAtomic(assetPath: String) {
        val fileName = File(assetPath).name
        val weightsDir = File(filesDir, "weights").apply { mkdirs() }
        val targetFile = File(weightsDir, fileName)
        
        // Solo copiamos si no existe. Si está corrupto, el usuario debe reinstalar.
        if (targetFile.exists() && targetFile.length() > 0) return

        val tempFile = File(weightsDir, "$fileName.tmp")
        try {
            assets.open(assetPath).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Renombrado atómico: si esto sucede, el archivo está completo.
            if (tempFile.renameTo(targetFile)) {
                Log.d("App", "✅ Modelo listo: $fileName")
            }
        } catch (e: Exception) {
            // Es normal fallar para archivos .data opcionales
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
