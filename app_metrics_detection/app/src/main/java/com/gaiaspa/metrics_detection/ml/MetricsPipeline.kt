package com.gaiaspa.metrics_detection.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.gaiaspa.metrics_detection.data.model.CalPredict
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Punto de entrada del pipeline nativo C++ (sin fallback Kotlin). */
class MetricsPipeline(
    private val context: Context,
    private val providerPreference: String = DEFAULT_PROVIDER,
    private val message: (String) -> Unit
) {
    companion object {
        const val DEFAULT_PROVIDER = "auto"
    }

    private val segModelAsset = "weights/seg_best.onnx"
    private val regModelAsset = "weights/best_model_5ch_residual.onnx"

    private val cppBridge = CppPipelineBridge(context)

    fun close() {
        cppBridge.close()
    }

    fun invoke(
        frame: Bitmap,
        smoothEdges: Boolean,
        varietyId: Int?,
        useDepth: Boolean = false,
        onSuccess: (Success) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // La UI sigue entrando por Bitmap; el bridge nativo consume rutas de archivo.
        invokeInternal(
            imagePath = saveTempBitmap(frame),
            inputFrame = frame,
            smoothEdges = smoothEdges,
            varietyId = varietyId,
            useDepth = useDepth,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun invokeFile(
        imagePath: String,
        inputFrame: Bitmap,
        smoothEdges: Boolean,
        varietyId: Int?,
        useDepth: Boolean = false,
        onSuccess: (Success) -> Unit,
        onFailure: (String) -> Unit
    ) {
        invokeInternal(
            imagePath = imagePath,
            inputFrame = inputFrame,
            smoothEdges = smoothEdges,
            varietyId = varietyId,
            useDepth = useDepth,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    private fun invokeInternal(
        imagePath: String,
        inputFrame: Bitmap,
        smoothEdges: Boolean,
        varietyId: Int?,
        useDepth: Boolean,
        onSuccess: (Success) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val segPath = ensureAssetFile(segModelAsset)
        val regPath = ensureAssetFile(regModelAsset)

        // Copiar archivos .data si existen, pero no fallar si faltan.
        ensureAssetFileOptional("${segModelAsset}.data")
        ensureAssetFileOptional("${regModelAsset}.data")

        if (segPath == null || regPath == null) {
            onFailure("No se pudieron preparar modelos ONNX en almacenamiento local")
            return
        }

        message("MetricsPipeline imagePath=$imagePath provider=$providerPreference")
        cppBridge.invoke(
            imagePath = imagePath,
            segModelPath = segPath.absolutePath,
            regModelPath = regPath.absolutePath,
            inputFrame = inputFrame,
            smoothEdges = smoothEdges,
            varietyId = varietyId,
            useDepth = useDepth,
            providerPreference = providerPreference,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    private fun saveTempBitmap(bitmap: Bitmap): String {
        val f = File(context.cacheDir, "cpp_input_${System.currentTimeMillis()}.png")
        FileOutputStream(f).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        return f.absolutePath
    }

    private fun ensureAssetFile(assetPath: String): File? {
        return try {
            val outFile = File(context.filesDir, assetPath)
            outFile.parentFile?.mkdirs()

            if (!outFile.exists() || outFile.length() == 0L) {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
            }
            outFile
        } catch (t: Throwable) {
            Log.e("MetricsPipeline", "No se pudo extraer asset: $assetPath", t)
            null
        }
    }

    private fun ensureAssetFileOptional(assetPath: String): File? {
        return try {
            val outFile = File(context.filesDir, assetPath)
            outFile.parentFile?.mkdirs()

            if (!outFile.exists() || outFile.length() == 0L) {
                try {
                    context.assets.open(assetPath).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                } catch (_: Throwable) {
                    Log.w("MetricsPipeline", "Asset opcional no encontrado (esto es normal): $assetPath")
                    return null
                }
            }
            outFile
        } catch (_: Throwable) {
            Log.w("MetricsPipeline", "No se pudo extraer asset opcional: $assetPath")
            null
        }
    }
}

private class CppPipelineBridge(private val context: Context) {
    private var nativeLoaded: Boolean = false

    init {
        nativeLoaded = try {
            System.loadLibrary("grape_pipeline_jni")
            true
        } catch (t: Throwable) {
            Log.w("MetricsPipeline", "No se pudo cargar grape_pipeline_jni", t)
            false
        }
    }

    fun close() {
        if (nativeLoaded) {
            try {
                nativeRelease()
            } catch (t: Throwable) {
                Log.w("MetricsPipeline", "nativeRelease fallo", t)
            }
        }
    }

    fun invoke(
        imagePath: String,
        segModelPath: String,
        regModelPath: String,
        inputFrame: Bitmap,
        smoothEdges: Boolean,
        varietyId: Int?,
        useDepth: Boolean,
        providerPreference: String,
        onSuccess: (Success) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!nativeLoaded) {
            Log.e("CppPipelineBridge", "Libreria nativa NO cargada")
            onFailure("No se pudo cargar libreria nativa grape_pipeline_jni")
            return
        }

        try {
            Log.d(
                "CppPipelineBridge",
                "Llamando nativeRunPipeline con varietyId=$varietyId provider=$providerPreference imagePath=$imagePath"
            )
            val resultJson = nativeRunPipeline(
                imagePath = imagePath,
                segModelPath = segModelPath,
                regModelPath = regModelPath,
                varietyId = varietyId ?: -1,
                providerPreference = providerPreference,
                smoothEdges = smoothEdges,
                useDepth = useDepth
            )

            writeUnifiedDebugFile(
                imagePath = imagePath,
                varietyId = varietyId,
                providerPreference = providerPreference,
                nativeResultJson = resultJson,
                errorMessage = null
            )

            Log.d("CppPipelineBridge", "nativeRunPipeline retornó: ${resultJson.take(100)}...")
            val out = parseSuccessFromJson(resultJson, inputFrame)
            Log.d("CppPipelineBridge", "parseSuccessFromJson exitoso")
            onSuccess(out)
        } catch (t: Throwable) {
            Log.e("CppPipelineBridge", "Exception:", t)

            writeUnifiedDebugFile(
                imagePath = imagePath,
                varietyId = varietyId,
                providerPreference = providerPreference,
                nativeResultJson = null,
                errorMessage = t.message ?: "Fallo pipeline C++"
            )

            onFailure(t.message ?: "Fallo pipeline C++")
        }
    }

    private fun writeUnifiedDebugFile(
        imagePath: String,
        varietyId: Int?,
        providerPreference: String,
        nativeResultJson: String?,
        errorMessage: String?
    ) {
        try {
            val now = System.currentTimeMillis()
            val sourceLabel = File(imagePath).name
            val runId = "run_${now}_${sourceLabel}"

            val root = JSONObject()
            root.put("run_id", runId)
            root.put("timestamp", now)
            root.put("source_label", sourceLabel)
            root.put("image_path", imagePath)
            root.put("variety_id_sent", varietyId ?: -1)
            root.put("provider_requested", providerPreference)

            if (nativeResultJson.isNullOrBlank()) {
                root.put("status", false)
                root.put("error", errorMessage ?: "Fallo pipeline C++")
                root.put("native_json_raw", JSONObject.NULL)
            } else {
                root.put("native_json_raw", nativeResultJson)

                val nativeObj = try {
                    JSONObject(nativeResultJson)
                } catch (_: Throwable) {
                    null
                }

                if (nativeObj != null) {
                    val status = nativeObj.optBoolean("status", false)
                    root.put("status", status)
                    root.put("error", nativeObj.optString("error", ""))
                    root.put("bunchColor", nativeObj.optString("variety", ""))
                    root.put("qty", nativeObj.optInt("count_total", 0))
                    root.put("mean", nativeObj.optDouble("mean", 0.0))
                    root.put("mode", nativeObj.optDouble("mode", 0.0))
                    root.put("std", nativeObj.optDouble("std", 0.0))
                    root.put("provider", nativeObj.optString("provider", ""))
                    root.put("seg_overlay_path", nativeObj.optString("seg_overlay_path", ""))
                    root.put("num_grape_det", nativeObj.optInt("num_grape_det", 0))
                    root.put("num_pingpong_det", nativeObj.optInt("num_pingpong_det", 0))
                    root.put("pred", nativeObj.optJSONArray("pred") ?: JSONArray())
                    root.put("bins", nativeObj.optJSONArray("bins") ?: JSONArray())
                } else {
                    root.put("status", false)
                    root.put("error", errorMessage ?: "nativeRunPipeline devolvio JSON invalido")
                }
            }

            val debugFile = File(context.cacheDir, "debug_kotlin.json")
            debugFile.writeText(root.toString(2))
            Log.d("CppPipelineBridge", "Debug actualizado: ${debugFile.absolutePath}")
        } catch (t: Throwable) {
            Log.w("CppPipelineBridge", "No se pudo escribir debug_kotlin.json", t)
        }
    }

    private fun parseSuccessFromJson(json: String, frame: Bitmap): Success {
        val root = JSONObject(json)
        val status = root.optBoolean("status", false)
        if (!status) {
            val err = root.optString("error", "Fallo pipeline C++")
            throw IllegalStateException(err)
        }

        val overlayPath = root.optString("seg_overlay_path", "")
        val overlayBitmap = if (overlayPath.isNotBlank()) {
            try {
                BitmapFactory.decodeFile(overlayPath)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        val predArray = root.optJSONArray("pred") ?: JSONArray()
        val binsArray = root.optJSONArray("bins") ?: JSONArray()

        val pred = mutableListOf<Int>()
        for (i in 0 until predArray.length()) pred.add(predArray.optInt(i, 0))

        val bins = mutableListOf<Float>()
        for (i in 0 until binsArray.length()) bins.add(binsArray.optDouble(i, 0.0).toFloat())

        val calPredict = CalPredict(
            status = true,
            error = "",
            bunchColor = root.optString("variety", ""),
            qty = root.optInt("count_total", pred.sum()),
            std = root.optDouble("std", 0.0).toFloat(),
            mean = root.optDouble("mean", 0.0).toFloat(),
            mode = root.optDouble("mode", 0.0).toFloat(),
            pred = pred,
            bins = bins
        )

        return Success(
            preProcessTime = root.optLong("pre_ms", 0L),
            interfaceTime = root.optLong("infer_ms", 0L),
            postProcessTime = root.optLong("post_ms", 0L),
            results = emptyList(),
            depthMap = null,
            mmPerPx = root.optDouble("mm_per_px", 0.0).toFloat(),
            predictsList = listOf(calPredict),
            imageOrig = frame,
            imagePro = Pair(frame, overlayBitmap)
        )
    }

    private external fun nativeRunPipeline(
        imagePath: String,
        segModelPath: String,
        regModelPath: String,
        varietyId: Int,
        providerPreference: String,
        smoothEdges: Boolean,
        useDepth: Boolean
    ): String

    private external fun nativeRelease()
}
