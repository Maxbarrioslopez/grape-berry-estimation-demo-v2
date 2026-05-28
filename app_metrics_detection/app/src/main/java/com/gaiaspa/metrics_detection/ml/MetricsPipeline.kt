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
 * MetricsPipeline — v10.0 NATIVE OVERLAY MIGRATION
 *
 * Bridge layer between the Kotlin/Android UI and the native C++ ML engine
 * ([grape_pipeline_jni.cpp]). Responsible for:
 * - Validating that ONNX model files exist on disk before invoking JNI.
 * - Delegating inference to the native library via [CppPipelineBridge].
 * - Parsing the raw JSON response coming from native code into the [Success] domain model.
 *
 * The JSON contract is tightly coupled to the native implementation
 * (v10.0 overlay generation) and must be kept in sync.
 */
class MetricsPipeline(
    private val context: Context,
    private val providerPreference: String = DEFAULT_PROVIDER,
    private val message: (String) -> Unit = {}
) {
    companion object {
        /** Default ONNX execution provider preference passed to the native engine. */
        const val DEFAULT_PROVIDER = "auto"
        private const val TAG = "MetricsPipeline"
    }

    /** Filename of the semantic segmentation ONNX model. */
    private val segModelFile = "seg_best.onnx"
    /** Filename of the regression (quantity/calibre) ONNX model. */
    private val regModelFile = "qty_model_rgbdt.onnx"
    private val cppBridge = CppPipelineBridge(context)

    /**
     * Releases native resources held by the pipeline bridge.
     * Must be called when the pipeline is no longer needed to avoid memory leaks.
     */
    fun close() = cppBridge.close()

    /**
     * Runs the full inference pipeline on an image stored on disk.
     *
     * Validates that both ONNX model files exist under [Context.filesDir]/weights/,
     * then delegates to the JNI bridge which executes segmentation + regression.
     *
     * @param imagePath Absolute path to the input image (JPEG/PNG).
     * @param smoothEdges Whether to apply edge-smoothing post-processing.
     * @param varietyId Optional variety identifier matching [RuntimeVarietyCatalog] IDs.
     *        Pass `null` for variety-agnostic inference.
     * @param visualOverlayBase Path to the high-quality upload copy used by the native
     *        overlay generator. Pass `null` to skip overlay generation.
     * @param onSuccess Callback invoked with the parsed [Success] result on completion.
     * @param onFailure Callback invoked with a human-readable error message on failure.
     */
    fun invokeFromFile(
        imagePath: String,
        smoothEdges: Boolean,
        varietyId: Int?,
        visualOverlayBase: String? = null, // Ruta de la copia de upload_512 para el overlay nativo
        onSuccess: (Success) -> Unit,
        onFailure: (String) -> Unit
    ) {
        try {
            val weightsDir = File(context.filesDir, "weights")
            val segFile = File(weightsDir, segModelFile)
            val regFile = File(weightsDir, regModelFile)

            if (!segFile.exists() || !regFile.exists()) {
                onFailure("Models not found on disk.")
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
                visualOverlayPath = visualOverlayBase, // ✅ Pasar al bridge
                onSuccess = onSuccess,
                onFailure = onFailure
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            onFailure(e.message ?: "Pipeline error")
        }
    }
}

/**
 * Lightweight wrapper around the JNI native library [libgrape_pipeline_jni].
 *
 * Handles native library loading, JSON result parsing, and error translation
 * from JNI exceptions into Kotlin callbacks. Kept private so all JNI details
 * remain encapsulated behind the [MetricsPipeline] public API.
 */
private class CppPipelineBridge(private val context: Context) {
    private var nativeLoaded = try { System.loadLibrary("grape_pipeline_jni"); true } catch (t: Throwable) { false }

    /** Releases native-side resources if the library was loaded successfully. */
    fun close() { if (nativeLoaded) nativeRelease() }

    /**
     * Invokes the native pipeline and maps the result to a [Success] instance.
     *
     * @param varietyId Sentinel -1 is used when no variety is selected (native side
     *        treats -1 as "variety agnostic").
     * @param visualOverlayPath Empty string signals that overlay generation is not requested.
     */
    fun invoke(imagePath: String, segModelPath: String, regModelPath: String, smoothEdges: Boolean, varietyId: Int?, useDepth: Boolean, providerPreference: String, visualOverlayPath: String?, onSuccess: (Success) -> Unit, onFailure: (String) -> Unit) {
        if (!nativeLoaded) { onFailure("Native library not loaded"); return }
        try {
            // ✅ Firma JNI actualizada v10.0
            val resultJson = nativeRunPipeline(
                imagePath, 
                segModelPath, 
                regModelPath, 
                varietyId ?: -1, 
                providerPreference, 
                smoothEdges, 
                useDepth,
                visualOverlayPath ?: "" // New parameter
            )
            onSuccess(parseSuccessFromJson(resultJson))
        } catch (t: Throwable) { 
            onFailure(t.message ?: "JNI failure") 
        }
    }

    private fun parseSuccessFromJson(json: String): Success {
        val root = JSONObject(json)
        if (!root.optBoolean("status", false)) throw Exception(root.optString("error", "Error JNI"))

        // ✅ SINCRONIZADO v10.0: 'pro' ahora contiene el res_ visual generado en C++
        val pathsObj = root.optJSONObject("jni_paths")
        val visualPath = pathsObj?.optString("pro", "") ?: ""
        val debugPath = pathsObj?.optString("debug_overlay", "") ?: ""
        
        val visualBitmap = if (visualPath.isNotBlank() && File(visualPath).exists()) {
            BitmapFactory.decodeFile(visualPath)
        } else null

        val debugBitmap = if (debugPath.isNotBlank() && File(debugPath).exists()) {
            BitmapFactory.decodeFile(debugPath)
        } else null

        val detectionsArray = root.optJSONArray("detections") ?: JSONArray()
        val segmentationResults = mutableListOf<SegmentationResult>()
        
        for (i in 0 until detectionsArray.length()) {
            val det = detectionsArray.getJSONObject(i)
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
                    clsName = det.optString("class_name", ""),
                    maskWeight = emptyList()
                )
                segmentationResults.add(SegmentationResult(output0, emptyArray(), 0))
            }
        }

        val predArray = root.optJSONArray("pred") ?: JSONArray()
        val binsArray = root.optJSONArray("bins") ?: JSONArray()
        val pred = mutableListOf<Int>().apply { for (i in 0 until predArray.length()) add(predArray.optInt(i)) }
        val bins = mutableListOf<Float>().apply { for (i in 0 until binsArray.length()) add(binsArray.optDouble(i).toFloat()) }

        return Success(
            preProcessTime = 0,
            interfaceTime = root.optLong("inf_ms"),
            postProcessTime = 0,
            results = segmentationResults, 
            depthMap = null,
            mmPerPx = 0.0f,
            predictsList = listOf(CalPredict(
                status = true, 
                bunchColor = root.optString("variety"), 
                qty = root.optInt("count_total"), 
                std = root.optDouble("std").toFloat(), 
                mean = root.optDouble("mean").toFloat(), 
                mode = root.optDouble("mode").toFloat(), 
                pred = pred, 
                bins = bins
            )),
            imageOrig = null,
            imagePro = Pair(visualBitmap, debugBitmap), // (Visual, Debug)
            rawJson = json
        )
    }

    private external fun nativeRunPipeline(imagePath: String, segModelPath: String, regModelPath: String, varietyId: Int, providerPreference: String, saveDebug: Boolean, allowSynthetic: Boolean, visualOverlayPath: String): String
    private external fun nativeRelease()
}
