package com.gaiaspa.metrics_detection.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class MetricsPipeline(
    private val context: Context,
    private val providerPreference: String = DEFAULT_PROVIDER,
    private val message: (String) -> Unit
) {
    companion object {
        const val DEFAULT_PROVIDER = "auto"
    }

    private val segModelAsset = "weights/seg_best.onnx"
    private val regModelAsset = "weights/unified_runtime.onnx"
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
            val segPath = ensureAssetFile(segModelAsset) ?: throw Exception("Falta modelo de segmentación")
            val regPath = ensureAssetFile(regModelAsset) ?: throw Exception("Falta modelo de regresión")

            cppBridge.invoke(
                imagePath = imagePath,
                segModelPath = segPath.absolutePath,
                regModelPath = regPath.absolutePath,
                smoothEdges = smoothEdges,
                varietyId = varietyId,
                useDepth = false,
                providerPreference = providerPreference,
                onSuccess = { success ->
                    onSuccess(success)
                },
                onFailure = { err ->
                    onFailure(err)
                }
            )
        } catch (e: Exception) {
            onFailure(e.message ?: "Error en pipeline")
        }
    }

    private fun ensureAssetFile(assetPath: String): File? {
        val outFile = File(context.filesDir, assetPath)
        if (!outFile.exists() || outFile.length() == 0L) {
            outFile.parentFile?.mkdirs()
            try {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                return null
            }
        }
        if (assetPath.endsWith(".onnx")) {
            val dataAssetPath = "$assetPath.data"
            val dataOutFile = File(context.filesDir, dataAssetPath)
            if (!dataOutFile.exists() || dataOutFile.length() == 0L) {
                try {
                    context.assets.open(dataAssetPath).use { input ->
                        FileOutputStream(dataOutFile).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                } catch (e: Exception) {}
            }
        }
        return outFile
    }

    fun invoke(frame: Bitmap, smoothEdges: Boolean, varietyId: Int?, onSuccess: (Success) -> Unit, onFailure: (String) -> Unit) {
        val f = File(context.cacheDir, "camera_temp_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { out ->
            frame.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        invokeFromFile(f.absolutePath, smoothEdges, varietyId, onSuccess, onFailure)
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
        } catch (t: Throwable) { onFailure(t.message ?: "Error JNI") }
    }

    private fun parseSuccessFromJson(json: String): Success {
        val root = JSONObject(json)
        if (!root.optBoolean("status", false)) throw Exception(root.optString("error"))

        val proPath = root.optString("seg_pro_path", "")
        val maskPath = root.optString("seg_mask_path", "")
        
        val proBitmap = if (proPath.isNotBlank()) {
            val b = BitmapFactory.decodeFile(proPath)
            try { File(proPath).delete() } catch(e: Exception) {}
            b
        } else null

        val maskBitmap = if (maskPath.isNotBlank()) {
            val b = BitmapFactory.decodeFile(maskPath)
            try { File(maskPath).delete() } catch(e: Exception) {}
            b
        } else null

        val predArray = root.optJSONArray("pred") ?: JSONArray()
        val binsArray = root.optJSONArray("bins") ?: JSONArray()
        val pred = mutableListOf<Int>().apply { for (i in 0 until predArray.length()) add(predArray.optInt(i)) }
        val bins = mutableListOf<Float>().apply { for (i in 0 until binsArray.length()) add(binsArray.optDouble(i).toFloat()) }
        val qty = pred.sum().takeIf { it > 0 } ?: root.optDouble("count_total", 0.0).roundToInt()

        return Success(
            preProcessTime = root.optLong("pre_ms"),
            interfaceTime = root.optLong("infer_ms"),
            postProcessTime = root.optLong("post_ms"),
            results = emptyList(),
            depthMap = null,
            mmPerPx = root.optDouble("mm_per_px", 0.0).toFloat(),
            predictsList = listOf(com.gaiaspa.metrics_detection.data.model.CalPredict(status = true, bunchColor = root.optString("variety"), qty = qty, std = root.optDouble("std").toFloat(), mean = root.optDouble("mean").toFloat(), mode = root.optDouble("mode").toFloat(), pred = pred, bins = bins)),
            imageOrig = null,
            imagePro = Pair(proBitmap, maskBitmap),
            rawJson = json
        )
    }

    private external fun nativeRunPipeline(imagePath: String, segModelPath: String, regModelPath: String, varietyId: Int, providerPreference: String, smoothEdges: Boolean, useDepth: Boolean): String
    private external fun nativeRelease()
}
