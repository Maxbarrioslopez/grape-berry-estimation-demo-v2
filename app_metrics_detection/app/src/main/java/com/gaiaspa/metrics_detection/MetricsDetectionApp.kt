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
        
        // Extraer modelos en segundo plano al iniciar para evitar bloqueos en el flujo de test
        CoroutineScope(Dispatchers.IO).launch {
            prepareModels()
        }
    }

    private fun prepareModels() {
        val models = listOf(
            "weights/seg_best.onnx",
            "weights/unified_runtime.onnx"
        )
        
        models.forEach { assetPath ->
            try {
                extractAsset(assetPath)
                // Intentar extraer el sidecar .data si existe (importante para modelos pesados)
                extractAsset("$assetPath.data")
            } catch (e: Exception) {
                // Silencioso para el sidecar si no existe
            }
        }
    }

    private fun extractAsset(assetPath: String) {
        val outFile = File(filesDir, assetPath)
        // Solo copiamos si no existe o está corrupto (0 bytes)
        if (!outFile.exists() || outFile.length() == 0L) {
            outFile.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d("App", "Modelo extraído: $assetPath (${outFile.length()} bytes)")
        }
    }
}
