package com.gaiaspa.metrics_detection.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class MetricsPipeline(
    private val context: Context,
    private val providerPreference: String = DEFAULT_PROVIDER,
    private val message: (String) -> Unit = {}
) {
    companion object {
        const val DEFAULT_PROVIDER = "auto"
        private const val TAG = "MetricsPipeline"
    }

    private val segModelAsset = "weights/seg_best.onnx"
    private val regModelAsset = "weights/unified_runtime.onnx"
    private val cppBridge = CppPipelineBridge(context)

    fun close() = cppBridge.close()

    /**
     * Lanza la inferencia. 
     * Asume que los archivos ya fueron extraídos por MetricsDetectionApp.
     */
    fun invokeFromFile(
        imagePath: String,
        smoothEdges: Boolean,
        varietyId: Int?,
        onSuccess: (Success) -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val segFile = File(context.filesDir, segModelAsset)
            val regFile = File(context.filesDir, regModelAsset)

            if (!segFile.exists() || !regFile.exists()) {
                onFailure("Modelos no inicializados. Reinstale la App.")
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
            val resultJson = nativeRunPipeline(imagePath, segModelPath, regModelPath, varietyId ?: -1, providerPreference, smoothEdges, useDepth)
            onSuccess(parseSuccessFromJson(resultJson))
        } catch (t: Throwable) { onFailure(t.message ?: "Fallo JNI") }
    }

    private fun parseSuccessFromJson(json: String): Success {
        val root = JSONObject(json)
        if (!root.optBoolean("status", false)) throw Exception(root.optString("error", "Fallo JNI"))

        val jniPaths = root.optJSONObject("jni_paths")
        val proPath = jniPaths?.optString("pro", "") ?: ""

        val proBitmap = if (proPath.isNotBlank() && File(proPath).exists()) {
            BitmapFactory.decodeFile(proPath)
        } else null

        val predArray = root.optJSONArray("pred") ?: JSONArray()
        val binsArray = root.optJSONArray("bins") ?: JSONArray()
        val pred = mutableListOf<Int>().apply { for (i in 0 until predArray.length()) add(predArray.optInt(i)) }
        val bins = mutableListOf<Float>().apply { for (i in 0 until binsArray.length()) add(binsArray.optDouble(i).toFloat()) }
        val qty = root.optDouble("count_total", 0.0).roundToInt()

        return Success(
            preProcessTime = 0,
            interfaceTime = root.optLong("inf_ms"),
            postProcessTime = root.optLong("post_ms"),
            results = emptyList(),
            depthMap = null,
            mmPerPx = root.optDouble("mm_per_px", 0.0).toFloat(),
            predictsList = listOf(com.gaiaspa.metrics_detection.data.model.CalPredict(
                status = true, bunchColor = root.optString("variety"), qty = qty, 
                std = root.optDouble("std").toFloat(), mean = root.optDouble("mean").toFloat(), 
                mode = root.optDouble("mode").toFloat(), pred = pred, bins = bins)),
            imageOrig = null,
            imagePro = Pair(proBitmap, null),
            rawJson = json
        )
    }

    private external fun nativeRunPipeline(imagePath: String, segModelPath: String, regModelPath: String, varietyId: Int, providerPreference: String, smoothEdges: Boolean, useDepth: Boolean): String
    private external fun nativeRelease()
}
