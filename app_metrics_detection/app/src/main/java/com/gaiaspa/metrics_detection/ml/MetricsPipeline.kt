package com.gaiaspa.metrics_detection.ml

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.gaiaspa.metrics_detection.data.model.CalPredict
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * MetricsPipeline.kt - v6.8 MAESTRA
 * Puente de comunicación con el motor C++ modular.
 * Sincronizado con el contrato de JSON de grape_pipeline_postprocess.cpp.
 */
class MetricsPipeline(
    private val context: Context,
    private val providerPreference: String = DEFAULT_PROVIDER,
    private val message: (String) -> Unit = {}
) {
    companion object {
        const val DEFAULT_PROVIDER = "auto"
        private const val TAG = "MetricsPipeline"
    }

    private val segModelFile = "seg_best.onnx"
    private val regModelFile = "qty_model_rgbdt.onnx"
    private val cppBridge = CppPipelineBridge(context)

    fun close() = cppBridge.close()

    fun invokeFromFile(
        imagePath: String,
        smoothEdges: Boolean,
        varietyId: Int?,
        onSuccess: (Success) -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val weightsDir = File(context.filesDir, "weights")
            val segFile = File(weightsDir, segModelFile)
            val regFile = File(weightsDir, regModelFile)

            if (!segFile.exists() || !regFile.exists()) {
                onFailure("Modelos no encontrados en disco. Reinstale la App.")
                return
            }

            cppBridge.invoke(
                imagePath = imagePath,
                segModelPath = segFile.absolutePath,
                regModelPath = regFile.absolutePath,
                smoothEdges = smoothEdges,
                varietyId = varietyId,
                useDepth = false,
                providerPreference = providerPreference,
                onSuccess = onSuccess,
                onFailure = onFailure
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            onFailure(e.message ?: "Error en el pipeline")
        }
    }
}

private class CppPipelineBridge(private val context: Context) {
    private var nativeLoaded = try { System.loadLibrary("grape_pipeline_jni"); true } catch (t: Throwable) { false }

    fun close() { if (nativeLoaded) nativeRelease() }

    fun invoke(imagePath: String, segModelPath: String, regModelPath: String, smoothEdges: Boolean, varietyId: Int?, useDepth: Boolean, providerPreference: String, onSuccess: (Success) -> Unit, onFailure: (String) -> Unit) {
        if (!nativeLoaded) { onFailure("Libreria nativa no cargada"); return }
        try {
            // saveDebugArtifacts se activa con smoothEdges para generar el overlay visual
            val resultJson = nativeRunPipeline(imagePath, segModelPath, regModelPath, varietyId ?: -1, providerPreference, smoothEdges, useDepth)
            onSuccess(parseSuccessFromJson(resultJson))
        } catch (t: Throwable) { 
            onFailure(t.message ?: "Fallo JNI") 
        }
    }

    private fun parseSuccessFromJson(json: String): Success {
        val root = JSONObject(json)
        if (!root.optBoolean("status", false)) throw Exception(root.optString("error", "Error JNI"))

        // ✅ SINCRONIZADO: Acceso a la ruta del overlay según el nuevo JSON de C++
        val debugObj = root.optJSONObject("debug")
        val proPath = debugObj?.optString("overlay", "") ?: ""
        
        val proBitmap = if (proPath.isNotBlank() && File(proPath).exists()) {
            BitmapFactory.decodeFile(proPath)
        } else null

        // ✅ PARSEO DE DETECCIONES: Filtrando Bunches para dejar solo Uvas y Pingpong
        val detectionsArray = root.optJSONArray("detections") ?: JSONArray()
        val segmentationResults = mutableListOf<SegmentationResult>()
        
        for (i in 0 until detectionsArray.length()) {
            val det = detectionsArray.getJSONObject(i)
            val name = det.optString("class_name", "").lowercase()
            
            // Solo procesamos lo que no es un racimo (bunch)
            if (name.contains("bunch")) continue

            val boxArr = det.optJSONArray("box")
            if (boxArr != null && boxArr.length() == 4) {
                val output0 = Output0(
                    x1 = boxArr.getDouble(0).toFloat(),
                    y1 = boxArr.getDouble(1).toFloat(),
                    x2 = boxArr.getDouble(0).toFloat() + boxArr.getDouble(2).toFloat(),
                    y2 = boxArr.getDouble(1).toFloat() + boxArr.getDouble(3).toFloat(),
                    cx = 0f, cy = 0f, w = 0f, h = 0f,
                    cnf = det.optDouble("score", 0.0).toFloat(),
                    cls = det.optInt("cls", 0),
                    clsName = if (name.contains("ping")) "pingpong" else "grape",
                    maskWeight = emptyList()
                )
                segmentationResults.add(SegmentationResult(output0, emptyArray(), 0))
            }
        }

        val predArray = root.optJSONArray("pred") ?: JSONArray()
        val binsArray = root.optJSONArray("bins") ?: JSONArray()
        val pred = mutableListOf<Int>().apply { for (i in 0 until predArray.length()) add(predArray.optInt(i)) }
        val bins = mutableListOf<Float>().apply { for (i in 0 until binsArray.length()) add(binsArray.optDouble(i).toFloat()) }

        val timing = root.optJSONObject("timing_ms")
        
        return Success(
            preProcessTime = timing?.optLong("preprocess_ms") ?: 0,
            interfaceTime = root.optLong("inf_ms"),
            postProcessTime = timing?.optLong("post_ms") ?: 0,
            results = segmentationResults, 
            depthMap = null,
            mmPerPx = root.optDouble("mm_per_px", 0.0).toFloat(),
            predictsList = listOf(CalPredict(
                status = true, 
                bunchColor = root.optString("variety"), 
                qty = root.optDouble("count_total", 0.0).roundToInt(), 
                std = root.optDouble("std").toFloat(), 
                mean = root.optDouble("mean").toFloat(), 
                mode = root.optDouble("mode").toFloat(), 
                pred = pred, 
                bins = bins
            )),
            imageOrig = null,
            imagePro = Pair(proBitmap, null),
            rawJson = json
        )
    }

    private external fun nativeRunPipeline(imagePath: String, segModelPath: String, regModelPath: String, varietyId: Int, providerPreference: String, saveDebug: Boolean, allowSynthetic: Boolean): String
    private external fun nativeRelease()
}
